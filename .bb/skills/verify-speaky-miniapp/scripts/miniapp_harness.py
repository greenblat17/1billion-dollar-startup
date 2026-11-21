#!/usr/bin/env python3
"""Isolated local Mini App UI harness. API replies stand in for the AI edge."""

import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[4]
NODE_BUILD = ROOT / "mini-app/scripts/build.mjs"


def fixture(action, body):
    lesson = {
        "id": "verify-lesson", "kind": "trial", "starts_at": "2030-01-01T16:00:00Z",
        "timezone": "UTC+03:00", "status": "scheduled", "duration_minutes": 30,
        "launch_mode": "executable",
    }
    if action == "progress":
        return {"status": "ready", "goal": {"value": "Уверенно говорить на рабочих встречах"},
                "evidenceStatus": "reviewed", "steps": [
                    {"id": "one", "outcome": "Начать разговор", "tactic": "Практика с Сашей", "status": "completed"},
                    {"id": "two", "outcome": "Объяснить идею на встрече", "tactic": "Ролевая игра", "status": "current"},
                    {"id": "three", "outcome": "Ответить на вопросы", "tactic": "Диалог", "status": "upcoming"},
                ]}
    if action == "list":
        return {"lessons": [lesson], "schedules": [], "demoReceipts": []}
    if action == "calendar" and body.get("lessonId") == lesson["id"]:
        return {"lesson": lesson, "googleUrl": "", "ics": "BEGIN:VCALENDAR\nEND:VCALENDAR\n",
                "filename": "speaky-verify.ics"}
    if action == "lesson_status" and body.get("lessonId") == lesson["id"]:
        return {"lesson": lesson, "serverNow": "2029-12-31T16:00:00Z"}
    return None


def serve(run_dir):
    run_dir.mkdir(parents=True, exist_ok=False)
    dist = run_dir / "dist"
    subprocess.run(["node", "--input-type=module", "-e",
                    "import {build} from './mini-app/scripts/build.mjs'; await build(process.argv[1])",
                    str(dist)], cwd=ROOT, check=True)
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    requests = run_dir / "requests.jsonl"

    class Handler(SimpleHTTPRequestHandler):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, directory=str(dist), **kwargs)

        def log_message(self, *_args):
            pass

        def do_GET(self):
            if self.path == "/verify/doctor":
                self.reply(200, {"pid": os.getpid(), "revision": revision, "surface": "miniapp-fixture"})
                return
            if self.path in ("/miniapp", "/miniapp/"):
                self.path = "/miniapp/index.html"
            super().do_GET()

        def do_POST(self):
            if self.path != "/miniapp/api":
                self.reply(404, {"detail": "not_found"})
                return
            try:
                body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
            except (ValueError, json.JSONDecodeError):
                self.reply(400, {"detail": "bad_json"})
                return
            safe = {key: value for key, value in body.items() if key != "initData"}
            safe["initDataPresent"] = bool(body.get("initData"))
            with requests.open("a") as log:
                log.write(json.dumps(safe, ensure_ascii=False) + "\n")
            result = fixture(body.get("action"), body)
            self.reply(200 if result is not None else 400, result or {"detail": "unsupported_action"})

        def reply(self, code, value):
            payload = json.dumps(value, ensure_ascii=False).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    instance = {"url": f"http://127.0.0.1:{server.server_port}", "pid": os.getpid(),
                "revision": revision, "runDir": str(run_dir)}
    (run_dir / "instance.json").write_text(json.dumps(instance) + "\n")
    print(json.dumps(instance), flush=True)
    try:
        server.serve_forever()
    finally:
        server.server_close()


def doctor(run_dir):
    instance = json.loads((run_dir / "instance.json").read_text())
    with urlopen(instance["url"] + "/verify/doctor", timeout=3) as response:
        state = json.load(response)
    if state["pid"] != instance["pid"] or state["revision"] != instance["revision"]:
        raise SystemExit("wrong instance or revision")
    with urlopen(instance["url"] + "/miniapp", timeout=3) as response:
        if response.status != 200 or b"Speaky" not in response.read():
            raise SystemExit("Mini App is not usable")
    print(json.dumps(state))


def stop(run_dir):
    instance = json.loads((run_dir / "instance.json").read_text())
    doctor(run_dir)
    os.kill(instance["pid"], signal.SIGTERM)
    print(f"stopped {instance['pid']}; evidence in {run_dir} remains")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["serve", "doctor", "stop"])
    parser.add_argument("--run-dir", type=Path, required=True)
    args = parser.parse_args()
    {"serve": serve, "doctor": doctor, "stop": stop}[args.command](args.run_dir.resolve())
