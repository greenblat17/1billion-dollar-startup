"""Text tutor adapter for scheduling commands and booking descriptions."""

from datetime import datetime, timezone
from uuid import uuid4

from app.foundation_client import BackendFailure, tool_operation_id
from app.trial_sp24 import SLOT_MINUTES
from app.trial_tools import CancelPreliminarySchedule, PreliminarySchedule, SCHEDULE_MODELS
from app.tutor_diagnostics import record_tool
from app.tutor_tools import MODELS as LEGACY_MODELS, resolve_time, zone


TRIAL_BOOKING_MODELS = {name: model for name, model in LEGACY_MODELS.items() if name != 'update_learner_context'}
LESSON_MUTATIONS = frozenset({'lesson.create', 'lesson.reschedule', 'lesson.cancel'})
TRIAL_BOOKING_MUTATIONS = TRIAL_BOOKING_MODELS.keys() - {'get_trial_booking'}
REGULAR_SCHEDULE_MUTATIONS = SCHEDULE_MODELS.keys()
SCHEDULING_TOOLS = TRIAL_BOOKING_MODELS.keys() | REGULAR_SCHEDULE_MUTATIONS | {
    'get_schedules', 'get_lessons', 'save_preliminary_schedule',
    'get_preliminary_schedules', 'cancel_preliminary_schedule',
}


MONTHS = ('января', 'февраля', 'марта', 'апреля', 'мая', 'июня',
          'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря')


def booking_label(booking):
    if booking['status'] == 'cancelled':
        return 'Пробное занятие отменено.\nЕсли добавлял его в календарь, удали событие там.'
    local = datetime.fromisoformat(booking['startsAtUtc']).astimezone(zone(booking['timezone']))
    weekdays = ('понедельник', 'вторник', 'среда', 'четверг', 'пятница', 'суббота', 'воскресенье')
    return (f"Пробное занятие · бесплатно\n"
            f"{local.day} {MONTHS[local.month - 1]} {local.year}, {weekdays[local.weekday()]}\n"
            f"{local:%H:%M} · {booking['timezone']} · {booking['durationMinutes']} минут\n"
            + ('Звонок в Speaky' if booking.get('launchMode') == 'executable' else 'Подключение пока недоступно'))


def scheduled_reply(booking, rescheduled=False):
    local = datetime.fromisoformat(booking['startsAtUtc']).astimezone(zone(booking['timezone']))
    day = f'{local.day} {MONTHS[local.month - 1]}'
    display_zone = 'МСК' if booking['timezone'] == 'Europe/Moscow' else booking['timezone']
    confirmation = 'Перенёс пробный' if rescheduled else 'Записал тебя на бесплатный пробный'
    calendar = ' Если добавлял урок в календарь, обнови время.' if rescheduled else ''
    return (f'{confirmation} на {day} в {local:%H:%M} ({display_zone}). '
            f'Урок на {booking["durationMinutes"]} минут. Подключайся по кнопке в этом сообщении.{calendar}')


def booking_projection(lesson: dict | None) -> dict | None:
    if not lesson:
        return None
    return dict(id=lesson['id'], learnerId=lesson['student_id'], status=lesson['status'],
                startsAtUtc=lesson['starts_at'], timezone=lesson['timezone'], version=lesson['version'],
                durationMinutes=lesson['duration_minutes'], timing=lesson.get('timing', 'scheduled'), launchMode=lesson.get('launch_mode', 'placeholder'))


