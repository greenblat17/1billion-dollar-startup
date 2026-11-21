#!/usr/bin/env python3
"""Run one synthetic learner turn on the DEV Ktor host over loopback."""

import json
import os
import subprocess
import sys
from pathlib import Path


def main():
    work = Path(sys.argv[1]).resolve()
    raw = subprocess.check_output(
        ["docker", "inspect", "--format", "{{json .Config.Env}}", "speaking-coach"], text=True
    )
    values = dict(item.split("=", 1) for item in json.loads(raw) if "=" in item)
    if values.get("SPEAKY_ENV") != "dev" or values.get("AGENT_TEST_ENABLED") != "true":
        raise SystemExit("DEV agent test route is disabled: "
                         f"environment_is_dev={values.get('SPEAKY_ENV') == 'dev'}, "
                         f"agent_test_enabled={values.get('AGENT_TEST_ENABLED') == 'true'}")
    token = values.get("AI_INTERNAL_TOKEN")
    port = values.get("SERVER_PORT")
    if not token or not port or not port.isdecimal():
        raise SystemExit("DEV agent test configuration is incomplete")
    payload = json.loads((work / "input.json").read_text())
    command = [sys.executable, str(work / "agent-dialogue.py"), "--message", payload["message"],
               "--base-url", f"https://127.0.0.1:{port}", "--loopback-tls",
               "--output", str(work / "transcript.json")]
    if payload.get("studentId"):
        command.extend(["--student-id", payload["studentId"]])
    result = subprocess.run(command, env=dict(os.environ, AI_INTERNAL_TOKEN=token),
                            text=True, capture_output=True)
    (work / "result.json").write_text(result.stdout)
    if result.stderr:
        (work / "error.txt").write_text(result.stderr)
    return result.returncode


if __name__ == "__main__":
    sys.exit(main())
