"""Bounded operational diagnostics, without payloads, credentials or traceback locals."""
from contextlib import contextmanager
from contextvars import ContextVar
import hashlib
import json
import logging
import os
import re
import traceback
from uuid import uuid4

_context = ContextVar('diagnostic_context', default={})
_ID = re.compile(r'[A-Za-z0-9_.:-]{1,128}\Z')
_KEYS = {'trace_id', 'lesson_id', 'call_session_id', 'turn_id', 'operation_id', 'job_id', 'notice_id', 'attempt_id'}


def identifier(value):
    return value if isinstance(value, str) and _ID.fullmatch(value) else None


def request_trace(value):
    return hashlib.sha256(str(value).encode()).hexdigest()[:32]


def context_fields():
    return dict(_context.get())


@contextmanager
def diagnostic_context(**fields):
    values = context_fields()
    values.update({k: identifier(v) for k, v in fields.items() if k in _KEYS and identifier(v)})
    token = _context.set(values)
    try:
        yield
    finally:
        _context.reset(token)


def clean_message(value):
    """Provider diagnostic text only; never pass prompts or arbitrary exception repr here."""
    if not isinstance(value, str):
        return None
    value = value[:4096]
    for key, secret in os.environ.items():
        if any(part in key.upper() for part in ('TOKEN', 'SECRET', 'PASSWORD', 'API_KEY')) and len(secret) >= 6:
            value = value.replace(secret, '[redacted]')
    value = re.sub(r'https?://\S+', '[url]', value)
    value = re.sub(r'(?i)bearer\s+\S+|sk-[\w-]+|\b\d{6,}:[\w-]+', '[redacted]', value)
    value = re.sub(r'(?i)(authorization|api[_-]?key|token|secret|password|initData|scope)\s*[:=]\s*\S+', r'\1=[redacted]', value)
    # Providers can quote rejected input. Preserve the explanation, not quoted values.
    value = re.sub(r'"[^"\n]*"|\x27[^\x27\n]*\x27', '[quoted]', value)
    value = re.sub(r'\b[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}\b', '[email]', value)
    return ' '.join(value.split())[:1000]


def error_fields(error):
    result = {'error_type': type(error).__name__, 'stack': [
        {'file': os.path.basename(f.filename), 'function': f.name, 'line': f.lineno}
        for f in traceback.extract_tb(error.__traceback__)[-16:]]}
    response = getattr(error, 'response', None)
    status = getattr(error, 'status_code', None) or getattr(response, 'status_code', None)
    if isinstance(status, int):
        result['http_status'] = status
    body = getattr(error, 'body', None)
    if not isinstance(body, dict) and response is not None:
        try:
            body = response.json()
        except Exception:
            pass
    # botocore exceptions expose a dict rather than an HTTP response.
    if isinstance(response, dict):
        body = response.get('Error', {})
        meta = response.get('ResponseMetadata', {})
        result['http_status'] = meta.get('HTTPStatusCode')
        result['provider_request_id'] = identifier(meta.get('RequestId'))
    if isinstance(body, dict):
        detail = body.get('error', body)
        if isinstance(detail, dict):
            metadata = detail.get('metadata')
            metadata = metadata if isinstance(metadata, dict) else {}
            safe = {}
            for key in ('code', 'type', 'message', 'reason', 'limit_source', 'remedy_hint'):
                raw = detail.get(key, metadata.get(key))
                if raw is None:
                    raw = detail.get(key.capitalize())
                if isinstance(raw, (str, int)):
                    safe[key] = clean_message(str(raw))
            if safe:
                result['provider_error'] = safe
    headers = getattr(response, 'headers', {})
    for header, field in [('x-request-id', 'provider_request_id'), ('retry-after', 'retry_after')]:
        raw = headers.get(header) if hasattr(headers, 'get') else None
        if raw:
            result[field] = clean_message(raw)
    request_id = identifier(getattr(error, 'request_id', None))
    if request_id:
        result['provider_request_id'] = request_id
    # Internal semantic codes are safe; arbitrary exception messages may contain user data.
    message = str(error)
    if re.fullmatch(r'[a-z][a-z0-9_]{0,100}', message):
        result['error_code'] = message
    cause = error.__cause__ or error.__context__
    if cause is not None:
        result['cause_type'] = type(cause).__name__
    if isinstance(error, json.JSONDecodeError):
        result['json_error'] = {'line': error.lineno, 'column': error.colno, 'reason': error.msg}
    if isinstance(error, OSError):
        result['errno'] = error.errno
    return result


def event(name, *, error=None, **fields):
    error_id = uuid4().hex if error is not None else None
    try:
        data = {'service': 'ai', 'event': name, **context_fields(), **fields}
        if error is not None:
            data.update(error_id=error_id, **error_fields(error))
        logging.getLogger(__name__).log(logging.WARNING if error else logging.INFO,
                                       'runtime_event %s', json.dumps(data, ensure_ascii=True))
    except Exception:
        # Logging must not change command outcomes or mask the original exception.
        pass
    return error_id
