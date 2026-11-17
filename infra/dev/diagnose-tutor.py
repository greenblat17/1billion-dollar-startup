#!/usr/bin/env python3
"""Print bounded, payload-free tutor failure metadata from DEV AI logs."""

import json
import re
import subprocess
import sys


target = sys.argv[1] if len(sys.argv) > 1 else ''
if target and not re.fullmatch(r'[0-9a-f]{32}', target):
    raise SystemExit('invalid_error_id')


result = subprocess.run(
    ["docker", "logs", "--timestamps", "--since", "20m", "ai-service"],
    stdout=subprocess.PIPE,
    stderr=subprocess.STDOUT,
    text=True,
    check=False,
)
if result.returncode:
    raise SystemExit("ai_log_unavailable")

events = []
tool_events = []
runtime = []
for line in result.stdout.splitlines():
    marker = next((item for item in ("tutor_tool ", "runtime_event ") if item in line), None)
    if marker is None:
        continue
    try:
        data = json.loads(line.split(marker, 1)[1])
    except (ValueError, IndexError):
        continue
    if marker == "tutor_tool ":
        tool_events.append({"at": line[:19], **{key: data.get(key) for key in
                            ("tool", "stage", "outcome", "ok", "booking_present", "request_key")
                            if data.get(key) is not None}})
        if data.get("outcome") not in ("failed", "rejected", "degraded"):
            continue
        safe = {key: data.get(key) for key in
                ("tool", "stage", "outcome", "code", "error_type", "http_status", "request_key")
                if data.get(key) is not None}
    else:
        runtime.append((line[:19], data))
        if data.get("event") not in ("tutor_failed", "provider_failed"):
            continue
        safe = {key: data.get(key) for key in
                ("event", "error_type", "error_code", "http_status", "cause_type")
                if data.get(key) is not None}
        stack = data.get("stack") or []
        if stack:
            safe["last_frame"] = {key: stack[-1].get(key) for key in ("file", "function", "line")}
    events.append({"at": line[:19], **safe})

if target:
    matching = [(at, data) for at, data in runtime if data.get('error_id') == target]
    related = []
    if matching:
        _, match = matching[-1]
        session = match.get('call_session_id')
        trace = match.get('trace_id')
        related = [(at, data) for at, data in runtime if data is not match and
                   ((session and data.get('call_session_id') == session) or
                    (trace and data.get('trace_id') == trace))]
    def diagnostic(at, data):
        safe = {key: data.get(key) for key in
                ('event', 'stage', 'error_id', 'error_type', 'error_code', 'cause_type',
                 'http_status', 'errno', 'provider_request_id') if data.get(key) is not None}
        stack = data.get('stack') or []
        if stack:
            safe['last_frame'] = {key: stack[-1].get(key) for key in ('file', 'function', 'line')}
        provider = data.get('provider_error') or {}
        if provider:
            safe['provider_error'] = {key: provider.get(key) for key in ('code', 'type', 'reason', 'limit_source')
                                      if provider.get(key) is not None}
        return {'at': at, **safe}
    print(json.dumps({'target': target, 'found': bool(matching),
                      'match': diagnostic(*matching[-1]) if matching else None,
                      'related': [diagnostic(at, data) for at, data in related[-20:]]}, ensure_ascii=True))
    raise SystemExit(0)

print(json.dumps({"window": "20m", "failure_count": len(events), "last_failures": events[-40:],
                  "last_tool_events": tool_events[-40:]},
                 ensure_ascii=True))
