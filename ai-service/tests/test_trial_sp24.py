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


def sp24_session(state=None, *, context=None, history=None, dialogue=None):
    doubles = session()
    admitted = {**ADMITTED, 'lesson': {**ADMITTED['lesson'], 'kind': 'trial',
                'trial_version': 'v5.5', 'duration_minutes': 20}, 'trialExecution': state or clock(),
                'trialDialogue': dialogue or {}}
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


def generations(s):
    return [item for item in sent(s) if item['type'] == 'response.create']


def updates(s):
    return [item['session'] for item in sent(s) if item['type'] == 'session.update']


async def configured(s):
    while s.sp24_update_pending is not None:
        await s.provider_event({'type': 'session.updated'})


async def learner_turn(s, body, sequence, *, ack=True):
    s.backend.command.return_value = {'eventId': f'source-{sequence}', 'sequence': sequence}
    await s.provider_event({'type': 'input_audio_buffer.committed', 'item_id': f'turn-{sequence}'})
    await s.provider_event({'type': 'conversation.item.input_audio_transcription.completed',
                            'item_id': f'turn-{sequence}', 'transcript': body})
    if ack:
        await configured(s)


async def response_done(s, *outputs):
    await s.provider_event({'type': 'response.done', 'response': {'status': 'completed', 'output': list(outputs)}})
    await configured(s)


async def play(s, key, text, sequence):
    await s.provider_event({'type': 'response.output_item.added', 'item': {'type': 'message', 'id': key}})
    await response_done(s)
    s.backend.command.return_value = {'eventId': f'played-{key}', 'sequence': sequence}
    s.items[key].update(bytes=4800, done=True, text=text, sent_at=0)
    await s.settle(key, {'complete': True, 'playedMs': 100})
    await configured(s)


def commands(s, action):
    return [call.args[3] for call in s.backend.command.await_args_list if call.args[2] == action]


@pytest.mark.asyncio
@pytest.mark.parametrize('confirmed', [False, True])
async def test_opening_waits_and_only_unconfirmed_address_gets_separate_question(confirmed):
    address = {'value': 'Алекс', 'source': 'telegram_explicit'} if confirmed else None
    s = sp24_session(context={'preferredAddress': address, 'facts': [{'fact_key': 'name', 'value': 'Display name'}]})
    await s.configure()
    config = updates(s)[0]
    headers = [line for line in config['instructions'].splitlines() if line.startswith('## ')]
    assert headers == ['## Core Persona', '## Trial voice guide', '## Current state', '## Tools and policies',
                       '## Student context (data)']
    assert PERSONALITY in config['instructions']
    assert '«рада»' in config['instructions']
    assert 'Display name' not in config['instructions']
    assert 'Listen most of the time' not in config['instructions']
    assert config['tools'] == []
    assert last_response(s)['tool_choice'] == 'none'
    await play(s, 'hello', 'Привет, как настроение?', 1)
    assert len(generations(s)) == 1
    await learner_turn(s, 'Хорошо, спасибо', 2, ack=False)
    assert len(generations(s)) == 1  # Do not generate with the previous state's instructions.
    target = trial_sp24.Stage.EXPLAIN_FORMAT if confirmed else trial_sp24.Stage.GET_NAME
    assert trial_sp24.STAGE_PROMPTS[target] in updates(s)[-1]['instructions']
    assert 'set_trial_phase' not in {tool['name'] for tool in updates(s)[-1]['tools']}
    await configured(s)
    assert len(generations(s)) == 2


@pytest.mark.asyncio
async def test_yes_by_name_stays_in_get_name_even_with_an_invalid_save_or_phase_request():
    s = sp24_session(clock(ms=100_000), history=[{'role': 'user', 'content': 'Привет'}])
    await s.configure()
    await play(s, 'address', 'Как я могу к тебе обращаться?', 3)
    by_name = 'Да, по имени.'
    await learner_turn(s, by_name, 4)
    assert len(updates(s)) == 1  # Same state does not incur another provider configuration round trip.
    await response_done(s,
        function_call('set_preferred_address', {'sourceId': 'source-4', 'value': 'по имени', 'evidence': by_name}),
        function_call('decline_preferred_address', {'sourceId': 'source-4', 'evidence': by_name}),
        function_call('set_trial_phase', {'phase': 'conversation'}))
    assert s.sp24_stage() == trial_sp24.Stage.GET_NAME
    assert '«да, по имени»' in updates(s)[-1]['instructions']
    for action in ('student.preferred_address', 'trial.dialogue', 'trial.phase'):
        assert commands(s, action) == []


