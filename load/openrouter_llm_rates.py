#!/usr/bin/env python3
"""Export historical OpenRouter LLM TPM/TPS without saving generation IDs or keys.

The Analytics API accepts historical minute buckets in windows of at most three
hours. A generation-level query supplies the generation time needed for TPS.
"""

from __future__ import annotations

import argparse
from collections import defaultdict
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import tempfile
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen


API = "https://openrouter.ai/api/v1/analytics/query"
MAX_ROWS = 10_000


def utc_minute(value: str) -> datetime:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None or parsed.utcoffset() != timedelta(0) or parsed.second or parsed.microsecond:
        raise ValueError("from/to must be UTC timestamps aligned to a minute")
    return parsed.astimezone(timezone.utc)


def iso(value: datetime) -> str:
    return value.isoformat(timespec="seconds").replace("+00:00", "Z")


def load_key(env_file: Path | None) -> str:
    if env_file is not None:
        for line in env_file.read_text().splitlines():
            if line.startswith("OPENROUTER_MANAGEMENT_KEY="):
                return line.split("=", 1)[1].strip().strip("\"'")
    return os.environ.get("OPENROUTER_MANAGEMENT_KEY", "")


class Analytics:
    def __init__(self, key: str) -> None:
        self.key = key
        self.last_request = 0.0
        self.requests = 0

    def query(self, body: dict) -> dict:
        # The public API limit is 64 requests/minute; leave room for retries.
        delay = 1.1 - (time.monotonic() - self.last_request)
        if delay > 0:
            time.sleep(delay)
        request = Request(API, json.dumps(body).encode(), {
            "Authorization": f"Bearer {self.key}", "Content-Type": "application/json",
        })
        self.last_request = time.monotonic()
        self.requests += 1
        try:
            with urlopen(request, timeout=90) as response:
                result = json.load(response)["data"]
        except HTTPError as error:
            raise RuntimeError(f"OpenRouter Analytics HTTP {error.code}") from None
        if result["metadata"].get("truncated"):
            result["truncated"] = True
        return result


def time_range(start: datetime, end: datetime) -> dict:
    return {"start": iso(start), "end": iso(end)}


def discover_models(api: Analytics, start: datetime, end: datetime, prefixes: list[str], exact: list[str]) -> list[str]:
    result = api.query({
        "metrics": ["request_count"], "dimensions": ["model"],
        "time_range": time_range(start, end), "limit": MAX_ROWS,
    })
    if result.get("truncated"):
        raise RuntimeError("Model list was truncated; narrow the time range")
    return sorted({row["model"] for row in result["data"]
                   if row["model"] in exact or any(row["model"].startswith(prefix) for prefix in prefixes)})


def generation_rows(api: Analytics, start: datetime, end: datetime, models: list[str]) -> list[dict]:
    model_filter = {"field": "model", "operator": "eq", "value": models[0]} if len(models) == 1 else {
        "field": "model", "operator": "in", "value": models,
    }
    result = api.query({
        "metrics": ["request_count", "tokens_prompt", "tokens_completion", "avg_generation_time"],
        "dimensions": ["generation_id", "model"], "granularity": "minute",
        "time_range": time_range(start, end), "filters": [model_filter], "limit": MAX_ROWS,
    })
    if not result.get("truncated"):
        return result["data"]
    midpoint = start + (end - start) / 2
    midpoint = midpoint.replace(second=0, microsecond=0)
    if midpoint <= start or midpoint >= end:
        raise RuntimeError(f"More than {MAX_ROWS} generations in one minute at {iso(start)}")
    return generation_rows(api, start, midpoint, models) + generation_rows(api, midpoint, end, models)


