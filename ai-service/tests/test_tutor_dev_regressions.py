import json
import re
from datetime import datetime, timezone
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from app.context_builder import ContextBuilder
from app.foundation_tutor import FoundationTutor
from app.learning_engine import PersonalCandidateExtractor
from app.trial_tools import TrialTutorModel, trial_booking_schema
from app.tutor import TutorRequest
from app.tutor_tools import Schedule, resolve_time


def snapshot(body):
    return {'version': 1, 'textMode': 'introduction', 'student': {'id': 'student', 'tutor_id': 'sasha',
        'tutor_version': 1, 'version': 1, 'data_epoch': 1}, 'events': [
        {'id': 'current', 'sequence': 1, 'role': 'user', 'body': body}]}


@pytest.mark.asyncio
@pytest.mark.parametrize('body,tool,kind,duration', [
    ('Саша, запиши меня на обычный 30-минутный урок английского прямо сейчас. '
     'Хочу попрактиковать рабочий апдейт о задержке релиза.', 'create_extra_lesson', 'regular', 30),
    ('Саша, запиши меня на пробный прямо сейчас.', 'create_trial_booking', 'trial', 20),
])
async def test_immediate_booking_uses_requested_lesson_type_during_introduction(body, tool, kind, duration):
    source = {'id': 'current', 'body': body, 'occurred_at': '2026-11-17T19:11:58+00:00'}
    lesson = dict(id='lesson', student_id='student', kind=kind, status='scheduled', starts_at=source['occurred_at'],
                  version=1, timing='now', timezone='UTC', duration_minutes=duration, launch_mode='executable')
    backend = SimpleNamespace(
        snapshot=AsyncMock(return_value=snapshot(body)),
        receipt=AsyncMock(return_value=None),
        command=AsyncMock(side_effect=[
            {'status': 'claimed', 'input': source},
            lesson,
            {'conversationId': 'chat', 'reply': 'Записала.', 'degraded': False},
        ]),
    )
    if kind == 'trial':
        arguments = {'evidence': body, 'intent': 'explicit_request', 'schedule': {'mode': 'now'}}
    else:
        arguments = {'evidence': body, 'timing': 'now', 'startsAt': None, 'delayMinutes': None,
                     'timezone': 'UTC', 'durationMinutes': 30}

    async def complete(messages, tools):
        if messages[-1]['role'] == 'tool':
            return {'content': '{"reply":"Записала."}'}
        assert messages[-1] == {'role': 'user', 'content': body}
        policies = '\n'.join(message['content'] for message in messages if message['role'] == 'system')
        assert 'Для Trial используй create_trial_booking' in policies
        assert 'регулярного урока create_extra_lesson' in policies
        assert 'For an unambiguous request to start immediately, call create_trial_booking' not in policies
        functions = {entry['function']['name']: entry['function'] for entry in tools}
        trial = functions['create_trial_booking']
        assert 'Use create_extra_lesson for an explicit one-off regular lesson.' in trial['description']
        assert 'An explicit request for a trial' in trial['parameters']['properties']['intent']['description']
        assert 'including an immediate request during introduction' in functions['create_extra_lesson']['description']
        return {'role': 'assistant', 'tool_calls': [{'id': 'booking', 'function': {
            'name': tool, 'arguments': json.dumps(arguments, ensure_ascii=False),
        }}]}

    tutor = FoundationTutor(backend, TrialTutorModel(SimpleNamespace(complete_tools=complete)), ContextBuilder(backend))
    await tutor.respond(TutorRequest(sessionId='session', requestId='request', text=body, receivedAt=1794942718), 'scope')

    assert [call.args[2] for call in backend.command.call_args_list] == ['run.claim', 'lesson.create', 'run.complete']
    payload = backend.command.call_args_list[1].args[3]
    assert (payload['kind'], payload['timing'], payload['durationMinutes']) == (kind, 'now', duration)
    assert payload['evidence'] == body
    assert payload['launchMode'] == 'executable'


