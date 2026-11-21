"""Payload-free operational events. Values from external boundaries need separate validators."""
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import json
import logging
import os
import re
from threading import Lock
import traceback
from uuid import uuid4
from typing import Literal

_context = ContextVar('diagnostic_context', default={})
_loss_lock = Lock()
_pending_losses = 0
_ID = re.compile(r'[A-Za-z0-9_.:-]{1,128}\Z')
_RELEASE = re.compile(r'[0-9a-f]{7,64}')
_ENVIRONMENTS = {'dev', 'prod', 'local', 'test'}
_OPAQUE = re.compile(r'[0-9a-f]{32}|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}')
_PROVIDER_ID = re.compile(r'(?:req[_-]|request[_-]|gen-|item_|resp_|evt_)[A-Za-z0-9_-]{8,96}')


def count_diagnostic_loss() -> None:
    global _pending_losses
    with _loss_lock:
        _pending_losses = min(_pending_losses + 1, 10**12 - 1)


_KEYS = {'trace_id', 'request_id', 'lesson_id', 'call_session_id', 'turn_id', 'operation_id',
         'job_id', 'notice_id', 'attempt_id'}
_LINKS = {'client_attempt_id', 'server_error_id', 'upstream_error_id'}
_CORRELATION_NUMBERS = {'fence', 'attempt'}
_LABELS = {
    *_KEYS, 'component', 'operation', 'stage', 'outcome', 'reason', 'reason_code', 'error_code', 'client_code',
    'method', 'route', 'provider', 'model', 'tool', 'transport', 'direction', 'skill', 'event_kind',
    'action_kind', 'source_event_id', 'field', 'rule', 'actualType', 'type', 'schema_name', 'schema_hash',
    'last_input_stage', 'status_reason', 'status_error_code', 'metric', 'peer_kind', 'request_key',
    'item_id', 'code', 'error_type', 'purpose', 'result', 'source', 'user_id',
    'reference_purpose', 'source_role', 'finish_reason',
}
_NUMBERS = {
    *_CORRELATION_NUMBERS, 'duration_ms', 'elapsed_ms', 'http_status', 'close_code', 'generation', 'bytes_sent',
    'roadmap_revision',
    'received_bytes', 'forwarded_bytes', 'received_input_bytes', 'forwarded_input_bytes',
    'pending_playback', 'pending_input_count', 'trailing_silence_ms', 'last_input_forwarded_ms_ago',
    'milliseconds', 'provider_audio_end_ms', 'stop_to_commit_ms', 'commit_to_request_ms',
    'request_to_first_audio_ms', 'commit_to_forward_ms', 'output_tokens', 'eligible_attempts',
    'cited_attempts', 'line', 'column', 'length', 'count', 'expectedCount', 'missingCount',
    'unexpectedCount', 'maxLength', 'minCount', 'maxCount', 'coverage_threshold', 'bytes_received',
    'first_audio_ms', 'playback_ms', 'cost_micro_usd', 'max_completion_tokens', 'input_characters',
    'pending_playback_count', 'pending_transcriptions', 'rejected_facts', 'rejected_candidates',
    'request_to_audio_ms', 'first_chunk_archive_ms', 'prompt_tokens', 'completion_tokens', 'reasoning_tokens',
}

Outcome = Literal['started', 'completed', 'failed', 'rejected', 'skipped', 'cancelled', 'unknown',
                  'degraded', 'incomplete', 'retried', 'replayed', 'superseded']
_ADVERSE = {'failed', 'rejected', 'unknown', 'degraded', 'incomplete'}


@dataclass(frozen=True)
class EventSpec:
    """One explicit outcome owned by a runtime component."""

    name: str
    component: str
    operation: str
    stage: str
    outcome: Outcome
    message: str
    reason_code: str | None = None
    level: int = logging.INFO
    fields: frozenset[str] = frozenset()
_FLAGS = {'muted', 'speaking', 'final', 'endpoint', 'dev', 'enabled', 'token_valid',
          'mixed_auth', 'duplicate', 'urlPresent', 'passed', 'ok', 'booking_present', 'response_running', 'valid', 'has_text'}
