import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.trial_realtime import RealtimeTrialSession
from app.trial_policy import TrialAnalyzer
from app.trial_v55 import phase_hint
from tests.test_trial_realtime import ADMITTED, SETTINGS, output, session, sent


def v55_session():
    s = session()
    s.v55 = True
    s.lesson = dict(s.lesson, kind='trial', trial_version='v5.5', duration_minutes=30)
    s.trial_state = {'phase': 'conversation', 'connectedSeconds': 800}
    return s


@pytest.mark.asyncio
async def test_v55_configuration_restores_phase_and_exposes_only_narrow_learning_tools():
    s = v55_session()
    s.provider.recv = AsyncMock(return_value=json.dumps({'type': 'session.updated'}))
    s.backend.command.return_value = {'phase': 'conversation', 'connectedSeconds': 800}
    await s.configure()
    config = sent(s)[0]['session']
    assert '30-minute Trial' in config['instructions']
    assert '"connectedSeconds": 800' in config['instructions']
    names = {t['name'] for t in config['tools']}
    assert {'update_trial_preferences', 'get_trial_preferences', 'set_trial_phase'} <= names
    assert 'learning.update' not in names and 'update_onboarding' not in names
    connected = s.socket.send_json.call_args_list[0].args[0]
    assert connected['trialExecution']['connectedSeconds'] == 800


@pytest.mark.asyncio
async def test_phase_call_at_two_seconds_waits_for_saved_first_learner_reply():
    s = v55_session()
    # The DEV phase event occurred at 2.519s. Foundation reports whole connected seconds.
    s.trial_state = {'phase': 'warmup', 'connectedSeconds': 2}
    transition = {'type': 'function_call', 'name': 'set_trial_phase',
                  'call_id': 'premature', 'arguments': '{"phase":"conversation"}'}
    await s.provider_event({'type': 'response.done',
                            'response': {'status': 'completed', 'output': [transition]}})
    s.backend.command.assert_not_awaited()
    assert json.loads(sent(s)[-1]['item']['output']) == {'ok': False, 'error': 'learner_turn_required'}
    assert not any(event['type'] == 'response.create' for event in sent(s))

    await s.provider_event({'type': 'input_audio_buffer.committed', 'item_id': 'learner-turn'})
    assert not any(event['type'] == 'response.create' for event in sent(s))
    s.backend.command.return_value = {'eventId': 'saved-reply'}
    await s.provider_event({'type': 'conversation.item.input_audio_transcription.completed',
                            'item_id': 'learner-turn', 'transcript': 'Да, давайте поговорим о встречах.'})
    assert sent(s)[-1] == {'type': 'response.create', 'response': {}}

    s.backend.command.return_value = {'phase': 'conversation', 'connectedSeconds': 10}
    await s.provider_event({'type': 'response.done', 'response': {
        'status': 'completed', 'output': [dict(transition, call_id='after-learner')],
    }})
    assert s.backend.command.call_args.args[2] == 'trial.phase'
    assert s.trial_state['phase'] == 'conversation'