@pytest.mark.parametrize('display_zone', [None, 'Europe/Moscow'])
def test_thirty_minutes_from_original_message_without_existing_booking(display_zone):
    received = datetime(2026, 11, 13, 11, 48, tzinfo=timezone.utc)
    schedule = Schedule(mode='after_minutes', delayMinutes=30, timezone=display_zone)
    assert resolve_time(schedule, received.timestamp(), now=received.replace(minute=50)) == (
        '2026-11-13T12:18:00+00:00', display_zone or 'UTC', 'scheduled')
    with pytest.raises(ValueError, match='time_in_past'):
        resolve_time(schedule, received.timestamp(), now=received.replace(hour=13))


def test_exclusive_modes_and_existing_shift_semantics():
    variants = trial_booking_schema('create_trial_booking')['$defs']['Schedule']['anyOf']
    assert {v['properties']['mode']['enum'][0] for v in variants} == {'now', 'after_minutes', 'local'}
    for variant in variants:
        assert variant['additionalProperties'] is False
        assert set(variant['required']) == set(variant['properties'])
    with pytest.raises(ValueError, match='conflicting_schedule_fields'):
        resolve_time(Schedule(mode='after_minutes', delayMinutes=30, localDateTime='2026-11-13T19:00:00'), 0)
    current = {'startsAtUtc': '2026-11-13T12:00:00+00:00', 'timezone': 'Europe/Moscow'}
    assert resolve_time(Schedule(mode='shift_minutes', shiftMinutes=30), 0, current,
        datetime(2026, 11, 13, 11, tzinfo=timezone.utc))[0] == '2026-11-13T12:30:00+00:00'


@pytest.mark.asyncio
async def test_memory_saves_supported_fact_without_inventing_evidence_or_preference():
    backend = SimpleNamespace(receipt=AsyncMock(return_value=None), snapshot=AsyncMock(return_value={}), command=AsyncMock())
    tutor = FoundationTutor(backend, None, None)
    fact = dict(memory='personal', key='difficulty', value='Vocabulary', kind='user_report', evidence='не хватает словб')
    result = await tutor.execute('scope', 'request', {'id': 'current', 'body': 'отчасти не хватает словб'},
        'update_learner_context', dict(facts=[fact, {**fact, 'key': 'occupation', 'evidence': 'для бизнеса'}],
        trialPreference='unknown', preferenceEvidence=None, summary=None))
    payload = backend.command.call_args.args[3]
    assert payload['facts'] == [fact]
    assert payload['trialPreference'] is None
    assert result['saved'] and len(result['skipped']) == 2
    backend.command.reset_mock()
    result = await tutor.execute('scope', 'other', {'id': 'current', 'body': 'московское время'},
        'update_learner_context', dict(facts=[], trialPreference='interested', preferenceEvidence='готов к уроку', summary=None))
    backend.command.assert_not_called()
    assert result['ok'] and not result['saved'] and result['skipped']


@pytest.mark.asyncio
async def test_two_rejections_finish_with_tools_disabled():
    body = 'московское время'
    source = {'id': 'current', 'body': body, 'occurred_at': '2026-11-13T11:48:00+00:00'}
    backend = SimpleNamespace(snapshot=AsyncMock(return_value=snapshot(body)), command=AsyncMock(side_effect=[
        {'status': 'claimed', 'input': source}, {'conversationId': 'conversation', 'reply': 'Я не смог подтвердить новую запись.', 'degraded': True}]))
    llm = SimpleNamespace(complete_tools=AsyncMock(side_effect=[
        {'role': 'assistant', 'tool_calls': [{'id': str(i), 'function': {'name': 'create_trial_booking', 'arguments': '{}'}}]}
        for i in range(2)] + [{'content': '{"reply":"Отлично, запускаю сейчас!"}'}]))
    dialogue_model = SimpleNamespace(complete=AsyncMock(), finalize=AsyncMock())
    tutor = FoundationTutor(backend, dialogue_model, ContextBuilder(backend),
                            introduction_model=TrialTutorModel(llm))
    tutor.execute = AsyncMock(return_value={'ok': False, 'error': 'invalid_evidence'})
    result = await tutor.respond(TutorRequest(sessionId='session', requestId='request', text=body, receivedAt=1794570480), 'scope')
    assert tutor.execute.await_count == 2
    assert llm.complete_tools.await_count == 3
    assert llm.complete_tools.call_args.args[1] == []
    dialogue_model.complete.assert_not_awaited()
    dialogue_model.finalize.assert_not_awaited()
    assert result['booking'] is None
    assert result['reply'] == 'Я не смог подтвердить новую запись.'
    assert backend.command.call_args.args[3]['degraded'] is True
    assert backend.command.call_args.args[3]['reply'] == result['reply']


