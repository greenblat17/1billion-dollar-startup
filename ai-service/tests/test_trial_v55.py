import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.trial_realtime import RealtimeProviderError, RealtimeTrialSession
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
    instructions = ' '.join(config['instructions'].split())
    assert "range ('two or three times a week')" in instructions
    assert "approximation ('about three times a week')" in instructions
    assert "broken transcript containing 'three times a week' is insufficient" in instructions
    assert 'leave it unknown if the learner does not choose' in instructions
    tool = next(t for t in config['tools'] if t['name'] == 'update_trial_preferences')
    assert 'Omit unresolved fields' in tool['description']
    assert 'Save a clear Homework refusal independently' in tool['description']
    evidence = tool['parameters']['$defs']['PreferenceChange']['properties']['evidence']
    assert 'the full turn must clearly choose one exact count' in evidence['description']
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
async def test_trial_wrapup_waits_for_saved_preference_source_before_requesting_reply():
    s = v55_session()
    s.trial_state = {'phase': 'wrapup', 'connectedSeconds': 1640}
    await s.provider_event({'type': 'input_audio_buffer.committed', 'item_id': 'learner'})
    assert s.socket.send_json.await_args.args[0] == {'type': 'user_turn_complete'}
    assert not sent(s)
    s.backend.command.assert_not_awaited()

    async def save_turn(scope, operation, action, args):
        assert action == 'voice.append'
        assert args['body'] == 'I do not want homework.'
        assert not sent(s)
        return {'eventId': 'saved-refusal'}

    s.backend.command.side_effect = save_turn
    await s.provider_event({'type': 'conversation.item.input_audio_transcription.completed',
                            'item_id': 'learner', 'transcript': 'I do not want homework.'})

    source_event, reply = sent(s)
    assert source_event['type'] == 'conversation.item.create'
    source_text = source_event['item']['content'][0]['text']
    assert json.loads(source_text.split(': ', 1)[1]) == {
        'id': 'saved-refusal', 'body': 'I do not want homework.',
    }
    assert reply == {'type': 'response.create', 'response': {}}


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


async def played_trial_output(s, key, text, *, finish=False):
    await output(s, key, finish=finish)
    await s.provider_event({'type': 'response.output_audio.done', 'item_id': key})
    await s.provider_event({'type': 'response.output_audio_transcript.done', 'item_id': key, 'transcript': text})
    await s.settle(key, {'complete': True, 'playedMs': 100})


async def hit_trial_output_limit(s, *items):
    await s.provider_event({'type': 'response.done', 'response': {
        'status': 'incomplete', 'status_details': {'reason': 'max_output_tokens'},
        'output': list(items),
    }})


@pytest.mark.asyncio
async def test_trial_output_limit_keeps_played_evidence_and_recovers_once():
    s = v55_session()
    s.append = AsyncMock(wraps=s.append)
    s.trial_state = {'phase': 'wrapup', 'connectedSeconds': 1541}
    await s.provider_event({'type': 'response.created'})
    await played_trial_output(s, 'complete', 'Tell me about your next meeting.')
    await played_trial_output(s, 'cut', 'To prepare for the meeting, you')
    assert not s.append.await_count

    await hit_trial_output_limit(
        s, {'type': 'message', 'id': 'complete', 'status': 'completed'},
        {'type': 'message', 'id': 'cut', 'status': 'incomplete'},
        {'type': 'function_call', 'name': 'finish_after_reply', 'call_id': 'cut-tool', 'arguments': '{}'},
    )

    assert [(call.args[1], call.kwargs['metadata']) for call in s.append.await_args_list] == [
        ('Tell me about your next meeting.', {
            'providerItemId': 'complete', 'finality': 'final', 'playbackComplete': True, 'playedMs': 100,
        }),
        ('To prepare for the meeting, you', {
            'providerItemId': 'cut', 'finality': 'partial', 'playbackComplete': True, 'playedMs': 100,
        }),
    ]
    assert sent(s) == [{'type': 'response.create', 'response': {
        'instructions': 'Continue naturally from what the learner already heard. Speak one short sentence and pause.',
        'tool_choice': 'none',
    }}]
    assert s.trial_state == {'phase': 'wrapup', 'connectedSeconds': 1541}
    await played_trial_output(s, 'cut-again', 'We can')
    await hit_trial_output_limit(s)
    assert len([event for event in sent(s) if event['type'] == 'response.create']) == 1
    assert not s.ended
    assert not any(call.args[2] == 'voice.end' for call in s.backend.command.await_args_list)


