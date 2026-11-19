import base64
import json
from datetime import datetime, timezone
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app import trial_sp24
from app.context_builder import ContextBuilder
from app.foundation_client import BackendFailure
from app.foundation_tutor import FoundationTutor
from app.kora_personality import PERSONALITY
from app.lesson_calendar import calendar_export
from app.trial_realtime import RealtimeTrialSession
from tests.test_foundation_current_message import snapshot
from tests.test_trial_realtime import ADMITTED, SETTINGS, sent, session


def clock(phase='warmup', ms=0, start=None, offer=None, reaction=None):
    return {'phase': phase, 'connectedSeconds': ms // 1000, 'connectedMs': ms, 'plannedSeconds': 1200,
            'conversationStartMs': start, 'conversationEndMs': None if start is None else start + 900_000,
            'offerSequence': offer, 'regularReaction': reaction}


def sp24_session(state=None, *, context=None, history=None):
    doubles = session()
    admitted = {**ADMITTED, 'lesson': {**ADMITTED['lesson'], 'kind': 'trial',
                'trial_version': 'v5.5', 'duration_minutes': 20}, 'trialExecution': state or clock()}
    doubles.contexts.build.return_value = SimpleNamespace(data=context or {}, history=history or [])
    result = RealtimeTrialSession(doubles.socket, doubles.backend, doubles.contexts, doubles.store, admitted, SETTINGS)
    result.provider = doubles.provider
    result.provider.recv = AsyncMock(return_value=json.dumps({'type': 'session.updated'}))
    result.backend.command.return_value = state or clock()
    return result


def last_response(s):
    return next(item['response'] for item in reversed(sent(s)) if item['type'] == 'response.create')


def function_call(name, arguments, call_id=None):
    return {'type': 'function_call', 'name': name, 'arguments': json.dumps(arguments), 'call_id': call_id or name}


async def learner_turn(s, body, sequence):
    s.backend.command.return_value = {'eventId': f'source-{sequence}', 'sequence': sequence}
    await s.provider_event({'type': 'input_audio_buffer.committed', 'item_id': f'turn-{sequence}'})
    await s.provider_event({'type': 'conversation.item.input_audio_transcription.completed',
                            'item_id': f'turn-{sequence}', 'transcript': body})


async def response_done(s, *outputs):
    await s.provider_event({'type': 'response.done', 'response': {'status': 'completed', 'output': list(outputs)}})


async def finish_audio_item(s, key, text):
    item = s.items[key]
    item.update(bytes=4800, done=True, text=text, generation_done=True, sent_at=0)
    await s.settle(key, {'complete': True, 'playedMs': 100})


@pytest.mark.asyncio
@pytest.mark.parametrize('confirmed', [False, True])
async def test_opening_waits_and_only_unconfirmed_address_gets_separate_question(confirmed):
    address = {'value': 'Алекс', 'source': 'telegram_explicit'} if confirmed else None
    s = sp24_session(context={'preferredAddress': address, 'facts': [{'fact_key': 'name', 'value': 'Display name'}]})
    await s.configure()
    config = sent(s)[0]['session']
    headers = [line for line in config['instructions'].splitlines() if line.startswith('## ')]
    assert headers == ['## Кто я', '## Что я делаю', '## Как я говорю']
    assert PERSONALITY in config['instructions']
    assert 'Listen most of the time' in config['instructions']
    assert 'One question at a time' in config['instructions']
    assert 'Treat an A2 speaker as an adult' in config['instructions']
    assert 'Never conduct deliberate grammar checks' in config['instructions']
    assert not any(phrase in config['instructions'] for phrase in ('30-minute Trial', '25–30 minutes'))
    assert last_response(s)['instructions'].endswith(trial_sp24.OPENING)
    assert last_response(s)['tool_choice'] == 'none'
    await response_done(s)
    await s.provider_event({'type': 'input_audio_buffer.committed', 'item_id': 'greeting-answer'})
    assert len([item for item in sent(s) if item['type'] == 'response.create']) == 1
    s.backend.command.return_value = {'eventId': 'answer', 'sequence': 2}
    await s.provider_event({'type': 'conversation.item.input_audio_transcription.completed',
                            'item_id': 'greeting-answer', 'transcript': 'Хорошо, спасибо'})
    if confirmed:
        assert last_response(s) == {}
    else:
        assert trial_sp24.ASK_ADDRESS in last_response(s)['instructions']