_COUNTERS = {
    'usage': {'input_tokens', 'output_tokens', 'total_tokens', 'cached_tokens', 'text_tokens', 'audio_tokens'},
    'client': {'capturedFrames', 'capturedBytes', 'sentFrames', 'sentBytes', 'receivedFrames',
               'receivedBytes', 'playedFrames', 'playbackQueue', 'bufferedAmount', 'underruns',
               'captureFrames', 'captureBytes', 'capturePeak', 'playbackQueued', 'playbackScheduled',
               'bufferedBytes', 'workletFrames', 'workletBytes', 'droppedFrames', 'playing'},
}
_PROVIDER_CODES = {
    'invalid_request_error', 'invalid_request', 'bad_request', 'invalid_json_schema', 'invalid_schema',
    'invalid_value', 'invalid_type', 'invalid_parameter', 'missing_required_parameter', 'unsupported_parameter',
    'model_not_found', 'context_length_exceeded', 'rate_limit_exceeded', 'insufficient_quota',
    'authentication_error', 'permission_denied', 'server_error', 'service_unavailable',
    'in_flight_budget_exhausted', 'openrouter_in_flight_budget', 'input_audio_buffer_commit_empty',
    'response_cancel_not_active', 'content_policy_violation', 'account_deactivated', 'limit_reached',
    'AccessDenied', 'NoSuchBucket', 'InvalidAccessKeyId', 'SignatureDoesNotMatch',
}
_PROVIDER_FIELDS = re.compile(
    r'(?:response_format|text\.format)(?:(?:\.|/)(?:json_schema|schema|name|strict|type|properties|'
    r'required|additionalProperties|items|anyOf|enum|verdict|errorEvidence|criterion|quote|feedback|'
    r'rationale|switchTarget|previousAnswerWasIncorrect)){0,12}|'
    r'model|temperature|max_tokens|max_completion_tokens|messages|input|tools|tool_choice'
)
_UPSTREAMS = {'openai': 'openai', 'openrouter': 'openrouter', 'groq': 'groq',
              'google': 'google', 'anthropic': 'anthropic', 'azure': 'azure',
              'cerebras': 'cerebras', 'deepinfra': 'deepinfra', 'together': 'together'}


def identifier(value):
    return value if isinstance(value, str) and _ID.fullmatch(value) else None


def opaque_id(value):
    return value if isinstance(value, str) and _OPAQUE.fullmatch(value) else None


def provider_id(value):
    return value if opaque_id(value) or isinstance(value, str) and _PROVIDER_ID.fullmatch(value) else None


def provider_code(value):
    if type(value) is int and 100 <= value <= 599:
        return str(value)
    return value if isinstance(value, str) and (value in _PROVIDER_CODES or re.fullmatch(r'[1-5]\d\d', value)) else None


def request_trace(value):
    return hashlib.sha256(str(value).encode()).hexdigest()[:32]


def context_fields():
    return dict(_context.get())


@contextmanager
def diagnostic_context(**fields):
    values = context_fields()
    values.update({k: identifier(v) for k, v in fields.items() if k in _KEYS and identifier(v)})
    values.update({k: opaque_id(v) for k, v in fields.items() if k in _LINKS and opaque_id(v)})
    values.update({k: v for k, v in fields.items() if k in _CORRELATION_NUMBERS and
                   type(v) is int and 0 < v < 10**12})
    token = _context.set(values)
    try:
        yield
    finally:
        _context.reset(token)


def correlation_headers(headers):
    ids = {key: value for key, header in (
        ('trace_id', 'X-Diagnostic-Trace'), ('lesson_id', 'X-Diagnostic-Lesson'),
        ('call_session_id', 'X-Diagnostic-Call'), ('job_id', 'X-Diagnostic-Job'),
        ('notice_id', 'X-Diagnostic-Notice'),
    ) if (value := opaque_id(headers.get(header)))}
    for key, header in (('fence', 'X-Diagnostic-Fence'), ('attempt', 'X-Diagnostic-Attempt')):
        value = headers.get(header)
        if isinstance(value, str) and re.fullmatch(r'[1-9][0-9]{0,11}', value):
            ids[key] = int(value)
    return ids


def _upstream_error(raw):
    # Some gateways wrap an upstream JSON error. Never inspect its free-text message.
    if isinstance(raw, str) and len(raw) <= 8192:
        try:
            raw = json.loads(raw)
        except (ValueError, RecursionError):
            return {}
    detail = raw.get('error') if isinstance(raw, dict) else None
    return detail if isinstance(detail, dict) else {}