@pytest.mark.asyncio
async def test_trial_output_limit_keeps_audio_when_completed_item_has_no_transcript():
    s = v55_session()
    s.append = AsyncMock(wraps=s.append)
    await output(s, 'missing-transcript')
    await s.provider_event({'type': 'response.output_audio.done', 'item_id': 'missing-transcript'})
    await s.settle('missing-transcript', {'complete': True, 'playedMs': 100})
    await hit_trial_output_limit(s, {'type': 'message', 'id': 'missing-transcript', 'status': 'completed'})
    assert s.append.await_args.kwargs['metadata'] == {
        'providerItemId': 'missing-transcript', 'finality': 'partial', 'playbackComplete': True, 'playedMs': 100,
    }
    assert sent(s)[-1]['type'] == 'response.create'


@pytest.mark.asyncio
async def test_trial_cut_goodbye_waits_for_playback_and_only_complete_recovery_finishes():
    s = v55_session()
    s.append = AsyncMock(wraps=s.append)
    s.trial_state = {'phase': 'wrapup', 'connectedSeconds': 1800}
    await s.provider_event({'type': 'response.created'})
    await output(s, 'cut-goodbye', finish=True)
    await hit_trial_output_limit(s)
    assert s.socket.send_json.await_args.args[0] == {'type': 'audio_end', 'id': 'cut-goodbye'}
    assert not sent(s)
    sent_count = s.socket.send_json.await_count
    await s.provider_event({'type': 'response.output_audio.done', 'item_id': 'cut-goodbye'})
    assert s.socket.send_json.await_count == sent_count
    assert not s.ended

    await s.settle('cut-goodbye', {'complete': True, 'playedMs': 100})
    assert s.append.await_args.kwargs['metadata'] == {
        'providerItemId': 'cut-goodbye', 'finality': 'partial', 'playbackComplete': True, 'playedMs': 100,
    }
    assert sent(s)[-1]['response']['tool_choice'] == 'none'
    assert 'Вы получите в Telegram персональный план занятий' in sent(s)[-1]['response']['instructions']
    await played_trial_output(s, 'recovery', 'Вы получите персональный план в Telegram. До встречи!',
                              finish=s.finish_next)
    assert not s.ended
    await s.provider_event({'type': 'response.done', 'response': {'status': 'completed'}})
    assert s.ended
    assert s.append.await_args.kwargs['metadata']['finality'] == 'final'
    assert [call.args[3]['reason'] for call in s.backend.command.await_args_list
            if call.args[2] == 'voice.end'] == ['natural_goodbye']


@pytest.mark.asyncio
async def test_trial_barge_in_supersedes_queued_goodbye_recovery():
    s = v55_session()
    s.append = AsyncMock(wraps=s.append)
    s.trial_state = {'phase': 'wrapup', 'connectedSeconds': 1800}
    await output(s, 'cut-goodbye', finish=True)
    await hit_trial_output_limit(s)
    await s.provider_event({'type': 'input_audio_buffer.speech_started', 'item_id': 'learner'})
    await s.settle('cut-goodbye', {'complete': False, 'playedMs': 60})
    assert s.append.await_args.kwargs['metadata']['playedMs'] == 60
    await s.provider_event({'type': 'input_audio_buffer.committed', 'item_id': 'learner'})
    assert sent(s)[-1]['type'] == 'conversation.item.truncate'
    await s.provider_event({'type': 'conversation.item.truncated', 'item_id': 'cut-goodbye'})
    assert sent(s)[-1]['type'] == 'conversation.item.truncate'
    s.backend.command.return_value = {'eventId': 'saved-learner-turn'}
    await s.provider_event({'type': 'conversation.item.input_audio_transcription.completed',
                            'item_id': 'learner', 'transcript': 'I have one more question.'})
    assert sent(s)[-1] == {'type': 'response.create', 'response': {}}
    assert not s.finish_next and not s.ended