@pytest.mark.asyncio
async def test_address_save_uses_current_learner_source_then_applies_in_same_call():
    s = sp24_session()
    s.address_question_requested = True
    s.trial_learner_has_spoken = True
    s.memory_sources = {'name': {'id': 'name', 'body': 'Зови меня Алекс'}}
    s.backend.command.return_value = {'value': 'Алекс', 'source': 'voice_explicit', 'sourceEventId': 'name'}
    args = {'sourceId': 'name', 'value': 'Алекс', 'evidence': 'Зови меня Алекс'}
    await s.tool(function_call('set_preferred_address', args))
    assert s.backend.command.await_args.args[2:] == ('student.preferred_address', {
        'lessonId': 'lesson', 'callSessionId': 'episode', **args})
    assert s.address_confirmed
    assert json.loads(sent(s)[-1]['item']['output'])['preferredAddress']['value'] == 'Алекс'
    await s.tool(function_call('set_preferred_address', {**args, 'sourceId': 'display-name'}, 'invalid'))
    assert s.backend.command.await_count == 1


@pytest.mark.asyncio
async def test_english_starts_on_audio_and_refusing_a_name_does_not_block():
    s = sp24_session(clock(ms=110_000))
    s.trial_learner_has_spoken = True
    s.address_question_requested = True
    await learner_turn(s, 'Не хочу называть имя, давай на английском', 4)
    await response_done(s, function_call('set_trial_phase', {'phase': 'conversation'}))
    assert s.trial_state['phase'] == 'warmup'
    assert last_response(s)['instructions'].endswith(trial_sp24.START_ENGLISH)
    assert not any(c.args[2] == 'trial.phase' for c in s.backend.command.await_args_list)

    async def command(scope, operation, action, args):
        if action == 'trial.phase':
            assert args['phase'] == 'conversation'
            return clock('conversation', 111_404, 111_404)
        return {'objectKey': 'trial/key'}

    s.backend.command.side_effect = command
    await s.provider_event({'type': 'response.output_item.added', 'item': {'type': 'message', 'id': 'first-en'}})
    await s.provider_event({'type': 'response.output_audio.delta', 'item_id': 'first-en', 'content_index': 0,
                            'delta': base64.b64encode(b'\x00\x00').decode()})
    assert s.trial_state['conversationStartMs'] == 111_404
    assert s.trial_state['conversationEndMs'] == 1_011_404
    assert s.backend.command.await_args_list[-3].args[2] == 'trial.phase'


@pytest.mark.asyncio
async def test_intro_cannot_start_english_in_first_minute():
    s = sp24_session(clock(ms=30_000))
    s.address_confirmed = s.trial_learner_has_spoken = True
    await s.tool(function_call('set_trial_phase', {'phase': 'conversation'}))
    await s.maybe_respond()
    assert s.trial_state['phase'] == 'warmup'
    assert last_response(s) == {}
    s.backend.command.assert_not_awaited()


@pytest.mark.asyncio
async def test_reconnect_resumes_clock_and_only_server_opens_russian_wrapup():
    state = clock('conversation', 1_010_500, 111_404)
    s = sp24_session(state, context={'preferredAddress': {'value': 'Алекс', 'source': 'voice_explicit'}},
                     history=[{'role': 'assistant', 'content': 'How is your project going?'}])
    await s.configure()
    assert s.address_confirmed
    assert not any(item['type'] == 'response.create' for item in sent(s))
    await s.advance_trial_clock()
    assert s.backend.command.await_count == 1
    await s.tool(function_call('set_trial_phase', {'phase': 'wrapup'}))
    assert s.backend.command.await_count == 1
    s.connected_trial_ms = lambda: 1_011_404
    s.backend.command.return_value = clock('wrapup', 1_011_404, 111_404)
    await s.maybe_respond()
    assert s.backend.command.await_args.args[2] == 'trial.phase'
    assert last_response(s)['instructions'].endswith(trial_sp24.OFFER)
    assert [tool['name'] for tool in last_response(s)['tools']] == ['finish_after_reply']


@pytest.mark.asyncio
@pytest.mark.parametrize('reaction,body', [('yes', 'Да, давай'), ('no', 'Нет, спасибо'), ('undecided', 'А сколько это стоит?')])
async def test_one_played_offer_waits_for_reaction_and_closes_after_goodbye(reaction, body):
    s = sp24_session(clock('wrapup', 1_020_000, 120_000))
    await s.configure()
    await s.provider_event({'type': 'response.output_item.added', 'item': {'type': 'message', 'id': 'offer'}})
    s.backend.command.return_value = {'eventId': 'played-offer', 'sequence': 10}
    await response_done(s)
    await finish_audio_item(s, 'offer', 'План бесплатный. Хочешь заниматься регулярно?')
    assert s.backend.command.await_args.args[3]['metadata']['trialClosingOffer'] is True
    assert s.offer_sequence == 10
    s.want_response = True
    await s.maybe_respond()
    assert not s.want_response
    await learner_turn(s, body, 11)
    s.backend.command.return_value = {'regularReaction': reaction}
    await response_done(s, function_call('record_trial_reaction', {'sourceId': 'source-11', 'reaction': reaction,
                                                                 'evidence': body}))
    assert s.backend.command.await_args.args[2] == 'trial.reaction'
    await response_done(s, function_call('finish_after_reply', {'reason': 'wrapup_complete'}))
    assert last_response(s)['instructions'].endswith(trial_sp24.GOODBYE)
    await s.provider_event({'type': 'response.output_item.added', 'item': {'type': 'message', 'id': 'bye'}})
    await response_done(s)
    await finish_audio_item(s, 'bye', 'До встречи!')
    assert s.backend.command.await_args.args[2:] == ('voice.end', {
        'lessonId': 'lesson', 'callSessionId': 'episode', 'reason': 'natural_goodbye'})
    assert s.ended


