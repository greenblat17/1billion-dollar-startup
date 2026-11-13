"""Content-free diagnostics for native tutor tools; never log arguments or exception text."""
import hashlib
import json
import logging

from pydantic import ValidationError

from app.foundation_client import BackendFailure
from app.diagnostics import context_fields, event as diagnostic_event

TOOLS = frozenset({
    'get_trial_booking', 'create_trial_booking', 'reschedule_trial_booking', 'cancel_trial_booking',
    'update_learner_context', 'save_preliminary_schedule', 'get_preliminary_schedules', 'cancel_preliminary_schedule',
})
CODES = frozenset({
    'unknown_tool', 'invalid_evidence', 'booking_not_owned_or_not_active', 'invalid_timezone',
    'timezone_required', 'conflicting_schedule_fields', 'current_booking_and_shift_required',
    'current_booking_and_date_required', 'precise_local_datetime_required', 'local_datetime_must_not_include_offset',
    'nonexistent_dst_time', 'ambiguous_dst_time', 'time_in_past', 'active_booking_exists',
    'stale_booking_version', 'not_owned', 'not_permitted', 'stale_revision', 'invalid_source',
    'input_mismatch', 'transport_outcome_unknown', 'invalid_backend_response', 'backend_unavailable',
    'empty_reply', 'tool_round_limit', 'recovering_committed_operation',
})
FIELDS = frozenset({
    'id', 'student_id', 'status', 'starts_at', 'timezone', 'version', 'duration_minutes', 'occurred_at',
    'body', 'evidence', 'intent', 'schedule', 'localDateTime', 'offset', 'shiftMinutes', 'date', 'mode',
    'bookingId', 'expectedVersion', 'facts', 'memory', 'key', 'value', 'kind', 'trialPreference',
    'preferenceEvidence', 'summary', 'startDate', 'localTime', 'weekdays', 'durationMinutes', 'scheduleId',
    'reply', 'content', 'function', 'name', 'arguments',
})


def record_tool(request_id, name, stage, outcome, error=None, result=None):
    event = dict(**context_fields(), request_key=hashlib.sha256(request_id.encode()).hexdigest()[:16],
                 tool=name if name in TOOLS else 'unknown', stage=stage, outcome=outcome)
    if error is not None:
        diagnostic_event('tutor_failed', error=error, stage=stage, tool=event['tool'])
        event['error_type'] = type(error).__name__
        code = str(error).split(':', 1)[0]
        event['code'] = code if code in CODES else 'unclassified_error'
        if isinstance(error, BackendFailure):
            event['http_status'] = error.status
        elif isinstance(error, ValidationError):
            event['code'] = 'invalid_tool_arguments'
            event['validation'] = [dict(type=item['type'], fields=[
                part if part in FIELDS else ('item' if isinstance(part, int) else 'unknown')
                for part in item['loc']]) for item in error.errors(include_input=False, include_context=False, include_url=False)]
        elif isinstance(error, KeyError):
            event['code'] = 'missing_field'
            event['field'] = error.args[0] if error.args and error.args[0] in FIELDS else 'unknown'
    if isinstance(result, dict):
        event['ok'] = result.get('ok')
        if 'booking' in result:
            event['booking_present'] = result['booking'] is not None
    logging.getLogger(__name__).info('tutor_tool %s', json.dumps(event, ensure_ascii=True))