@pytest.mark.asyncio
async def test_address_save_uses_current_learner_source_then_applies_in_same_call():
    s = sp24_session(history=[{'role': 'user', 'content': 'Привет'}])
    await s.configure()
    await response_done(s)
    await learner_turn(s, 'Зови меня Алекс', 4)
    saved = {'value': 'Алекс', 'source': 'voice_explicit', 'sourceEventId': 'source-4'}
    s.backend.command.return_value = saved
    args = {'sourceId': 'source-4', 'value': 'Алекс', 'evidence': 'Зови меня Алекс'}
    await response_done(s, function_call('set_preferred_address', args))
    assert commands(s, 'student.preferred_address') == [dict(lessonId='lesson', callSessionId='episode', **args)]
    assert s.sp24_stage() == trial_sp24.Stage.EXPLAIN_FORMAT
    assert '"preferredAddress": {"value": "Алекс"' in updates(s)[-1]['instructions']


@pytest.mark.asyncio
async def test_refusal_allows_format_but_english_clock_starts_on_audio():
    s = sp24_session(clock(ms=110_000), history=[{'role': 'user', 'content': 'Привет'}])
    await s.configure()
    await response_done(s)
    refusal = 'Не хочу называть имя, давай на английском'
    await learner_turn(s, refusal, 4)
    await response_done(s, function_call('decline_preferred_address', {'sourceId': 'source-4', 'evidence': refusal}))
    assert commands(s, 'trial.dialogue')[0]['decision'] == 'name_refused'
    assert s.sp24_stage() == trial_sp24.Stage.EXPLAIN_FORMAT
    await play(s, 'format', 'Познакомимся и поговорим по-английски.', 5)
    await s.advance_trial_clock()
    await s.maybe_respond()
    await configured(s)
    assert s.sp24_stage() == trial_sp24.Stage.CONVERSATION
    assert trial_sp24.START_ENGLISH in updates(s)[-1]['instructions']
    assert s.trial_state['phase'] == 'warmup'
    assert commands(s, 'trial.phase') == []

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


@pytest.mark.asyncio
async def test_name_timeout_waits_until_learner_is_done_and_does_not_skip_format():
    s = sp24_session(clock(ms=120_000), history=[{'role': 'user', 'content': 'Привет'}])
    s.speaking = True
    await s.configure()
    assert s.sp24_stage() == trial_sp24.Stage.GET_NAME
    assert not generations(s)
    s.speaking = False
    await s.advance_trial_clock()
    await s.maybe_respond()
    await configured(s)
    assert s.sp24_stage() == trial_sp24.Stage.EXPLAIN_FORMAT
    assert commands(s, 'trial.phase') == []


@pytest.mark.asyncio
async def test_reconnect_waits_after_a_played_name_question_and_resumes_with_the_learner_reply():
    s = sp24_session(clock(ms=30_000), dialogue={'greeting': 1, 'get_name': 3}, history=[
        {'role': 'user', 'content': 'Привет'}, {'role': 'assistant', 'content': 'Как обращаться?'}],
        context={'voiceSources': [{'id': 'source-2', 'sequence': 2, 'body': 'Привет'}]})
    await s.configure()
    assert not generations(s)
    await learner_turn(s, 'Да, по имени', 4)
    assert s.sp24_stage() == trial_sp24.Stage.GET_NAME
    assert len(generations(s)) == 1


@pytest.mark.asyncio
async def test_interruption_does_not_mark_format_as_played():
    s = sp24_session(context={'preferredAddress': {'value': 'Алекс'}},
                     history=[{'role': 'user', 'content': 'Привет'}])
    await s.configure()
    await s.provider_event({'type': 'response.output_item.added', 'item': {'type': 'message', 'id': 'format'}})
    s.items['format'].update(bytes=4800, sent_at=0)
    await s.interrupt()
    s.backend.command.return_value = {'sequence': 3}
    await s.settle('format', {'complete': False, 'playedMs': 50})
    assert s.dialogue == {'interrupted_explain_format': 3}
    assert commands(s, 'voice.append')[-1]['metadata']['trialDialogueInterrupted'] == 'explain_format'
    s.connected_trial_ms = lambda: 120_000
    await s.advance_trial_clock()
    assert s.sp24_stage() == trial_sp24.Stage.EXPLAIN_FORMAT
    assert not commands(s, 'trial.phase')


