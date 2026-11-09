"""Strict tool arguments and code-owned booking invariants. User text never supplies ownership."""
import hashlib
import json
import re
import time
from copy import deepcopy
from datetime import datetime, timedelta, timezone
from typing import Literal
from uuid import uuid4
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from pydantic import BaseModel, ConfigDict, Field, ValidationError
from app.tutor_store import active_booking


class Strict(BaseModel):
    model_config = ConfigDict(extra='forbid', strict=True)


class ReadBooking(Strict):
    pass


class Schedule(Strict):
    mode: Literal['now', 'local', 'shift_minutes', 'same_time_on_date']
    localDateTime: str | None
    timezone: str | None
    offset: str | None
    shiftMinutes: int | None
    date: str | None


class CreateBooking(Strict):
    intent: Literal['explicit_request'] = Field(description='A clear present instruction, including wanting a lesson now; no separate confirmation is required. Questions about availability, tentative suggestions and hypothetical wishes are not authorization.')
    evidence: str = Field(min_length=1, max_length=2000)
    schedule: Schedule


class ChangeBooking(CreateBooking):
    bookingId: str
    expectedVersion: int = Field(ge=1)


class CancelBooking(Strict):
    intent: Literal['explicit_request']
    evidence: str = Field(min_length=1, max_length=2000)
    bookingId: str
    expectedVersion: int = Field(ge=1)


class Fact(Strict):
    memory: Literal['personal', 'learning']
    key: str = Field(pattern=r'^[a-z][a-z_]{0,49}$')
    value: str | None = Field(max_length=1000)
    evidence: str = Field(min_length=1, max_length=2000)
    kind: Literal['user_report', 'self_assessment']


class UpdateContext(Strict):
    facts: list[Fact] = Field(max_length=20)
    trialPreference: Literal['unknown', 'interested', 'declined'] | None
    preferenceEvidence: str | None
    summary: str | None = Field(max_length=3000)


MODELS = {'get_trial_booking': ReadBooking, 'create_trial_booking': CreateBooking,
          'reschedule_trial_booking': ChangeBooking, 'cancel_trial_booking': CancelBooking,
          'update_learner_context': UpdateContext}
DESCRIPTIONS = {
    'get_trial_booking': 'Read the actual active trial, including past placeholders.',
    'create_trial_booking': 'Create a free 15 minute trial on a clear present request, including wanting a lesson right now. Execute in the same turn without a second confirmation; disclose unavailable lesson launch alongside the result, not as a consent gate. Never act on an availability question, hypothetical wish or tentative suggestion. Resolve relative dates using original message time and learner timezone. Ask only when required timing or current city/zone is ambiguous. mode=now needs no timezone or profile fields.',
    'reschedule_trial_booking': 'Reschedule the SAME booking on an explicit request, with expected version. shift_minutes uses its existing instant; same_time_on_date preserves its local clock. A timezone display change alone is not a reschedule.',
    'cancel_trial_booking': 'Cancel the current booking on explicit cancellation. Cannot attend is NOT cancellation authorization.',
    'update_learner_context': 'Save or correct user-stated facts with verbatim evidence from this message. Null deletes a fact. Self-reported level is self_assessment, never measured. Preserve unrelated facts. Save concise conversation summary, and trial preference only with evidence. Declined means stop unsolicited trial promotion, not stop chatting.',
}
TOOLS = [{'type': 'function', 'function': {'name': name, 'description': DESCRIPTIONS[name],
         'strict': True, 'parameters': model.model_json_schema()}} for name, model in MODELS.items()]


def zone(value):
    if not value:
        raise ValueError('timezone_required: ask current city or timezone')
    match = re.fullmatch(r'UTC([+-])(\d{2}):(\d{2})', value)
    if match:
        h, m = int(match[2]), int(match[3])
        if h > 14 or m > 59 or (h == 14 and m):
            raise ValueError('invalid_timezone')
        return timezone(timedelta(minutes=(h * 60 + m) * (1 if match[1] == '+' else -1)))
    try:
        return ZoneInfo(value)
    except (ZoneInfoNotFoundError, ValueError):
        raise ValueError('invalid_timezone') from None


def utc_text(dt):
    return dt.astimezone(timezone.utc).isoformat()


