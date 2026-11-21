#!/usr/bin/env python3
"""Run a repo-owned Foundation acceptance scenario through DEV Ktor loopback."""

import json
import os
import re
import ssl
import subprocess
import sys
import uuid
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import HTTPSHandler, ProxyHandler, Request, build_opener


def env_values(path):
    values = {}
    for line in path.read_text().splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            values[key] = value.strip().strip('"').strip("'")
    return values


def safe(value):
    """Keep synthetic evidence, never credentials or scope values."""
    if isinstance(value, dict):
        return {key: safe(item) for key, item in value.items()
                if key.lower() not in {"token", "scopetoken", "executionscope", "grant", "authorization", "secret", "password"}
                and not (key.lower() == "scope" and isinstance(item, str)
                         and re.fullmatch(r"[0-9a-f-]{72}", item))}
    if isinstance(value, list):
        return [safe(item) for item in value]
    return value


def resolve(value, previous):
    if isinstance(value, dict):
        if set(value) == {"$uuid"} and value["$uuid"] is True:
            return str(uuid.uuid4())
        if set(value) == {"$now"} and value["$now"] is True:
            return datetime.now(timezone.utc).isoformat()
        if set(value) == {"$ref"}:
            parts = value["$ref"].split(".")
            result = previous[parts.pop(0)]
            for part in parts:
                result = result[int(part)] if isinstance(result, list) else result[part]
            return result
        return {key: resolve(item, previous) for key, item in value.items()}
    if isinstance(value, list):
        return [resolve(item, previous) for item in value]
    return value


def run(work, env_path=None, opener=None):
    if env_path is None:
        raw = subprocess.check_output(
            ["docker", "inspect", "--format", "{{json .Config.Env}}", "speaking-coach"], text=True
        )
        values = dict(item.split("=", 1) for item in json.loads(raw) if "=" in item)
    else:
        values = env_values(env_path)
    if values.get("SPEAKY_ENV") != "dev" or values.get("AGENT_TEST_ENABLED") != "true":
        raise ValueError("agent_test_disabled")
    token, port = values.get("AI_INTERNAL_TOKEN"), values.get("SERVER_PORT")
    if not token or not port or not port.isdecimal():
        raise ValueError("agent_test_config_incomplete")
    config = json.loads((work / "foundation-input.json").read_text())
    mode = config.get("mode")
    raw_id = config.get("studentId") or (str(uuid.uuid4()) if mode == "scenario" else "")
    student_id = str(uuid.UUID(raw_id))
    if raw_id != student_id:
        raise ValueError("invalid_student_id")
    origin = f"https://127.0.0.1:{port}/internal/agent-test/v1/{student_id}"
    if opener is None:
        opener = build_opener(ProxyHandler({}), HTTPSHandler(context=ssl._create_unverified_context()))
    if mode == "regular_entitlement":
        steps = [{"name": "regular_entitlement", "path": "/regular-entitlement", "request": {}}]
    elif mode == "scenario":
        name = config.get("scenario", "")
        if not re.fullmatch(r"foundation-[a-z0-9-]+\.json", name):
            raise ValueError("invalid_scenario")
        steps = json.loads((work / name).read_text()).get("steps")
        if not isinstance(steps, list) or not 1 <= len(steps) <= 40:
            raise ValueError("invalid_scenario")
        for step in steps:
            if set(step) != {"name", "request"} or not isinstance(step["name"], str) or not isinstance(step["request"], dict):
                raise ValueError("invalid_scenario")
            step["path"] = "/foundation-check"
    else:
        raise ValueError("invalid_mode")
    output = {"studentId": student_id, "mode": mode, "steps": []}
    previous = {}
    for step in steps:
        payload = resolve(step["request"], previous)
        request = Request(origin + step["path"], data=json.dumps(payload).encode(),
                          headers={"Content-Type": "application/json", "X-Internal-Token": token}, method="POST")
        try:
            with opener.open(request, timeout=30) as response:
                status, body = response.status, json.load(response)
        except HTTPError as error:
            status = error.code
            try:
                body = json.load(error)
            except (ValueError, UnicodeDecodeError):
                body = {"error": "http_error"}
        except URLError:
            status, body = 0, {"error": "transport_error"}
        if mode == "regular_entitlement":
            body = {key: body[key] for key in ("status", "entitlementId", "validUntil", "error") if key in body}
        public = safe(body)
        output["steps"].append({"name": step["name"], "httpStatus": status, "response": public})
        previous[step["name"]] = {"request": payload, "response": public}
        if status < 200 or status >= 300:
            break
    return output


def main():
    work = Path(sys.argv[1]).resolve()
    try:
        result = run(work)
    except (ValueError, KeyError, OSError) as error:
        result = {"error": str(error).split(":")[0]}
    output = work / "result.json"
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    output.chmod(0o600)
    return 0 if "error" not in result and all(200 <= s["httpStatus"] < 300 for s in result["steps"]) else 1


if __name__ == "__main__":
    sys.exit(main())
