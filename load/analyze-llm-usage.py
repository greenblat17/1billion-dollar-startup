"""Summarize load-test LLM tokens from filtered ai-service logs.

Example: python3 load/analyze-llm-usage.py AI_LOG --run-id longvoice20261014
"""

from __future__ import annotations

import argparse
from collections import deque
from datetime import datetime, timezone
import json
from pathlib import Path
import re

MARKER = "load_test_llm_usage "
FIELD = re.compile(r"([a-z_]+)=([^\s]+)")


def read_events(path: Path, run_id: str) -> list[dict]:
    prefix = f"loadtest-{run_id}-"
    events = []
    for line in path.open(errors="replace"):
        if MARKER not in line:
            continue
        fields = dict(FIELD.findall(line.split(MARKER, 1)[1]))
        if not fields.get("session", "").startswith(prefix):
            continue
        try:
            timestamp = datetime.strptime(line[:23], "%Y-%m-%d %H:%M:%S,%f").replace(tzinfo=timezone.utc)
            elapsed_ms = int(fields["elapsed_ms"])
            if fields["outcome"] == "ok":
                prompt = int(fields["prompt_tokens"])
                completion = int(fields["completion_tokens"])
                if prompt < 0 or completion < 0:
                    raise ValueError("negative token usage")
            else:
                prompt = completion = 0
        except (KeyError, ValueError) as error:
            raise ValueError(f"malformed load-test usage line: {line[:120]}") from error
        events.append({
            "timestamp": timestamp,
            "session": fields["session"],
            "purpose": fields.get("purpose", "unknown"),
            "model": fields.get("model", "unknown"),
            "outcome": fields["outcome"],
            "elapsed_ms": elapsed_ms,
            "prompt": prompt,
            "completion": completion,
            "usage_present": fields.get("usage_present") == "True" if fields["outcome"] == "ok" else False,
        })
    return sorted(events, key=lambda event: event["timestamp"])


def summarize(events: list[dict]) -> dict:
    successful = [event for event in events if event["outcome"] == "ok"]
    if not successful:
        raise ValueError("no successful load-test LLM calls found")
    missing = sum(not event["usage_present"] for event in successful)
    pending: deque[dict] = deque()
    prompt_window = completion_window = 0
    peak = {"prompt": 0, "completion": 0, "total": 0, "ending_at_utc": ""}
    for event in successful:
        pending.append(event)
        prompt_window += event["prompt"]
        completion_window += event["completion"]
        while pending and (event["timestamp"] - pending[0]["timestamp"]).total_seconds() >= 60:
            old = pending.popleft()
            prompt_window -= old["prompt"]
            completion_window -= old["completion"]
        if prompt_window + completion_window > peak["total"]:
            peak = {
                "prompt": prompt_window,
                "completion": completion_window,
                "total": prompt_window + completion_window,
                "ending_at_utc": event["timestamp"].isoformat(),
            }
    by_purpose = {}
    for event in successful:
        purpose = by_purpose.setdefault(event["purpose"], {"calls": 0, "prompt": 0, "completion": 0})
        purpose["calls"] += 1
        purpose["prompt"] += event["prompt"]
        purpose["completion"] += event["completion"]
    return {
        "successful_llm_calls": len(successful),
        "failed_llm_attempts": len(events) - len(successful),
        "calls_without_provider_usage": missing,
        "prompt_tokens_total": sum(event["prompt"] for event in successful),
        "completion_tokens_total": sum(event["completion"] for event in successful),
        "peak_rolling_60s_tokens": peak,
        "peak_output_tokens_per_second": round(peak["completion"] / 60, 2),
        "by_purpose": by_purpose,
        "first_llm_response_utc": successful[0]["timestamp"].isoformat(),
        "last_llm_response_utc": successful[-1]["timestamp"].isoformat(),
        "method": "Token usage is attributed to the timestamp of each completed provider response. "
                  "Output TPS is peak completion tokens in a rolling 60-second window divided by 60; "
                  "it is aggregate system throughput, not per-request generation speed.",
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    result = summarize(read_events(args.log, args.run_id))
    output = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.out:
        args.out.write_text(output)
    print(output, end="")


if __name__ == "__main__":
    main()