def resolve_time(schedule, received, current=None, now=None):
    now = now or datetime.now(timezone.utc)
    allowed = {
        'now': set(), 'local': {'localDateTime', 'timezone', 'offset'},
        'shift_minutes': {'shiftMinutes'}, 'same_time_on_date': {'date', 'timezone', 'offset'},
    }[schedule.mode]
    if any(v is not None and k not in allowed for k, v in schedule.model_dump().items() if k != 'mode'):
        raise ValueError('conflicting_schedule_fields')
    if schedule.mode == 'now':
        return utc_text(now), 'UTC', 'now'
    if schedule.mode == 'shift_minutes':
        if current is None or schedule.shiftMinutes is None:
            raise ValueError('current_booking_and_shift_required')
        instant = datetime.fromisoformat(current['startsAtUtc']) + timedelta(minutes=schedule.shiftMinutes)
        tz_name = current['timezone']
    else:
        tz_name = schedule.timezone or (current or {}).get('timezone')
        tz = zone(tz_name)
        if schedule.mode == 'same_time_on_date':
            if current is None or schedule.date is None:
                raise ValueError('current_booking_and_date_required')
            clock = datetime.fromisoformat(current['startsAtUtc']).astimezone(zone(current['timezone'])).time().replace(tzinfo=None)
            local = datetime.combine(datetime.strptime(schedule.date, '%Y-%m-%d').date(), clock)
        else:
            if not schedule.localDateTime:
                raise ValueError('precise_local_datetime_required')
            local = datetime.fromisoformat(schedule.localDateTime)
            if local.tzinfo is not None:
                raise ValueError('local_datetime_must_not_include_offset')
        candidates = {utc_text(local.replace(tzinfo=tz, fold=fold)) for fold in (0, 1)
                      if local.replace(tzinfo=tz, fold=fold).astimezone(timezone.utc).astimezone(tz).replace(tzinfo=None) == local}
        if not candidates:
            raise ValueError('nonexistent_dst_time: ask another local time')
        if len(candidates) > 1:
            candidates = {v for v in candidates if datetime.fromisoformat(v).astimezone(tz).strftime('%z') == (schedule.offset or '').replace(':', '')}
            if len(candidates) != 1:
                raise ValueError('ambiguous_dst_time: ask UTC offset')
        instant = datetime.fromisoformat(next(iter(candidates)))
    if instant <= now:
        raise ValueError('time_in_past')
    return utc_text(instant), tz_name, 'scheduled'


def booking_label(booking):
    if booking['status'] == 'cancelled':
        return 'Пробная встреча отменена.'
    when = 'сейчас' if booking.get('timing') == 'now' else datetime.fromisoformat(booking['startsAtUtc']).astimezone(zone(booking['timezone'])).strftime('%d.%m.%Y в %H:%M') + ' (' + booking['timezone'] + ')'
    return f'Пробная встреча: {when}, около 15 минут. Запуск урока пока недоступен.'


async def execute(store, state, name, arguments, request_id, text, received):
    fingerprint = hashlib.sha256(json.dumps([request_id, name, arguments], sort_keys=True).encode()).hexdigest()
    if fingerprint in state['operations']:
        return deepcopy(state['operations'][fingerprint]['result'])
    try:
        model = MODELS.get(name)
        if model is None:
            raise ValueError('unknown_tool')
        args = model.model_validate(arguments)
        booking = active_booking(state)
        before = deepcopy(booking)
        if hasattr(args, 'evidence') and args.evidence not in text:
            raise ValueError('evidence_not_in_current_message')
        if name == 'get_trial_booking':
            return {'ok': True, 'booking': deepcopy(booking)}
        if name == 'update_learner_context':
            for fact in args.facts:
                if fact.evidence not in text:
                    raise ValueError('fact_evidence_not_in_current_message')
            if args.trialPreference is not None and (not args.preferenceEvidence or args.preferenceEvidence not in text):
                raise ValueError('preference_evidence_required')
            for fact in args.facts:
                previous = state[fact.memory].get(fact.key)
                entry = dict(value=fact.value, kind=fact.kind, source=dict(requestId=request_id, text=fact.evidence, at=received))
                state['factHistory'].append(dict(memory=fact.memory, key=fact.key, previous=previous, new=entry))
                if fact.value is None:
                    state[fact.memory].pop(fact.key, None)
                else:
                    state[fact.memory][fact.key] = entry
            if args.trialPreference is not None:
                state['trialPreference'] = args.trialPreference
            if args.summary is not None:
                state['summary'], state['summaryAt'] = args.summary, received
            result = dict(ok=True, personal=state['personal'], learning=state['learning'])
        else:
            if name == 'create_trial_booking':
                if booking:
                    raise ValueError('active_booking_exists: use reschedule')
                starts, tz, timing = resolve_time(args.schedule, received)
                booking = dict(id=uuid4().hex, learnerId=state['learnerId'], kind='trial', status='scheduled',
                               startsAtUtc=starts, timezone=tz, timing=timing, durationMinutes=15, version=1,
                               createdAt=time.time(), updatedAt=time.time(),
                               previousBookingId=state['bookings'][-1]['id'] if state['bookings'] else None)
                state['bookings'].append(booking)
                state['introduction'] = 'acquainted'
                state['trialPreference'] = 'interested'
            else:
                if not booking or booking['learnerId'] != state['learnerId'] or booking['id'] != args.bookingId:
                    raise ValueError('booking_not_owned_or_not_active')
                if booking['version'] != args.expectedVersion:
                    raise ValueError('stale_booking_version: read current booking')
                if name == 'cancel_trial_booking':
                    booking['status'] = 'cancelled'
                else:
                    starts, tz, timing = resolve_time(args.schedule, received, booking)
                    booking.update(startsAtUtc=starts, timezone=tz, timing=timing)
                booking['version'] += 1
                booking['updatedAt'] = time.time()
            result = dict(ok=True, booking=deepcopy(booking), confirmation=booking_label(booking))
            event = 'trial_rebooked' if name == 'create_trial_booking' and booking['previousBookingId'] else name
            state['events'].append(dict(type=event, at=time.time(), requestId=request_id, bookingId=booking['id']))
        state['operations'][fingerprint] = dict(requestId=request_id, tool=name, before=before,
                                                 result=deepcopy(result), at=time.time())
        await store.save(state['learnerId'], state)
        return result
    except (ValueError, ValidationError, OverflowError) as exc:
        result = dict(ok=False, error=str(exc)[:500])
        state['events'].append(dict(type='action_error', tool=name, requestId=request_id, at=time.time()))
        await store.save(state['learnerId'], state)
        return result
