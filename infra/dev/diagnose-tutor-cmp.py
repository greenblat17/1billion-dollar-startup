#!/usr/bin/env python3
"""Print allowlisted Ktor tutor diagnostics around an exact UTC timestamp."""

import json
import re
import subprocess
import sys
from datetime import datetime, timedelta, timezone


EVENTS = {'tutor_http_failed', 'tutor_failed'}
FIELDS = {'trace_id': r'[0-9a-f]{32}', 'error_id': r'[0-9a-f-]{36}',
          'error_type': r'[A-Za-z][A-Za-z0-9_]{0,79}', 'cause_type': r'[A-Za-z][A-Za-z0-9_]{0,79}',
          'provider_code': r'[a-z][a-z0-9_]{0,100}', 'error_code': r'[a-z][a-z0-9_]{0,100}',
          'http_status': r'[1-5][0-9]{2}', 'sql_state': r'[A-Z0-9]{5}'}


def safe_events(logs):
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
        if not isinstance(event, str) or event not in EVENTS:
            continue
        fields = {key: value for key, pattern in FIELDS.items()
                  if isinstance(value := data.get(key), str) and re.fullmatch(pattern, value)}
        events.append({'at': at, 'event': event, **fields})
    return events


def main(timestamp):
    if not re.fullmatch(r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z', timestamp):
        raise SystemExit('invalid_diagnostic_timestamp')
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
    events = safe_events(result.stdout)
    print(json.dumps({'at': timestamp, 'windowSeconds': 60, 'eventCount': len(events), 'events': events[-40:]}))


if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('missing_diagnostic_timestamp')
    main(sys.argv[1])
