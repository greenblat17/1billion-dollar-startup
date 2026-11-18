#!/usr/bin/env python3
"""Print allowlisted Ktor tutor diagnostics around an exact UTC timestamp."""

import hashlib
import json
import re
import subprocess
import sys
from datetime import datetime, timedelta, timezone
from urllib.error import URLError
from urllib.parse import urlencode
from urllib.request import ProxyHandler, build_opener


EVENTS = {'tutor_http_failed', 'tutor_failed'}
JOB_ID_PATTERN = r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}'
STACK_FRAME_PATTERN = r'[A-Za-z0-9_.$<>]+:-?\d{1,6}'
FIELDS = {'trace_id': r'[0-9a-f]{32}|[0-9a-f-]{36}', 'error_id': r'[0-9a-f]{32}|[0-9a-f-]{36}',
          'upstream_error_id': r'[0-9a-f]{32}|[0-9a-f-]{36}', 'request_id': r'[0-9a-f]{32}',
          'operation': r'[a-z][a-z0-9_.]{0,70}', 'reason_code': r'[a-z][a-z0-9_]{0,70}',
          'stage': r'[a-z][a-z0-9_.]{0,70}', 'job_status': r'[a-z_]{1,40}',
          'error_type': r'[A-Za-z][A-Za-z0-9_]{0,79}', 'cause_type': r'[A-Za-z][A-Za-z0-9_]{0,79}',
          'provider_code': r'[a-z][a-z0-9_]{0,100}', 'error_code': r'[a-z][a-z0-9_]{0,100}',
          'http_status': r'[1-5][0-9]{2}', 'sql_state': r'[A-Z0-9]{5}'}


def safe_events(logs, job_id=''):
    events = []
    for line in logs.splitlines():
        at, _, _ = line.partition(' ')
        if not re.fullmatch(r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z', at):
            continue
        _, marker, body = line.partition('runtime_event ')
        if not marker:
            continue
        try:
            data = json.loads(body)
        except ValueError:
            continue
        if not isinstance(data, dict) or data.get('service') != 'backend':
            continue
        event = data.get('event')
        if not isinstance(event, str):
            continue
        fields = {key: value for key, pattern in FIELDS.items()
                  if isinstance(value := data.get(key), str) and re.fullmatch(pattern, value)}
        if type(data.get('http_status')) is int and 100 <= data['http_status'] <= 599:
            fields['http_status'] = data['http_status']
        if job_id:
            if event not in ('foundation_job_failed', 'foundation_job', 'foundation_job_ai_call') or data.get('job_id') != job_id:
                continue
            stack = data.get('stack')
            if isinstance(stack, list):
                fields['stack'] = [frame for frame in stack[:6]
                                   if isinstance(frame, str) and re.fullmatch(STACK_FRAME_PATTERN, frame)]
            fields['job_id'] = job_id
        elif event not in EVENTS:
            continue
        events.append({'at': at, 'event': event, **fields})
    return events


def safe_ai_events(payload, trace_id):
    events = []
    for stream in payload.get('data', {}).get('result', []):
        for timestamp, line in stream.get('values', []):
            _, marker, raw = line.partition('runtime_event ')
            if not marker:
                _, marker, raw = line.partition('provider_call ')
            if not marker:
                continue
            try:
                data = json.loads(raw)
            except ValueError:
                continue
            if not isinstance(data, dict) or data.get('service') != 'ai' or (
                data.get('event') not in ('foundation_failed', 'foundation_compute', 'provider_failed') or data.get('trace_id') != trace_id
            ):
                continue
            event = {'timestamp_ns': timestamp, 'trace_id': trace_id, 'event': data['event']}
            for key in ('stage', 'error_type', 'error_code', 'cause_type', 'operation', 'reason_code'):
                value = data.get(key)
                if isinstance(value, str) and re.fullmatch(r'[A-Za-z][A-Za-z0-9_.]{0,100}', value):
                    event[key] = value
            stack = data.get('stack')
            if isinstance(stack, list):
                event['stack'] = [
                    frame for frame in stack[-6:] if isinstance(frame, dict)
                    and set(frame) == {'file', 'function', 'line'}
                    and isinstance(frame['file'], str) and re.fullmatch(r'[A-Za-z0-9_]+\.py', frame['file'])
                    and isinstance(frame['function'], str) and re.fullmatch(r'[A-Za-z0-9_<>]+', frame['function'])
                    and type(frame['line']) is int
                ]
            events.append(event)
    return events


def archived_ai_events(job_id, since, until):
    trace_id = hashlib.sha256(job_id.encode()).hexdigest()[:32]
    query = urlencode({
        'query': '{service_name="ai-service",environment="dev"} |~ "runtime_event|provider_call" '
                 f'|= "{trace_id}"',
        'start': int(since.timestamp()) * 10**9,
        'end': int(until.timestamp()) * 10**9,
        'limit': 20,
    })
    try:
        opener = build_opener(ProxyHandler({}))
        with opener.open('http://127.0.0.1:3100/loki/api/v1/query_range?' + query, timeout=8) as response:
            events = safe_ai_events(json.load(response), trace_id)
    except (URLError, TimeoutError, ValueError):
        return {'trace_id': trace_id, 'status': 'log_unavailable'}
    return {'trace_id': trace_id, 'status': 'read', 'eventCount': len(events), 'events': events}


def main(timestamp, job_id=''):
    if not re.fullmatch(r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z', timestamp):
        raise SystemExit('invalid_diagnostic_timestamp')
    if job_id and not re.fullmatch(JOB_ID_PATTERN, job_id):
        raise SystemExit('invalid_job_id')
    try:
        at = datetime.strptime(timestamp, '%Y-%m-%dT%H:%M:%SZ').replace(tzinfo=timezone.utc)
        since = at - timedelta(seconds=30)
        until = at + timedelta(seconds=30)
    except (ValueError, OverflowError):
        raise SystemExit('invalid_diagnostic_timestamp') from None
    result = subprocess.run(['docker', 'logs', '--timestamps', '--since', since.isoformat(),
                             '--until', until.isoformat(), 'speaking-coach'],
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, check=False)
    if result.returncode:
        raise SystemExit('cmp_log_unavailable')
    events = safe_events(result.stdout, job_id)
    print(json.dumps({'at': timestamp, 'windowSeconds': 60, 'eventCount': len(events), 'events': events[-40:]}))
    if job_id:
        print(json.dumps({'archivedAiFoundation': archived_ai_events(job_id, since, until)}))


if __name__ == '__main__':
    if len(sys.argv) not in (2, 3):
        raise SystemExit('missing_diagnostic_timestamp')
    main(*sys.argv[1:])