@pytest.mark.asyncio
@pytest.mark.parametrize('phase,seconds,explain_roadmap', [
    ('wrapup', 1844, True),
    ('conversation', 1800, True),
    ('warmup', 20, False),
])
async def test_finish_tool_sets_roadmap_handoff_only_for_normal_wrapup(phase, seconds, explain_roadmap):
    s = v55_session()
    s.trial_state = {'phase': phase, 'connectedSeconds': seconds}
    await s.provider_event({'type': 'response.done', 'response': {'status': 'completed', 'output': [
        {'type': 'function_call', 'name': 'finish_after_reply', 'call_id': 'finish', 'arguments': '{}'},
    ]}})
    response = sent(s)[-1]['response']
    if explain_roadmap:
        assert response['tool_choice'] == 'none'
        assert 'После урока мы разберём наш разговор' in response['instructions']
        assert 'Вы получите в Telegram персональный план занятий' in response['instructions']
        assert 'даже если вы решите заниматься самостоятельно' in response['instructions']
    else:
        assert response == {}

    await output(s, 'goodbye', finish=s.finish_next)
    await s.provider_event({'type': 'response.output_audio.done', 'item_id': 'goodbye'})
    goodbye = ('Вы получите в Telegram персональный план занятий. До встречи!'
               if explain_roadmap else 'До встречи!')
    await s.provider_event({'type': 'response.output_audio_transcript.done', 'item_id': 'goodbye',
                            'transcript': goodbye})
    await s.provider_event({'type': 'response.done', 'response': {'status': 'completed', 'output': []}})
    assert not s.ended
    await s.settle('goodbye', {'complete': True, 'playedMs': 100})
    assert s.ended
    assert s.backend.command.call_args_list[-2].args[3]['body'] == goodbye
    assert s.backend.command.call_args.args[3]['reason'] == 'natural_goodbye'


@pytest.mark.asyncio
async def test_preferences_require_durable_user_source_and_preserve_null():
    s = v55_session()
    arguments = {'sourceId': 'event', 'expectedRevision': 2,
                 'changes': [{'field': 'consent', 'value': 'no', 'evidence': 'No homework'},
                             {'field': 'lessonsPerWeek', 'value': None, 'evidence': 'I do not know'}]}
    await s.tool({'name': 'update_trial_preferences', 'arguments': json.dumps(arguments), 'call_id': 'missing'})
    s.backend.command.assert_not_awaited()
    assert json.loads(sent(s)[-1]['item']['output'])['ok'] is False
    s.memory_sources['event'] = {'id': 'event', 'body': 'No homework. I do not know'}
    await s.tool({'name': 'update_trial_preferences', 'arguments': json.dumps(arguments), 'call_id': 'save'})
    command = s.backend.command.call_args
    assert command.args[1] == 'episode:save'
    assert command.args[2] == 'trial.preferences'
    assert command.args[3]['changes'][1]['value'] is None
    await s.tool({'name': 'update_trial_preferences', 'arguments': json.dumps(arguments), 'call_id': 'save'})
    assert s.backend.command.await_count == 1


@pytest.mark.asyncio
async def test_final_transcript_has_real_span_and_unknown_asr_is_not_invented():
    s = v55_session()
    s.turn_times['turn'] = '2026-11-16T12:00:00+00:00'
    s.input_spans['turn'] = {'audioStartMs': 100, 'audioEndMs': 800}
    s.backend.command.return_value = {'eventId': 'persisted'}
    await s.provider_event({'type': 'conversation.item.input_audio_transcription.completed', 'item_id': 'turn', 'transcript': 'Hello'})
    args = s.backend.command.call_args.args[3]
    assert args['metadata'] == {'providerItemId': 'turn', 'finality': 'final', 'audioStartMs': 100, 'audioEndMs': 800}
    assert args['revision'] == 1
    assert args['occurredAt'] == '2026-11-16T12:00:00+00:00'
    assert s.memory_sources['persisted']['body'] == 'Hello'


def test_phase_cues_follow_total_connected_time_not_new_episode():
    assert phase_hint({'phase': 'warmup', 'connectedSeconds': 301})[0] == 'conversation'
    assert phase_hint({'phase': 'conversation', 'connectedSeconds': 1501})[0] == 'wrapup'
    assert phase_hint({'phase': 'wrapup', 'connectedSeconds': 1801})[0] == 'closing'
    assert phase_hint({'phase': 'conversation', 'connectedSeconds': 800}) == (None, None)


@pytest.mark.asyncio
async def test_personal_memory_only_never_runs_educational_analysis():
    model = SimpleNamespace(complete_json=AsyncMock(return_value=json.dumps({'facts': [], 'candidates': []})))
    context = SimpleNamespace(data={'sourceEvents': [{'id': 'event', 'role': 'user', 'body': 'I work in IT'}]})
    result = await TrialAnalyzer(model).evaluate(context, {'memoryOnly': True})
    assert result['observations'] == [] and result['notes'] == []
    assert model.complete_json.call_args.kwargs['operation'] == 'trial_personal_memory'
    schema = model.complete_json.call_args.kwargs['response_format']['json_schema']['schema']
    assert set(schema['properties']) == {'facts', 'candidates'}


