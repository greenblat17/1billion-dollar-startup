from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.foundation_client import BackendFailure
from app.foundation_tutor import FoundationTutor
from app.trial_tools import TrialTutorModel


def tutor(command=None):
    backend = SimpleNamespace(command=command or AsyncMock(return_value={'id': 'lesson'}))
    return FoundationTutor(backend, None, None), backend


@pytest.mark.asyncio
async def test_regular_creation_is_native_authorized_and_idempotent():
    agent, backend = tutor()
    args = dict(timing='scheduled', delayMinutes=None, evidence='добавь завтра', startsAt='2026-11-13T12:00:00Z', timezone='Europe/Moscow', durationMinutes=30)
    for _ in range(2):
        result = await agent.execute('scope', 'request', {'id': 'source', 'body': 'добавь завтра урок'}, 'create_extra_lesson', args)
        assert result == {'ok': True, 'result': {'id': 'lesson'}}
    calls = backend.command.call_args_list
    assert calls[0] == calls[1]
    assert calls[0].args[2] == 'lesson.create'
    assert calls[0].args[3] == dict(args, sourceId='source', kind='regular', launchMode='executable')


@pytest.mark.asyncio
@pytest.mark.parametrize('change', [{'durationMinutes': 14}, {'durationMinutes': 61}, {'evidence': 'old consent'}, {'launchMode': 'placeholder'}])
async def test_invalid_regular_mutations_do_not_reach_backend(change):
    agent, backend = tutor()
    args = dict(timing='scheduled', delayMinutes=None, evidence='давай', startsAt='2026-11-13T12:00:00Z', timezone='UTC', durationMinutes=30)
    args.update(change)
    result = await agent.execute('scope', 'request', {'id': 'source', 'body': 'давай'}, 'create_extra_lesson', args)
    assert not result['ok']
    backend.command.assert_not_called()


@pytest.mark.asyncio
async def test_dst_error_asks_for_clarification_without_claiming_success():
    agent, _ = tutor(AsyncMock(side_effect=BackendFailure('schedule_time_needs_clarification:2027-03-28:02:30:Europe/Berlin', 400)))
    args = dict(evidence='да', timezone='Europe/Berlin', startDate='2027-03-28', localTime='02:30', weekdays=[7],
                durationMinutes=30, scheduleId=None, expectedVersion=None)
    result = await agent.execute('scope', 'request', {'id': 'source', 'body': 'да'}, 'save_schedule', args)
    assert not result['ok']
    assert 'another local time' in result['guidance']


@pytest.mark.asyncio
async def test_model_receives_regular_tools_without_preliminary_placeholders():
    llm = SimpleNamespace(complete_tools=AsyncMock(return_value={}))
    await TrialTutorModel(llm).complete([])
    names = {t['function']['name'] for t in llm.complete_tools.call_args.args[1]}
    assert {'save_schedule', 'get_schedules', 'get_lessons', 'create_extra_lesson', 'reschedule_lesson', 'cancel_lesson', 'resolve_schedule_time'} <= names
    assert 'save_preliminary_schedule' not in names


@pytest.mark.asyncio
async def test_recovered_regular_receipt_confirms_saved_schedule_without_replaying_tools():
    from app.tutor import TutorRequest
    command = AsyncMock(side_effect=[
        {'status': 'running', 'input': {'id': 'source', 'body': 'да'}, 'committed': [
            {'action': 'schedule.agree', 'result': {'schedule': {'id': 'schedule'}}}]},
        {'conversationId': 'chat', 'reply': 'Изменения расписания сохранены.', 'degraded': True},
    ])
    model = SimpleNamespace(complete=AsyncMock())
    context = SimpleNamespace(instructions=lambda _: '', history=[])
    agent = FoundationTutor(SimpleNamespace(command=command), model, SimpleNamespace(build=AsyncMock(return_value=context)))
    await agent.respond(TutorRequest(sessionId='s', requestId='r', text='да', receivedAt=1794479220), 'scope')
    model.complete.assert_not_called()
    assert 'сохранены' in command.call_args.args[3]['reply']
    assert command.call_args.args[3]['bookingId'] is None


