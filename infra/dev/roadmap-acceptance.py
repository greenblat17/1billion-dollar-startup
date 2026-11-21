#!/usr/bin/env python3
"""Check one DEV transcript fixture and its real Foundation and Telegram outcomes."""

import hashlib
import json
import re
import subprocess
import sys
import time
from urllib.parse import urlencode
from urllib.request import ProxyHandler, build_opener


CONTAINER = "speaking-coach"
MAIN = "com.eliteteam.speakingcoach.foundation.DevRoadmapEvidenceKt"
UUID = r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"


def operator(mode, at):
    result = subprocess.run(
        ["docker", "exec", CONTAINER, "java", "-cp", "/app/server-all.jar", MAIN, mode, at],
        capture_output=True, text=True, timeout=30,
    )
    if result.returncode:
        match = re.search(r"FoundationFailure: (probe_[a-z_]+|lesson_expired|lesson_unavailable|active_episode)", result.stderr)
        raise SystemExit(match[1] if match else "roadmap_acceptance_operator_failed")
    return json.loads(result.stdout.strip().splitlines()[-1])


def trace_id(value):
    if not re.fullmatch(UUID, value):
        raise SystemExit("invalid_evidence_id")
    return hashlib.sha256(value.encode()).hexdigest()[:32]


def runtime_record(line):
    _, marker, body = line.partition("runtime_event ")
    if not marker:
        return {}
    try:
        row = json.loads(body)
        return row if isinstance(row, dict) else {}
    except ValueError:
        return {}


def confirmed_chain(evidence):
    jobs = {job["id"]: job for job in evidence["jobs"]}
    for delivery in evidence["deliveries"]:
        generation = jobs.get(delivery["source_job_id"], {})
        assessment = jobs.get(generation.get("source_job_id"), {})
        observation = jobs.get(assessment.get("source_job_id"), {})
        if (delivery["status"] == "sent" and delivery["parts"] > 0
                and delivery["roadmap_source_matches"] and delivery["source_job_fence"] == generation.get("fence")
                and generation.get("attempt_outcome") == "succeeded"
                and all(job.get("kind") == kind and job.get("status") == "succeeded"
                        for job, kind in [(generation, "roadmap.generate"), (assessment, "trial.assess"),
                                          (observation, "trial.observe")])):
            return delivery, generation
    return None


def check_runtime(evidence, delivery, generation, at):
    delivery_trace = trace_id(delivery["id"])
    job_trace = trace_id(generation["id"])
    result = subprocess.run(["docker", "logs", "--since", at, CONTAINER],
                            capture_output=True, text=True, timeout=20, check=True)
    backend = (runtime_record(line) for line in (result.stdout + "\n" + result.stderr).splitlines())
    receipt = next((row for row in backend if row.get("trace_id") == delivery_trace
                    and row.get("delivery_id") == delivery["id"] and row.get("lesson_id") == evidence["lesson_id"]
                    and row.get("source_job_id") == generation["id"] and row.get("source_job_fence") == generation["fence"]
                    and row.get("stage") == "delivery_persist" and row.get("job_status") == "sent"
                    and row.get("outcome") == "completed"), None)
    params = urlencode({
        "query": '{service_name="ai-service",environment="dev"} |= "runtime_event" '
                 f'|= "{job_trace}"',
        "start": int((time.time() - 3600) * 1e9),
        "end": int(time.time() * 1e9),
        "limit": 50,
    })
    opener = build_opener(ProxyHandler({}))
    with opener.open("http://127.0.0.1:3100/loki/api/v1/query_range?" + params, timeout=10) as response:
        ai_records = [runtime_record(line) for stream in json.load(response)["data"]["result"]
                      for _, line in stream["values"]]
    compute = next((row for row in ai_records if row.get("trace_id") == job_trace and row.get("service") == "ai"
                    and row.get("event") == "foundation_compute" and row.get("job_id") == generation["id"]
                    and row.get("fence") == generation["fence"] and row.get("outcome") == "completed"), None)
    print(json.dumps({"telegram_log": bool(receipt), "python_compute_log": bool(compute),
                      "delivery_trace_id": delivery_trace, "generation_trace_id": job_trace}, sort_keys=True))
    if not receipt or not compute:
        raise SystemExit("roadmap_runtime_evidence_incomplete")


def main(mode, at):
    if mode == "submit":
        print(json.dumps(operator("submit", at), sort_keys=True), flush=True)
    deadline = time.monotonic() + (210 if mode == "submit" else 1)
    while True:
        evidence = operator("inspect", at)
        if not evidence["migration_67"]:
            raise SystemExit("migration_67_not_applied")
        chain = confirmed_chain(evidence)
        if chain or time.monotonic() >= deadline:
            print(json.dumps(evidence, sort_keys=True), flush=True)
            break
        time.sleep(8)
    if not chain:
        raise SystemExit("roadmap_delivery_evidence_incomplete")
    check_runtime(evidence, *chain, at)
    print("dev_roadmap_acceptance=passed")


if __name__ == "__main__":
    main(*sys.argv[1:])