@pytest.mark.asyncio
async def test_personal_memory_keeps_only_items_with_valid_user_evidence(caplog):
    caplog.set_level('INFO', logger='app.diagnostics')
    work = {'key': 'work', 'value': 'IT', 'sourceId': 'learner',
            'evidence': 'I work in IT', 'expectedVersion': 0}
    candidate = {'key': 'leisure', 'value': 'Cooking', 'sourceId': 'learner'}
    model = SimpleNamespace(complete_json=AsyncMock(return_value=json.dumps({
        'facts': [
            work,
            dict(work, sourceId='unknown'),
            dict(work, evidence='I work in technology'),
            dict(work, evidence=' '),
            dict(work, sourceId='tutor'),
        ],
        'candidates': [candidate, dict(candidate, sourceId='unknown'), dict(candidate, sourceId='tutor')],
    })))
    context = SimpleNamespace(data={'sourceEvents': [
        {'id': 'learner', 'role': 'user', 'body': 'I work in IT and I like cooking.'},
        {'id': 'tutor', 'role': 'assistant', 'body': 'I work in IT'},
    ]})

    result = await TrialAnalyzer(model).evaluate(context, {'memoryOnly': True})

    assert result == {'status': 'evaluated', 'facts': [work], 'candidates': [candidate],
                      'observations': [], 'notes': []}
    model.complete_json.assert_awaited_once()
    assert model.complete_json.call_args.kwargs['retry'] is False
    assert [json.loads(record.getMessage().removeprefix('runtime_event ')) for record in caplog.records] == [{
        'service': 'ai', 'event': 'trial_personal_memory_evidence_rejected',
        'rejected_facts': 4, 'rejected_candidates': 2,
    }]


@pytest.mark.asyncio
async def test_personal_memory_returns_insufficient_evidence_if_every_item_is_rejected():
    model = SimpleNamespace(complete_json=AsyncMock(return_value=json.dumps({
        'facts': [{'key': 'work', 'value': 'IT', 'sourceId': 'learner',
                   'evidence': 'I work in technology', 'expectedVersion': 0}],
        'candidates': [{'key': 'work', 'value': 'IT', 'sourceId': 'unknown'}],
    })))
    context = SimpleNamespace(data={'sourceEvents': [
        {'id': 'learner', 'role': 'user', 'body': 'I work in IT'},
    ]})

    result = await TrialAnalyzer(model).evaluate(context, {'memoryOnly': True})

    assert result == {'status': 'insufficient_evidence', 'facts': [], 'candidates': [],
                      'observations': [], 'notes': []}
    model.complete_json.assert_awaited_once()


@pytest.mark.asyncio
async def test_v55_followup_never_uses_old_roadmap_or_sales_model():
    from fastapi import FastAPI
    import httpx
    from app.trial_api import install_trial_api
    app = FastAPI()
    model = SimpleNamespace(complete_json=AsyncMock())
    context = SimpleNamespace(build=AsyncMock(return_value=SimpleNamespace(
        data={'lesson': {'kind': 'trial', 'trial_version': 'v5.5', 'status': 'completed'},
              'roadmap': {'old': 'Must not be presented'}}, history=[])))
    install_trial_api(app, None, context, model, None, SETTINGS, None)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://test') as http:
        for kind in ('followup', 'end_button'):
            result = await http.post('/internal/trial/followup', headers={'X-Execution-Scope': 'scope'},
                                     json={'lesson_id': 'lesson', 'kind': kind})
            assert result.status_code == 200
            assert 'Спасибо' in result.json()['reply']
    model.complete_json.assert_not_awaited()