def active_hours(api: Analytics, start: datetime, end: datetime, models: list[str]) -> list[tuple[datetime, datetime]]:
    model_filter = {"field": "model", "operator": "eq", "value": models[0]} if len(models) == 1 else {
        "field": "model", "operator": "in", "value": models,
    }
    result = api.query({
        "metrics": ["request_count"], "granularity": "hour",
        "time_range": time_range(start, end), "filters": [model_filter], "limit": MAX_ROWS,
    })
    if result.get("truncated"):
        raise RuntimeError("Hourly activity list was truncated; narrow the time range")
    hours = []
    for row in result["data"]:
        if int(row["request_count"]) <= 0:
            continue
        hour = datetime.fromisoformat(row["date__hour"].replace(" ", "T")).replace(tzinfo=timezone.utc)
        hours.append((max(start, hour), min(end, hour + timedelta(hours=1))))
    return sorted((a, b) for a, b in hours if a < b)


def aggregate(rows: list[dict]) -> list[dict]:
    minutes: dict[str, dict] = defaultdict(lambda: {
        "calls": 0, "prompt_tokens": 0, "completion_tokens": 0,
        "timed_calls": 0, "timed_completion_tokens": 0, "generation_ms": 0,
    })
    for row in rows:
        minute = row["created_at__minute"].replace(" ", "T") + "Z"
        item = minutes[minute]
        calls = int(row["request_count"])
        prompt = int(row["tokens_prompt"] or 0)
        completion = int(row["tokens_completion"] or 0)
        item["calls"] += calls
        item["prompt_tokens"] += prompt
        item["completion_tokens"] += completion
        generation_ms = row.get("avg_generation_time")
        if generation_ms is not None and float(generation_ms) > 0:
            item["timed_calls"] += calls
            item["timed_completion_tokens"] += completion
            item["generation_ms"] += round(float(generation_ms) * calls)
    return [
        {"minute_utc": minute, **item, "tpm": item["prompt_tokens"] + item["completion_tokens"],
         "tps_milli": (round(item["timed_completion_tokens"] * 1_000_000 / item["generation_ms"])
                       if item["generation_ms"] else None)}
        for minute, item in sorted(minutes.items())
    ]


def export(api: Analytics, start: datetime, end: datetime, prefixes: list[str], exact: list[str], scope: str) -> dict:
    if end <= start or end - start > timedelta(days=31):
        raise ValueError("Select a positive interval of at most 31 days")
    models = discover_models(api, start, end, prefixes, exact)
    if not models:
        raise RuntimeError("No OpenRouter models matched the requested LLM prefixes")
    minutes = []
    for hour_start, hour_end in active_hours(api, start, end, models):
        minutes.extend(aggregate(generation_rows(api, hour_start, hour_end, models)))
    return {
        "schema": 1, "scope": scope, "source": "OpenRouter Analytics API",
        "from_utc": iso(start), "to_utc": iso(end),
        "model_prefixes": prefixes, "selected_models": exact, "models": models, "minutes": minutes,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--from-utc", required=True)
    parser.add_argument("--to-utc", required=True)
    parser.add_argument("--model-prefix", action="append", default=[],
                        help="Include only matching LLM models, e.g. openai/gpt-")
    parser.add_argument("--model", action="append", default=[], help="Include this exact LLM model ID")
    parser.add_argument("--scope", choices=["test", "production"], required=True)
    parser.add_argument("--env-file", type=Path, help="Private dotenv with OPENROUTER_MANAGEMENT_KEY")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not args.model_prefix and not args.model:
        parser.error("Specify at least one --model or --model-prefix")
    key = load_key(args.env_file)
    if not key:
        parser.error("OPENROUTER_MANAGEMENT_KEY is missing")
    start, end = utc_minute(args.from_utc), utc_minute(args.to_utc)
    api = Analytics(key)
    result = export(api, start, end, args.model_prefix, args.model, args.scope)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=args.output.parent, delete=False) as tmp:
        json.dump(result, tmp, ensure_ascii=False, separators=(",", ":"))
        tmp.write("\n")
        temporary = tmp.name
    os.replace(temporary, args.output)
    print(f"Saved {len(result['minutes'])} active minutes, {len(result['models'])} LLM models, "
          f"{api.requests} analytics queries to {args.output} (scope={args.scope})")


if __name__ == "__main__":
    main()