def error_fields(error):
    result = {'error_type': type(error).__name__}
    response = getattr(error, 'response', None)
    status = (getattr(error, 'status_code', None) or getattr(error, 'status', None)
              or getattr(response, 'status_code', None))
    body = getattr(error, 'body', None)
    headers = getattr(response, 'headers', {}) or {}
    request_id = provider_id(getattr(error, 'request_id', None)) or provider_id(headers.get('x-request-id'))
    if isinstance(response, dict):  # botocore ClientError
        body = response.get('Error')
        metadata = response.get('ResponseMetadata')
        if isinstance(metadata, dict):
            status = metadata.get('HTTPStatusCode')
            request_id = provider_id(metadata.get('RequestId'))
    if type(status) is int and 100 <= status <= 599:
        result['http_status'] = status
    if request_id:
        result['provider_request_id'] = request_id
    retry_after = headers.get('retry-after')
    if isinstance(retry_after, str) and re.fullmatch(r'\d{1,6}', retry_after):
        result['retry_after'] = int(retry_after)
    if isinstance(body, dict) or request_id:
        detail = body.get('error', body) if isinstance(body, dict) else {}
        detail = detail if isinstance(detail, dict) else {}
        metadata = detail.get('metadata')
        metadata = metadata if isinstance(metadata, dict) else {}
        upstream = metadata.get('provider_name')
        upstream = _UPSTREAMS.get(upstream.lower()) if isinstance(upstream, str) else None
        if upstream:
            result['upstream'] = upstream
        inner = _upstream_error(metadata.get('raw')) if upstream else {}
        provider_error = {}
        for key in ('code', 'type', 'reason', 'limit_source'):
            outer_value = provider_code(detail.get(key, metadata.get(key, detail.get(key.capitalize()))))
            inner_value = provider_code(inner.get(key))
            if inner_value or outer_value:
                provider_error[key] = inner_value or outer_value
            if key == 'code' and inner_value and outer_value and inner_value != outer_value:
                provider_error['gateway_code'] = outer_value
        for candidate in (inner, detail, metadata):
            param = candidate.get('param', candidate.get('field'))
            if isinstance(param, str) and len(param) <= 200 and _PROVIDER_FIELDS.fullmatch(param):
                provider_error['field'] = param
                break
        if provider_error:
            result['provider_error'] = provider_error
        missing = [key for key in ('code', 'type', 'field') if key not in provider_error]
        if 'upstream' not in result:
            missing.append('upstream')
        if 'provider_request_id' not in result:
            missing.append('request_id')
        result['provider_details_missing'] = missing
    if isinstance(error, json.JSONDecodeError):
        result['json_error'] = {'line': error.lineno, 'column': error.colno}
    if isinstance(error, OSError) and type(error.errno) is int:
        result['errno'] = error.errno
    cause = error.__cause__
    if cause is not None:
        result['cause_type'] = type(cause).__name__
    return result


def _validation_fields(item):
    allowed = {k: item[k] for k in ('field', 'type', 'length', 'reason', 'maxLength') if k in item}
    safe = _fields(allowed)
    return {k: safe.get(k) for k, v in allowed.items() if k in safe or v is None}


def _fields(fields):
    safe = {}
    for key, value in fields.items():
        if key in _NUMBERS and type(value) is int and 0 <= value < 10**12:
            safe[key] = value
        elif key in _FLAGS and type(value) is bool:
            safe[key] = value
        elif key in _LINKS and opaque_id(value):
            safe[key] = value
        elif key == 'validation' and isinstance(value, list):
            safe[key] = [{'type': item['type'], 'fields': [part for part in item['fields'][:12] if identifier(part)]}
                         for item in value[:12] if isinstance(item, dict) and identifier(item.get('type'))
                         and isinstance(item.get('fields'), list)]
        elif key == 'fields' and isinstance(value, (list, tuple)):
            safe[key] = [_validation_fields(item) for item in value[:12] if isinstance(item, dict)]
        elif key == 'archive' and isinstance(value, dict):
            safe[key] = {direction: {k: v for k, v in stats.items() if k in {'count', 'mean_ms', 'max_ms'}
                                     and type(v) is int and 0 <= v < 10**12}
                         for direction, stats in value.items()
                         if direction in {'input', 'output', 'user', 'assistant'} and isinstance(stats, dict)}
        elif key == 'field' and isinstance(value, str) and re.fullmatch(r'[A-Za-z0-9_.]{1,100}(?:\[\d{1,2}\])?', value):
            safe[key] = value
        elif key in ('model', 'route') and isinstance(value, str) and re.fullmatch(r'[A-Za-z0-9_./:-]{1,128}', value):
            safe[key] = value
        elif key in _LABELS and identifier(value):
            safe[key] = value
        elif key in ('reasonCodes', 'missingFields') and isinstance(value, (list, tuple)):
            safe[key] = [item for item in value[:24] if identifier(item)]
        elif key in _COUNTERS and isinstance(value, dict):
            allowed = _COUNTERS[key]
            safe[key] = {k: v for k, v in value.items() if k in allowed and type(v) is int and 0 <= v < 10**12}
            if key == 'client':
                safe[key].update({k: value[k] for k in ('muted', 'trackEnabled', 'trackMuted', 'trackEnded',
                                                        'contextRunning', 'pageHidden') if type(value.get(k)) is bool})
            if key == 'usage':
                for part in ('input_token_details', 'output_token_details'):
                    nested = value.get(part)
                    if isinstance(nested, dict):
                        safe[key][part] = {k: v for k, v in nested.items()
                                           if k in allowed and type(v) is int and 0 <= v < 10**12}
    return safe