async def execute_schedule(backend, scope, request_id, source, name, arguments):
    stage = 'validate_arguments'
    try:
        if name in ('get_schedules', 'get_lessons'):
            if arguments:
                raise ValueError('unexpected_arguments')
            return await backend.command(scope, uuid4().hex,
                'trial.schedules' if name == 'get_schedules' else 'lesson.list', {})
        if name in SCHEDULE_MODELS:
            model, action = SCHEDULE_MODELS[name]
            args = model.model_validate(arguments)
            if args.evidence not in source['body']:
                raise ValueError('invalid_evidence')
            command = {'sourceId': source['id'], **args.model_dump()}
            if name == 'create_extra_lesson':
                command.update(kind='regular', launchMode='executable')
            elif name == 'reschedule_lesson':
                command['launchMode'] = 'executable'
            identity = tool_operation_id(request_id, name, arguments)
            result = await backend.command(scope, identity, action, command)
            return {'ok': True, 'result': result}
        if name in ('save_preliminary_schedule', 'get_preliminary_schedules', 'cancel_preliminary_schedule'):
            if name == 'get_preliminary_schedules':
                return await backend.command(scope, uuid4().hex, 'trial.schedules', {})
            schedule = (CancelPreliminarySchedule if name == 'cancel_preliminary_schedule' else PreliminarySchedule).model_validate(arguments)
            identity = tool_operation_id(request_id, name, arguments)
            stage = 'schedule_command'
            return await backend.command(scope, identity, 'trial.schedule_cancel' if name == 'cancel_preliminary_schedule' else 'trial.schedule', {'sourceId': source['id'], **schedule.model_dump()})
        args = TRIAL_BOOKING_MODELS[name].model_validate(arguments)
        identity = tool_operation_id(request_id, name, arguments)
        stage = 'receipt'
        previous = await backend.receipt(scope, identity)
        if previous is not None:
            booking = booking_projection(previous)
            return {'ok': True, 'booking': booking, 'confirmation': booking_label(booking)}
        stage = 'snapshot'
        snapshot = await backend.snapshot(scope, 'text')
        stage = 'booking_projection'
        current = booking_projection(snapshot.get('booking'))
        if name == 'get_trial_booking':
            return {'ok': True, 'booking': current}
        stage = 'validate_evidence'
        if getattr(args, 'evidence', '') not in source['body']:
            raise ValueError('invalid_evidence')
        command = {'sourceId': source['id'], 'evidence': args.evidence}
        stage = 'validate_booking'
        if name != 'create_trial_booking':
            if not current or current['id'] != args.bookingId:
                raise ValueError('booking_not_owned_or_not_active')
            command.update(lessonId=args.bookingId, expectedVersion=args.expectedVersion)
        if name == 'cancel_trial_booking':
            action = 'lesson.cancel'
        else:
            stage = 'resolve_time'
            received = datetime.fromisoformat(source['occurred_at']).timestamp()
            starts, timezone_name, timing = resolve_time(args.schedule, received, current, datetime.now(timezone.utc))
            command.update(startsAt=starts, timezone=timezone_name, timing=timing)
            if name == 'create_trial_booking':
                action = 'lesson.create'
                command.update(kind='trial', launchMode='executable', durationMinutes=SLOT_MINUTES)
            else:
                action = 'lesson.reschedule'
                command['launchMode'] = 'executable'
        stage = 'booking_command'
        lesson = await backend.command(scope, identity, action, command)
        stage = 'result_projection'
        booking = booking_projection(lesson)
        return {'ok': True, 'booking': booking, 'confirmation': booking_label(booking)}
    except BackendFailure as error:
        record_tool(request_id, name, stage, 'rejected', error=error)
        if error.status >= 500:
            raise
        if error.code.startswith('schedule_time_needs_clarification:'):
            return {'ok': False, 'error': error.code, 'guidance':
                'No change committed. Explain that this local time is missing or occurs twice because clocks change. Ask for another local time for that date; do not guess UTC or claim a server outage.'}
        return {'ok': False, 'error': error.code}
    except (ValueError, TypeError, KeyError) as error:
        record_tool(request_id, name, stage, 'rejected', error=error)
        if stage == 'validate_evidence' and str(error) == 'invalid_evidence':
            return {'ok': False, 'error': 'invalid_evidence', 'guidance': (
                'No action was performed: evidence is not a verbatim quote from the current '
                'user message. This is invalid tool input, not a server outage. Reconsider '
                'whether the current message requests this action. If it does, retry with '
                'an exact supporting quote; otherwise continue the conversation without '
                'booking. Do not ask the learner to repeat an already clear request.')}
        if stage == 'resolve_time' and str(error) in ('conflicting_schedule_fields', 'current_booking_and_shift_required'):
            return {'ok': False, 'error': str(error), 'guidance':
                'Invalid tool arguments, not a timezone problem. For N minutes from this message use '
                'after_minutes with delayMinutes. shift_minutes requires an existing booking. '
                'Use only fields belonging to the chosen mode; no change was committed.'}
        return {'ok': False, 'error': str(error)[:300]}