@pytest.mark.asyncio
async def test_rejected_optional_updates_cannot_confirm_trial():
    body = 'Хочу пробный урок сейчас.'
    source = {'id': 'current', 'body': body, 'occurred_at': '2026-11-17T12:45:00+00:00'}
    saved = {'conversationId': 'conversation',
             'reply': 'Не удалось выполнить запрос. Можешь написать мне ещё раз?',
             'degraded': True}
    backend = SimpleNamespace(snapshot=AsyncMock(return_value=snapshot(body)), command=AsyncMock(side_effect=[
        {'status': 'claimed', 'input': source}, saved]))
    model = SimpleNamespace(complete=AsyncMock(side_effect=[
        {'role': 'assistant', 'tool_calls': [{'id': str(i), 'function': {'name': 'update_onboarding', 'arguments': '{}'}}]}
        for i in range(2)]), finalize=AsyncMock(return_value={'content':
        '{"reply":"Сейчас запускаю пробный урок!","trialOffered":true}'}))
    tutor = FoundationTutor(backend, model, ContextBuilder(backend))
    tutor.execute = AsyncMock(return_value={'ok': False, 'error': 'invalid_evidence'})
    result = await tutor.respond(
        TutorRequest(sessionId='session', requestId='request', text=body, receivedAt=1794919500), 'scope')
    assert result['booking'] is None
    assert result['reply'] == saved['reply']
    assert backend.command.call_args.args[3]['degraded'] is True


@pytest.mark.asyncio
async def test_extracted_personal_keys_are_stable_and_match_backend_contract():
    model = SimpleNamespace(update_person_once=AsyncMock(return_value={'facts': ['Люблю походы', 'Люблю музыку']}))
    context = SimpleNamespace(data={'sourceEvents': [{'id': 'source', 'body': 'Люблю походы и музыку'}]})
    extractor = PersonalCandidateExtractor(model)
    first = await extractor.extract(context, {'sourceId': 'source'})
    assert first == await extractor.extract(context, {'sourceId': 'source'})
    keys = [c['key'] for c in first['candidates']]
    assert len(set(keys)) == 2
    assert all(re.fullmatch('[a-z][a-z_]{0,49}', key) for key in keys)


@pytest.mark.asyncio
@pytest.mark.parametrize('delay,expected_start', [(5, '11:53'), (30, '12:18')])
async def test_after_minutes_reaches_real_booking_adapter(monkeypatch, delay, expected_start):
    import app.foundation_tutor as module
    class Clock(datetime):
        @classmethod
        def now(cls, tz=None):
            return datetime(2026, 11, 13, 11, 49, tzinfo=timezone.utc)
    monkeypatch.setattr(module, 'datetime', Clock)
    lesson = dict(id='lesson', student_id='student', status='scheduled', starts_at='2026-11-13T12:18:00+00:00',
                  timezone='UTC', version=1, duration_minutes=15, launch_mode='executable')
    backend = SimpleNamespace(receipt=AsyncMock(return_value=None), snapshot=AsyncMock(return_value={}),
                              command=AsyncMock(return_value=lesson))
    body = f'давай через {delay} минут?'
    result = await FoundationTutor(backend, None, None).execute('scope', 'request',
        {'id': 'current', 'body': body, 'occurred_at': '2026-11-13T11:48:00+00:00'},
        'create_trial_booking', dict(intent='explicit_request', evidence=body,
        schedule=dict(mode='after_minutes', delayMinutes=delay, timezone=None)))
    assert result['ok']
    assert backend.command.call_args.args[2] == 'lesson.create'
    assert backend.command.call_args.args[3]['startsAt'] == f'2026-11-13T{expected_start}:00+00:00'
    assert result['booking']['id'] == 'lesson'


