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
    fact = dict(memory='learning', key='difficulty', value='Vocabulary', kind='user_report', evidence='не хватает словб')
    result = await tutor.execute('scope', 'request', {'id': 'current', 'body': 'отчасти не хватает словб'},
        'update_learner_context', dict(facts=[fact, {**fact, 'key': 'goal', 'evidence': 'для бизнеса'}],
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
        {'status': 'claimed', 'input': source}, {'conversationId': 'conversation', 'reply': 'Запись пока не сохранилась.', 'degraded': False}]))
    llm = SimpleNamespace(complete_tools=AsyncMock(side_effect=[
        {'role': 'assistant', 'tool_calls': [{'id': str(i), 'function': {'name': 'create_trial_booking', 'arguments': '{}'}}]}
        for i in range(2)] + [{'content': '{"reply":"Запись пока не сохранилась."}'}]))
    tutor = FoundationTutor(backend, TrialTutorModel(llm), ContextBuilder(backend))
    tutor.execute = AsyncMock(return_value={'ok': False, 'error': 'invalid_evidence'})
    result = await tutor.respond(TutorRequest(sessionId='session', requestId='request', text=body, receivedAt=1794570480), 'scope')
    assert tutor.execute.await_count == 2
    assert llm.complete_tools.await_count == 3
    assert llm.complete_tools.call_args.args[1] == []
    assert result['booking'] is None
    assert result['reply'] == 'Запись пока не сохранилась.'


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
async def test_after_minutes_reaches_real_booking_adapter(monkeypatch):
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
    body = 'давай сегодня через 30 минут, норм будет?'
    result = await FoundationTutor(backend, None, None).execute('scope', 'request',
        {'id': 'current', 'body': body, 'occurred_at': '2026-11-13T11:48:00+00:00'},
        'create_trial_booking', dict(intent='explicit_request', evidence=body,
        schedule=dict(mode='after_minutes', delayMinutes=30, timezone=None)))
    assert result['ok']
    assert backend.command.call_args.args[2] == 'lesson.create'
    assert backend.command.call_args.args[3]['startsAt'] == '2026-11-13T12:18:00+00:00'
    assert result['booking']['id'] == 'lesson'