@pytest.mark.asyncio
async def test_early_exit_reports_early_reason_and_avoids_offer():
    s = sp24_session(clock('conversation', 150_000, 120_000))
    await learner_turn(s, 'Мне нужно идти, давай закончим', 3)
    await response_done(s, function_call('finish_after_reply', {'reason': 'learner_requested'}))
    assert last_response(s)['instructions'].endswith(trial_sp24.EARLY_GOODBYE)
    await s.provider_event({'type': 'response.output_item.added', 'item': {'type': 'message', 'id': 'bye'}})
    await response_done(s)
    await finish_audio_item(s, 'bye', 'Спасибо, до встречи!')
    assert s.backend.command.await_args.args[3]['reason'] == 'learner_requested'


@pytest.mark.asyncio
async def test_rejected_wrapup_at_clock_boundary_can_retry():
    s = sp24_session(clock('conversation', 1_020_000, 120_000))
    s.backend.command.side_effect = BackendFailure('trial_conversation_in_progress', 409)
    await s.advance_trial_clock()
    assert s.trial_state['phase'] == 'conversation'


@pytest.mark.asyncio
async def test_telegram_saves_address_and_all_contexts_project_only_student_preference():
    address = {'value': 'Аля', 'source': 'telegram_explicit', 'sourceEventId': 'current'}
    data = snapshot('Зови меня Аля')
    data['student']['profile'] = {'preferredAddress': address}
    backend = SimpleNamespace(snapshot=AsyncMock(return_value=data), command=AsyncMock(return_value=address))
    contexts = ContextBuilder(backend)
    tutor = FoundationTutor(backend, None, contexts)
    result = await tutor.execute('text-scope', 'request', {'id': 'current'}, 'set_preferred_address',
                                 {'value': 'Аля', 'evidence': 'Зови меня Аля'})
    assert result['preferredAddress'] == address
    assert backend.command.await_args.args[2:] == ('student.preferred_address', {
        'sourceId': 'current', 'value': 'Аля', 'evidence': 'Зови меня Аля'})
    for purpose in ('text', 'voice'):
        assert (await contexts.build('scope', purpose)).data['preferredAddress'] == address
    data['student']['profile'] = {'username': 'alya', 'displayName': 'Алевтина'}
    assert (await contexts.build('scope', 'voice')).data['preferredAddress'] is None


def test_roadmap_prerequisites_and_saved_calendar_duration():
    assert 'missing' in trial_sp24.roadmap_guidance({})
    goal = {'content': {'value': 'Speak at work'}}
    assert 'missing' in trial_sp24.roadmap_guidance({'goal': goal, 'learningState': {'profile': {'targetLevel': 'Unknown'}}})
    assert 'free plan will follow' in trial_sp24.roadmap_guidance(
        {'goal': goal, 'learningState': {'profile': {'targetLevel': 'B2'}}})
    starts = datetime(2026, 11, 19, tzinfo=timezone.utc).isoformat()
    for kind, duration in (('trial', 15), ('trial', 20), ('trial', 30), ('regular', 30)):
        card = calendar_export(dict(id='lesson', kind=kind, starts_at=starts, updated_at=starts,
                                    version=1, status='scheduled', duration_minutes=duration))
        assert f'DTEND:20261119T00{duration}00Z' in card['ics']


@pytest.mark.parametrize('reaction', ['yes', 'no', 'undecided', None])
def test_telegram_followup_discusses_schedule_only_after_yes(reaction):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    from app.trial_api import install_trial_api

    app = FastAPI()
    context = SimpleNamespace(build=AsyncMock(return_value=SimpleNamespace(
        data={'lesson': {'kind': 'trial', 'trial_version': 'v5.5', 'trial_regular_reaction': reaction}})))
    install_trial_api(app, None, context, None, None, SimpleNamespace(), None)
    with TestClient(app) as client:
        result = client.post('/internal/trial/followup', headers={'X-Execution-Scope': 'voice'},
                             json={'kind': 'followup', 'lesson_id': 'lesson'})
    assert result.status_code == 200
    assert ('расписании' in result.json()['reply']) == (reaction == 'yes')