def _failure_class(outcome, status, error, reason):
    if outcome == 'cancelled':
        return 'cancelled'
    if reason == 'internal_error':
        return 'unexpected'
    if (status in (408, 429) or status is not None and status >= 500 or
            isinstance(error, OSError) or reason in ('provider_transport_error', 'provider_timeout')):
        return 'transient'
    if outcome in ('rejected', 'degraded', 'incomplete') or status is not None and status >= 400:
        return 'expected'
    if error or reason:
        return 'unexpected'
    return None


def event(name: str | EventSpec, *, error=None, marker='runtime_event', logger=None,
          error_id=None, **fields) -> str | None:
    global _pending_losses
    spec = name if isinstance(name, EventSpec) else None
    name = spec.name if spec else name
    # Reuse an ID across propagation. Only the originating record includes frames.
    error_id = opaque_id(error_id)
    losses = 0
    try:
        prior = opaque_id(getattr(error, '_diagnostic_error_id', None)) or opaque_id(getattr(
            getattr(error, '__cause__', None), '_diagnostic_error_id', None))
        if spec:
            fields = {k: v for k, v in fields.items() if k in spec.fields}
            fields.update(component=spec.component, operation=spec.operation, stage=spec.stage, outcome=spec.outcome)
        data = _fields({**context_fields(), **{k: v for k, v in fields.items() if v is not None}})
        outcome = data.get('outcome', 'failed' if error or name.endswith('_failed') else
                           'rejected' if name.endswith(('_rejected', '_denied')) else 'completed')
        failed = outcome in _ADVERSE
        error_id = error_id or prior or (uuid4().hex if error is not None or failed else None)
        reason = (spec.reason_code if spec else data.get('reason_code') or data.get('error_code')
                  or data.get('reason') or data.get('rule') or data.get('status_reason'))
        if reason is None and (error is not None or failed or outcome == 'skipped'):
            reason = 'unexpected_error' if error is not None else 'operation_failed'
        level = spec.level if spec else logging.WARNING if error or failed else logging.INFO
        writer = logger or logging.getLogger(__name__)
        if not writer.isEnabledFor(level):
            return error_id
        stage = data.get('stage', 'execute')
        component = data.get('component') or name
        operation = data.get('operation', name)
        environment = os.getenv('SPEAKY_ENV')
        release = os.getenv('SPEAKY_RELEASE', '')
        if spec:
            message = spec.message
        else:
            operation_label = operation.replace('_', ' ').replace('.', ' ')
            message = f'{operation_label.capitalize()} {outcome} at {stage.replace("_", " ")}'
            if reason:
                message += f' because {reason.replace("_", " ")}'
            message += '.'
        data.update(timestamp=datetime.now(timezone.utc).isoformat(timespec='milliseconds').replace('+00:00', 'Z'),
                    schema_version=1, level=logging.getLevelName(level), service='ai', component=component,
                    environment=environment if environment in _ENVIRONMENTS else 'unknown',
                    release=release if _RELEASE.fullmatch(release) else 'unknown', event=name,
                    operation=operation, stage=stage, outcome=outcome,
                    message=message[:280] or 'The operation reported a diagnostic event.',
                    trace_id=data.get('trace_id') or uuid4().hex, request_id=data.get('request_id'),
                    duration_ms=data.get('duration_ms', data.get('elapsed_ms')), reason_code=reason, error_id=error_id)
        if error is not None:
            data.update(error_fields(error))
            data.setdefault('error_code', reason)
            if not prior and not data.get('upstream_error_id'):
                data['stack'] = [
                    {'file': os.path.basename(f.filename), 'function': f.name, 'line': f.lineno}
                    for f in traceback.extract_tb(error.__traceback__)[-16:]
                ]
        if data.get('provider') and outcome in ('failed', 'rejected'):
            data.setdefault('provider_details_missing', ['code', 'type', 'field', 'upstream', 'request_id'])
        status = data.get('http_status')
        failure_class = _failure_class(outcome, status, error, reason)
        if failure_class is not None:
            data['failure_class'] = failure_class
        if marker == 'provider_call':
            for key in ('http_status', 'cost_micro_usd', 'user_id', 'purpose', 'error_code'):
                data.setdefault(key, None)
        with _loss_lock:
            losses, _pending_losses = _pending_losses, 0
        if losses:
            data['diagnostic_losses'] = losses
        writer.log(level, marker + ' %s', json.dumps(data, ensure_ascii=True))
    except Exception:
        # Diagnostics must not change the outcome of a committed operation.
        with _loss_lock:
            _pending_losses = min(_pending_losses + losses + 1, 10**12 - 1)
    else:
        if error is not None:
            try:
                error._diagnostic_error_id = error_id
            except Exception:
                pass
    return error_id
