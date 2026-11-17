#!/usr/bin/env python3
"""Print bounded, payload-free tutor failure metadata from DEV AI logs."""

import json
import subprocess


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
for line in result.stdout.splitlines():
    marker = next((item for item in ("tutor_tool ", "runtime_event ") if item in line), None)
    if marker is None:
        continue
    try:
        data = json.loads(line.split(marker, 1)[1])
    except (ValueError, IndexError):
        continue
    if marker == "tutor_tool ":
        if data.get("outcome") not in ("failed", "rejected", "degraded"):
            continue
        safe = {key: data.get(key) for key in
                ("tool", "stage", "outcome", "code", "error_type", "http_status", "request_key")
                if data.get(key) is not None}
    elif data.get("event") in ("tutor_failed", "provider_failed"):
        safe = {key: data.get(key) for key in
                ("event", "error_type", "error_code", "http_status", "cause_type")
                if data.get(key) is not None}
        stack = data.get("stack") or []
        if stack:
            safe["last_frame"] = {key: stack[-1].get(key) for key in ("file", "function", "line")}
    else:
        continue
    events.append({"at": line[:19], **safe})

print(json.dumps({"window": "20m", "failure_count": len(events), "last_failures": events[-40:]},
                 ensure_ascii=True))