@pytest.mark.asyncio
async def test_reconnect_resumes_clock_and_only_server_opens_russian_wrapup():
    state = clock('conversation', 1_010_500, 111_404)
    s = sp24_session(state, context={'preferredAddress': {'value': 'Алекс', 'source': 'voice_explicit'}},
                     history=[{'role': 'assistant', 'content': 'How is your project going?'}])
    await s.configure()
    assert s.address_confirmed
    prompt = updates(s)[0]['instructions']
    assert 'Listen most of the time' in prompt
    assert 'Treat an A2 speaker as an adult' in prompt
    assert 'Never conduct deliberate grammar checks' in prompt
    assert 'free personal Roadmap' not in prompt
    assert not generations(s)
    await s.advance_trial_clock()
    assert not commands(s, 'trial.phase')
    s.connected_trial_ms = lambda: 1_011_404
    s.backend.command.return_value = clock('wrapup', 1_011_404, 111_404)
    await s.advance_trial_clock()
    await s.maybe_respond()
    await configured(s)
    assert commands(s, 'trial.phase')[-1]['phase'] == 'wrapup'
    assert s.sp24_stage() == trial_sp24.Stage.ROADMAP_INFO
    assert trial_sp24.STAGE_PROMPTS[trial_sp24.Stage.ROADMAP_INFO] in updates(s)[-1]['instructions']


@pytest.mark.asyncio
@pytest.mark.parametrize('reaction,body', [('yes', 'Да, давай'), ('no', 'Нет, спасибо'), ('undecided', 'А сколько это стоит?')])
async def test_roadmap_and_offer_are_separate_and_only_post_offer_reply_can_close(reaction, body):
    s = sp24_session(clock('wrapup', 1_020_000, 120_000))
    await s.configure()
    await play(s, 'roadmap', 'Пришлю бесплатный план в Telegram.', 9)
    assert s.sp24_stage() == trial_sp24.Stage.REGULAR_OFFER
    assert 'record_trial_reaction' not in s.sp24_tools
    await play(s, 'offer', 'Хочешь заниматься регулярно?', 10)
    assert commands(s, 'voice.append')[-1]['metadata']['trialClosingOffer'] is True
    s.want_response = True
    await s.maybe_respond()
    assert not s.want_response
    await learner_turn(s, body, 11)
    assert 'record_trial_reaction' in s.sp24_tools
    s.backend.command.return_value = {'regularReaction': reaction}
    await response_done(s, function_call('record_trial_reaction', {'sourceId': 'source-11', 'reaction': reaction,
                                                                 'evidence': body}))
    assert commands(s, 'trial.reaction')[-1]['reaction'] == reaction
    assert s.sp24_stage() == trial_sp24.Stage.GOODBYE
    assert f'"regularReaction": "{reaction}"' in updates(s)[-1]['instructions']
    await play(s, 'bye', 'До встречи!', 12)
    assert commands(s, 'voice.end')[-1]['reason'] == 'natural_goodbye'
    assert s.ended


@pytest.mark.asyncio
async def test_reconnect_after_a_played_offer_does_not_repeat_it():
    s = sp24_session(clock('wrapup', 1_050_000, 120_000, offer=10),
                     dialogue={'roadmap_info': 9, 'regular_offer': 10})
    await s.configure()
    assert not generations(s)
    await learner_turn(s, 'Да', 11)
    assert trial_sp24.RECORD_REACTION in updates(s)[-1]['instructions']
    assert len(generations(s)) == 1


@pytest.mark.asyncio
async def test_late_offer_still_gives_the_learner_time_to_answer():
    s = sp24_session(clock('wrapup', 1_250_000, 120_000), dialogue={'roadmap_info': 9})
    await s.configure()
    await play(s, 'offer', 'Хочешь заниматься регулярно?', 10)
    await s.advance_trial_clock()
    assert s.sp24_stage() == trial_sp24.Stage.REGULAR_OFFER
    assert len(generations(s)) == 1
    s.offer_wait_until = 0
    await s.advance_trial_clock()
    await s.maybe_respond()
    await configured(s)
    assert s.sp24_stage() == trial_sp24.Stage.GOODBYE
    assert trial_sp24.CLOSING_TIMEOUT in updates(s)[-1]['instructions']
    assert commands(s, 'trial.reaction') == []


@pytest.mark.asyncio
async def test_early_exit_is_saved_before_goodbye_and_avoids_offer():
    s = sp24_session(clock('conversation', 150_000, 120_000))
    await s.configure()
    stop = 'Мне нужно идти, давай закончим'
    await learner_turn(s, stop, 3)
    await response_done(s, function_call('finish_after_reply', {'reason': 'learner_requested',
                                                                 'sourceId': 'source-3', 'evidence': stop}))
    assert commands(s, 'trial.dialogue')[-1]['decision'] == 'early_exit'
    assert trial_sp24.EARLY_GOODBYE in updates(s)[-1]['instructions']
    await play(s, 'bye', 'Спасибо, до встречи!', 4)
    assert commands(s, 'voice.end')[-1]['reason'] == 'learner_requested'


@pytest.mark.asyncio
async def test_reconnect_after_early_goodbye_can_complete_without_replaying():
    s = sp24_session(clock('conversation', 150_000, 120_000), dialogue={'early_exit': 3, 'goodbye': 4})
    await s.configure()
    assert not generations(s)
    assert commands(s, 'voice.end')[-1]['reason'] == 'learner_requested'


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