@pytest.mark.asyncio
async def test_trial_learner_commit_wins_over_an_inflight_goodbye_recovery():
    s = v55_session()
    s.trial_state = {'phase': 'wrapup', 'connectedSeconds': 1800}
    s.finish_next = True
    await s.provider_event({'type': 'response.output_item.added',
                            'item': {'type': 'message', 'id': 'silent-goodbye'}})
    await hit_trial_output_limit(s)
    assert s.items['silent-goodbye']['settled']
    assert sent(s)[-1]['response']['tool_choice'] == 'none'

    await played_trial_output(s, 'recovery', 'До встречи!', finish=s.finish_next)
    await s.provider_event({'type': 'input_audio_buffer.committed', 'item_id': 'learner'})
    await s.provider_event({'type': 'response.done', 'response': {'status': 'completed'}})
    assert len([event for event in sent(s) if event['type'] == 'response.create']) == 1
    s.backend.command.return_value = {'eventId': 'saved-learner-turn'}
    await s.provider_event({'type': 'conversation.item.input_audio_transcription.completed',
                            'item_id': 'learner', 'transcript': 'I have one more question.'})
    assert sent(s)[-1] == {'type': 'response.create', 'response': {}}
    assert not s.ended
    assert not any(call.args[2] == 'voice.end' for call in s.backend.command.await_args_list)


@pytest.mark.asyncio
async def test_trial_limit_between_speech_stop_and_commit_does_not_start_recovery():
    s = v55_session()
    await s.provider_event({'type': 'response.created'})
    await s.provider_event({'type': 'input_audio_buffer.speech_started', 'item_id': 'learner'})
    await s.provider_event({'type': 'input_audio_buffer.speech_stopped', 'item_id': 'learner'})
    await hit_trial_output_limit(s)
    assert not sent(s)
    await s.provider_event({'type': 'input_audio_buffer.committed', 'item_id': 'learner'})
    assert sent(s) == [{'type': 'response.create', 'response': {}}]


@pytest.mark.asyncio
@pytest.mark.parametrize('details', [{'reason': 'content_filter'}, {'reason': 'unrecognized'}, None])
async def test_other_trial_incomplete_reasons_fail_without_recovery(details):
    s = v55_session()
    with pytest.raises(RealtimeProviderError):
        await s.provider_event({'type': 'response.done', 'response': {
            'status': 'incomplete', 'status_details': details,
        }})
    assert not sent(s)


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
async def test_trial_can_forward_homework_no_without_writing_unresolved_frequency():
    s = v55_session()
    workload_turn = 'Or you can happen like we do for three times a week and do a little homework.'
    refusal = 'I do not want homework.'
    s.memory_sources = {
        'workload': {'id': 'workload', 'body': workload_turn},
        'refusal': {'id': 'refusal', 'body': refusal},
    }
    changes = [{'field': 'consent', 'value': 'no', 'evidence': refusal}]
    await s.tool({'name': 'update_trial_preferences', 'call_id': 'save-refusal',
                  'arguments': json.dumps({'sourceId': 'refusal', 'expectedRevision': 2, 'changes': changes})})

    s.backend.command.assert_awaited_once_with(s.scope, 'episode:save-refusal', 'trial.preferences', {
        'lessonId': s.lesson_id, 'callSessionId': s.episode, 'sourceId': 'refusal',
        'expectedRevision': 2, 'changes': changes,
    })


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
