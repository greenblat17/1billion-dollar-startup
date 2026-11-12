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
    args = dict(evidence='добавь завтра', startsAt='2026-11-13T12:00:00Z', timezone='Europe/Moscow', durationMinutes=30)
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
    args = dict(evidence='давай', startsAt='2026-11-13T12:00:00Z', timezone='UTC', durationMinutes=30)
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
