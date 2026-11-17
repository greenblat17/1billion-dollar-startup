#!/usr/bin/env python3
"""Send a BB agent's next learner turn or run fixed turns against DEV."""

import argparse
import json
import os
import ssl
import sys
import time
import uuid
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request, urlopen


def request(base, token, path, payload=None, *, loopback_tls=False):
    data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode()
    req = Request(base + path, data=data, headers={
        "X-Internal-Token": token,
        "Content-Type": "application/json",
    }, method="GET" if data is None else "POST")
    try:
        context = ssl._create_unverified_context() if loopback_tls else None
        with urlopen(req, timeout=150, context=context) as response:
            return json.load(response)
    except HTTPError as error:
        detail = error.read(1000).decode(errors="replace")
        raise RuntimeError(f"DEV returned HTTP {error.code}: {detail}") from None
    except URLError as error:
        raise RuntimeError(f"DEV connection failed: {error.reason}") from None


def write_readable_dialogue(path, transcript):
    lines = ["# Диалог с Сашей на DEV", "", f"Тестовый ученик: `{transcript['studentId']}`", ""]
    for number, turn in enumerate(transcript["turns"], 1):
        lines.extend([f"## Ход {number}", "", "**Ученик**", "", turn["input"]["text"], "",
                      "**Саша**", "", turn["response"].get("reply") or "(текстового ответа нет)", ""])
    if transcript.get("error"):
        lines.extend(["## Ошибка прогона", "", transcript["error"], ""])
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w") as output:
        output.write("\n".join(lines))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scenario", nargs="?", type=Path, help="optional JSON with fixed turns")
    parser.add_argument("--base-url", required=True, help="DEV Ktor origin, without a path")
    parser.add_argument("--output", required=True, type=Path, help="JSON transcript file")
    parser.add_argument("--student-id", type=uuid.UUID, default=None, help="continue a synthetic student")
    parser.add_argument("--message", help="one learner message chosen by the BB agent")
    parser.add_argument("--loopback-tls", action="store_true",
                        help="permit local TLS certificate mismatch only for a loopback DEV host")
    args = parser.parse_args()
    parsed = urlparse(args.base_url)
    if parsed.scheme != "https" and not (parsed.scheme == "http" and parsed.hostname in ("localhost", "127.0.0.1")):
        parser.error("Use HTTPS, or HTTP on localhost")
    if parsed.path not in ("", "/") or parsed.query or parsed.fragment or not parsed.netloc:
        parser.error("--base-url must be an origin")
    if args.loopback_tls and parsed.hostname not in ("localhost", "127.0.0.1"):
        parser.error("--loopback-tls is limited to localhost")
    token = os.environ.get("AI_INTERNAL_TOKEN", "")
    if not token:
        parser.error("AI_INTERNAL_TOKEN is required in the environment")
    if (args.message is None) == (args.scenario is None):
        parser.error("Provide exactly one of --message or a fixed-turn scenario")
    if args.message is not None:
        turns = [args.message]
        if not turns[0].strip() or turns[0].strip().startswith("/start"):
            parser.error("--message must contain text other than /start")
    else:
        scenario = json.loads(args.scenario.read_text())
        turns = scenario.get("turns")
        if not isinstance(turns, list) or not turns or any(not isinstance(t, str) or not t.strip() or t.strip().startswith("/start") for t in turns):
            parser.error("scenario.turns must contain nonempty text turns; /start is unsupported")
    if args.output.exists():
        if args.message is None or args.student_id is None:
            parser.error("Only --message with --student-id can continue an existing transcript")
        transcript = json.loads(args.output.read_text())
        if transcript.get("studentId") != str(args.student_id):
            parser.error("--student-id does not match the transcript")
    else:
        student = args.student_id or uuid.uuid4()
        transcript = {"scenario": args.scenario.stem if args.scenario else "bb-agent",
                      "studentId": str(student), "mode": "bb_agent" if args.message else "fixed",
                      "baseUrl": args.base_url.rstrip("/"), "turns": [], "result": "incomplete"}
    student = uuid.UUID(transcript["studentId"])
    base = args.base_url.rstrip("/")
    if transcript["baseUrl"] != base:
        parser.error("--base-url does not match the transcript")
    root = f"/internal/agent-test/v1/{student}"
    try:
        for text in turns:
            payload = {"messageId": str(uuid.uuid4()), "text": text, "receivedAt": round(time.time(), 3)}
            result = request(base, token, root + "/turn", payload, loopback_tls=args.loopback_tls)
            response = result["response"]
            transcript["turns"].append({"input": payload, "response": response, "state": result["state"]})
            if not response.get("reply") and not response.get("booking"):
                raise RuntimeError("Tutor returned neither reply nor booking")
        transcript["result"] = "completed"
    except (RuntimeError, KeyError, ValueError) as error:
        transcript["error"] = str(error)
        transcript["result"] = "failed"
    args.output.parent.mkdir(parents=True, exist_ok=True)
    descriptor = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w") as output:
        json.dump(transcript, output, ensure_ascii=False, indent=2)
        output.write("\n")
    dialogue_path = args.output.with_suffix(".md")
    write_readable_dialogue(dialogue_path, transcript)
    print(json.dumps({"result": transcript["result"], "studentId": str(student),
                      "response": transcript["turns"][-1]["response"] if transcript["turns"] else None,
                      "transcript": str(args.output), "dialogue": str(dialogue_path)}, ensure_ascii=False))
    return 0 if transcript["result"] == "completed" else 1


if __name__ == "__main__":
    sys.exit(main())