@pytest.mark.asyncio
@pytest.mark.parametrize('action', ['create_trial_booking', 'reschedule_trial_booking'])
@pytest.mark.parametrize('timing', ['now', 'scheduled'])
async def test_trial_booking_finishes_without_extra_model_reply_and_replays(action, timing):
    import json
    from app.foundation_tutor import booking_projection, scheduled_reply
    body = 'Я готов, можем начать'
    lesson = dict(id='lesson', student_id='student', status='scheduled', starts_at='2026-11-13T12:18:00+00:00',
                  timezone='UTC', version=1, duration_minutes=15, launch_mode='executable', timing=timing)
    reply = '' if timing == 'now' else scheduled_reply(booking_projection(lesson), action == 'reschedule_trial_booking')
    saved = dict(conversationId='conversation', reply=reply, booking=lesson)
    backend = SimpleNamespace(snapshot=AsyncMock(return_value=snapshot(body)), command=AsyncMock(side_effect=[
        {'status': 'running', 'input': {'id': 'current', 'body': body}}, saved,
        {'status': 'ready', 'response': saved}]))
    model = SimpleNamespace(complete=AsyncMock(side_effect=[
        {'role': 'assistant', 'tool_calls': [{'id': 'call', 'function': {'name': action, 'arguments': '{}'}}]},
        {'content': json.dumps({'reply': 'До встречи!'})}]))
    tutor = FoundationTutor(backend, model, ContextBuilder(backend))
    tutor.execute = AsyncMock(return_value={'ok': True, 'booking': booking_projection(lesson)})
    request = TutorRequest(sessionId='session', requestId='request', text=body, receivedAt=1794570480)
    result = await tutor.respond(request, 'scope')
    assert result == await tutor.respond(request, 'scope')
    assert result['booking']['timing'] == timing
    assert model.complete.await_count == 1
    assert tutor.execute.await_count == 1
    assert backend.command.call_args_list[1].args[3]['reply'] == saved['reply']


@pytest.mark.asyncio
@pytest.mark.parametrize('tool_name', ['get_trial_booking', 'create_trial_booking'])
async def test_follow_up_uses_existing_trial_after_direct_read_or_rejected_mutation(tool_name):
    body = 'Куда подключаться?'
    lesson = dict(id='lesson', student_id='student', status='scheduled', starts_at='2026-11-18T07:43:58+00:00',
                  timezone='UTC', version=1, duration_minutes=30, launch_mode='executable', timing='scheduled')
    source = {'id': 'current', 'body': body}
    mock_snapshot = {**snapshot(body), 'booking': lesson}
    finish = AsyncMock(side_effect=lambda scope, key, action, payload: (
        {'status': 'running', 'input': source} if action == 'run.claim' else
        {'conversationId': 'conversation', **payload}))
    backend = SimpleNamespace(snapshot=AsyncMock(return_value=mock_snapshot), command=finish, receipt=AsyncMock(return_value=None))
    model = SimpleNamespace(complete=AsyncMock(side_effect=[
        {'role': 'assistant', 'tool_calls': [{'id': 'call', 'function': {
            'name': tool_name, 'arguments': '{}'}}]},
        {'content': '{"reply":"Подключайся в Speaky."}'},
    ]))
    result = await FoundationTutor(backend, model, ContextBuilder(backend)).respond(
        TutorRequest(sessionId='session', requestId='request', text=body, receivedAt=1794987579), 'scope')
    assert result['joinLessonId'] == 'lesson'
    assert result['booking'] is None
    assert result['reply'].startswith('Пробный уже записан' if tool_name == 'create_trial_booking' else 'Подключайся в Speaky')
    assert [call.args[2] for call in finish.call_args_list] == ['run.claim', 'run.complete']
