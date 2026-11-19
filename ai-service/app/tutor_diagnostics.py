"""Content-free diagnostics for native tutor tools; never log arguments or exception text."""
import hashlib
import logging

from pydantic import ValidationError

from app.foundation_client import BackendFailure
from app.diagnostics import event as diagnostic_event

TOOLS = frozenset({
    'get_trial_booking', 'create_trial_booking', 'reschedule_trial_booking', 'cancel_trial_booking',
    'update_learner_context', 'update_onboarding', 'save_preliminary_schedule', 'get_preliminary_schedules',
    'cancel_preliminary_schedule',
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
    fields = dict(request_key=hashlib.sha256(request_id.encode()).hexdigest()[:16],
                 tool=name if name in TOOLS else 'unknown', stage=stage, outcome=outcome)
    if error is not None:
        code = str(error).split(':', 1)[0]
        fields['code'] = code if code in CODES else 'unclassified_error'
        if isinstance(error, BackendFailure):
            fields['http_status'] = error.status
        elif isinstance(error, ValidationError):
            fields['code'] = 'invalid_tool_arguments'
            fields['validation'] = [dict(type=item['type'], fields=[
                part if part in FIELDS else ('item' if isinstance(part, int) else 'unknown')
                for part in item['loc']]) for item in error.errors(include_input=False, include_context=False, include_url=False)]
        elif isinstance(error, KeyError):
            fields['code'] = 'missing_field'
            fields['field'] = error.args[0] if error.args and error.args[0] in FIELDS else 'unknown'
    if isinstance(result, dict):
        fields['ok'] = result.get('ok')
        if 'booking' in result:
            fields['booking_present'] = result['booking'] is not None
    diagnostic_event('tutor_failed' if error else 'tutor_tool', marker='tutor_tool',
                     logger=logging.getLogger(__name__), error=error, operation='tutor_tool',
                     reason_code=fields.get('code'), **fields)