@pytest.mark.asyncio
@pytest.mark.parametrize('name', ['reschedule_lesson', 'create_extra_lesson'])
@pytest.mark.parametrize('timing,delay', [('now', None), ('after', 5)])
async def test_relative_intent_reaches_backend_without_model_timestamp(name, timing, delay):
    agent, backend = tutor()
    args = dict(evidence='сейчас', timing=timing, startsAt=None, delayMinutes=delay, timezone='Europe/Moscow')
    if name == 'reschedule_lesson':
        args.update(lessonId='monday', expectedVersion=3)
    else:
        args.update(durationMinutes=30)
    result = await agent.execute('scope', 'request', {'id': 'source', 'body': 'сейчас'}, name, args)
    assert result['ok']
    command = backend.command.call_args.args[3]
    assert command['timing'] == timing
    assert command['startsAt'] is None
    assert command['delayMinutes'] == delay


@pytest.mark.asyncio
@pytest.mark.parametrize('change', [
    {'startsAt': '2026-11-12T15:45:00Z'}, {'delayMinutes': 5},
    {'timing': 'after', 'delayMinutes': None}, {'timing': 'after', 'delayMinutes': 0},
    {'timing': 'scheduled', 'startsAt': None},
])
async def test_conflicting_time_arguments_never_mutate_a_lesson(change):
    agent, backend = tutor()
    args = dict(evidence='сейчас', lessonId='monday', expectedVersion=1, timing='now', startsAt=None,
                delayMinutes=None, timezone='Europe/Moscow')
    args.update(change)
    result = await agent.execute('scope', 'request', {'id': 'source', 'body': 'сейчас'}, 'reschedule_lesson', args)
    assert not result['ok']
    backend.command.assert_not_called()


@pytest.mark.asyncio
async def test_scripted_direct_move_finishes_in_original_turn_with_current_policy():
    import json
    from app.tutor import TutorRequest

    text = 'окей погнали щас урок проведем вместо понедельника'
    source = {'id': 'source', 'body': text, 'occurred_at': '2026-11-12T15:45:00Z'}
    lesson = {'id': 'monday', 'kind': 'regular', 'version': 1, 'timezone': 'Europe/Moscow'}
    command = AsyncMock(side_effect=[
        {'status': 'running', 'input': source}, {'lessons': [lesson]},
        dict(lesson, version=2, starts_at='2026-11-12T15:45:20Z', timing='now'),
        {'conversationId': 'chat', 'reply': 'Перенёс на сейчас.', 'degraded': False},
    ])
    def call(name, args):
        return {'role': 'assistant', 'content': None, 'tool_calls': [{'id': name, 'type': 'function',
            'function': {'name': name, 'arguments': json.dumps(args, ensure_ascii=False)}}]}
    model = SimpleNamespace(complete=AsyncMock(side_effect=[
        call('get_lessons', {}),
        call('reschedule_lesson', dict(evidence=text, lessonId='monday', expectedVersion=1,
            timing='now', startsAt=None, delayMinutes=None, timezone='Europe/Moscow')),
        {'content': '{"reply":"Перенёс на сейчас."}'},
    ]))
    context = SimpleNamespace(instructions=lambda _: '', history=[])
    agent = FoundationTutor(SimpleNamespace(command=command), model, SimpleNamespace(build=AsyncMock(return_value=context)))
    result = await agent.respond(TutorRequest(sessionId='s', requestId='r', text=text, receivedAt=1794498300), 'scope')
    assert result['reply'] == 'Перенёс на сейчас.'
    assert [c.args[2] for c in command.call_args_list] == ['run.claim', 'lesson.list', 'lesson.reschedule', 'run.complete']
    systems = '\n'.join(m['content'] for m in model.complete.call_args_list[0].args[0] if m['role'] == 'system')
    assert 'launch is a placeholder and unavailable' not in systems
    assert 'No available Mini App' not in systems
    assert 'free trial bookings only' not in systems
    assert 'in this turn: get_lessons' in systems
    assert 'Do not ask "Перенести?"' in systems
    assert 'backend chooses time' in systems
