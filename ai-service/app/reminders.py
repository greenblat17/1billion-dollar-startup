from __future__ import annotations

import asyncio
import json
import re
import statistics
import time
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Protocol
from zoneinfo import ZoneInfo

from redis.asyncio import Redis

from app.metrics import (
    FUNNEL_WINDOW_DAYS,
    FUNNEL_WEEK_DAYS,
    METRICS_TIMEZONE,
    MetricsStore,
    RedisMetricsStore,
    metrics_day,
    nonneg_int,
    recent_days,
)

REPLY_WINDOW_SECONDS = 24 * 60 * 60
RUN_LIMIT = 30
REPLY_SAMPLE_LIMIT = 1000
STATUSES = ("sent", "blocked", "failed")
MODES = ("auto", "manual")
SEGMENT_NEW = "new"
SEGMENT_ACTIVE = "active"
UNKNOWN_TEMPLATE = "unknown"
DAY_FIELDS = (
    "sent",
    "blocked",
    "failed",
    "returned",
    "sent_new",
    "sent_active",
    "returned_new",
    "returned_active",
)
TEMPLATE_FIELDS = ("sent", "returned", "blocked")
_TEMPLATE_RE = re.compile(r"^[a-z0-9_]{1,64}$")
_TIME_MAX_CHARS = 40
_TZ = ZoneInfo(METRICS_TIMEZONE)

_RUNS_KEY = "reminder:runs"
_TEMPLATES_KEY = "reminder:templates"


@dataclass(frozen=True)
class ReminderResult:
    session_id: str
    template_id: str
    status: str


@dataclass(frozen=True)
class ReminderReport:
    mode: str
    started_at: str
    finished_at: str
    claimed: int
    results: list[ReminderResult] = field(default_factory=list)


@dataclass
class LastReminder:
    ts: float
    day: str
    template: str
    segment: str
    answered: bool = False
    replied: bool = False


class ReminderLedger(Protocol):
    async def record_report(self, report: ReminderReport, *, now: float | None = None) -> None: ...

    async def record_reply(self, session_id: str, *, now: float | None = None) -> None: ...

    async def snapshot(self, *, now: float | None = None) -> dict[str, Any]: ...

    async def chat_marks(self, sessions: list[str]) -> dict[str, dict[str, Any]]: ...


def build_reminder_ledger(metrics: MetricsStore) -> ReminderLedger:
    if isinstance(metrics, RedisMetricsStore):
        return RedisReminderLedger(metrics.redis, metrics)
    return MemoryReminderLedger(metrics)


def parse_report(payload: dict[str, Any]) -> ReminderReport:
    raw_results = payload.get("results")
    results = []
    for item in raw_results if isinstance(raw_results, list) else []:
        if not isinstance(item, dict):
            continue
        session = str(item.get("sessionId") or "").strip()
        if not session:
            continue
        results.append(
            ReminderResult(
                session_id=session,
                template_id=normalize_template(item.get("templateId")),
                status=normalize_status(item.get("status")),
            ),
        )
    mode = str(payload.get("mode") or "")
    return ReminderReport(
        mode=mode if mode in MODES else "auto",
        started_at=str(payload.get("startedAt") or "")[:_TIME_MAX_CHARS],
        finished_at=str(payload.get("finishedAt") or "")[:_TIME_MAX_CHARS],
        claimed=nonneg_int(payload.get("claimed")),
        results=results,
    )


def normalize_status(value: Any) -> str:
    text = str(value or "")
    return text if text in STATUSES else "failed"


def normalize_template(value: Any) -> str:
    text = str(value or "").strip()
    return text if _TEMPLATE_RE.fullmatch(text) else UNKNOWN_TEMPLATE


def run_row(report: ReminderReport, day: str) -> dict[str, Any]:
    counts = {status: 0 for status in STATUSES}
    for result in report.results:
        counts[result.status] += 1
    return {
        "mode": report.mode,
        "day": day,
        "startedAt": report.started_at,
        "finishedAt": report.finished_at,
        "claimed": report.claimed,
        **counts,
    }


def reply_counts(last: LastReminder, moment: float) -> bool:
    return not last.answered and 0 <= moment - last.ts <= REPLY_WINDOW_SECONDS


def reminder_view(
    *,
    now: float,
    day_counts: dict[str, dict[str, int]],
    template_counts: dict[str, dict[str, int]],
    reply_seconds: list[int],
    runs: list[dict[str, Any]],
) -> dict[str, Any]:
    days = recent_days(now, FUNNEL_WINDOW_DAYS)
    rows = [{"day": day, **{name: day_counts.get(day, {}).get(name, 0) for name in DAY_FIELDS}} for day in days]
    week_rows = rows[:FUNNEL_WEEK_DAYS]
    week = {name: sum(int(row[name]) for row in week_rows) for name in DAY_FIELDS}
    templates = [
        {"templateId": template, **{name: counts.get(name, 0) for name in TEMPLATE_FIELDS}}
        for template, counts in template_counts.items()
        if any(counts.get(name, 0) for name in TEMPLATE_FIELDS)
    ]
    templates.sort(key=lambda row: (-_rate(row["returned"], row["sent"]), -int(row["sent"]), row["templateId"]))
    today = days[0]
    auto_today = next((run for run in runs if run.get("mode") == "auto" and run.get("day") == today), None)
    return {
        "today": {name: rows[0][name] for name in ("sent", "blocked", "failed", "returned")},
        "week": {name: week[name] for name in ("sent", "blocked", "failed", "returned")},
        "days": [{name: row[name] for name in ("day", "sent", "blocked", "failed", "returned")} for row in rows],
        "segments": [
            {"segment": SEGMENT_NEW, "sent": week["sent_new"], "returned": week["returned_new"]},
            {"segment": SEGMENT_ACTIVE, "sent": week["sent_active"], "returned": week["returned_active"]},
        ],
        "templates": templates,
        "replyMedianSeconds": int(statistics.median(reply_seconds)) if reply_seconds else None,
        "runs": runs,
        "autoToday": auto_today,
    }


def _rate(part: Any, total: Any) -> float:
    whole = int(total)
    return int(part) / whole if whole else 0.0


def _moment(now: float | None) -> float:
    return time.time() if now is None else now


def _iso(moment: float) -> str:
    return datetime.fromtimestamp(moment, _TZ).isoformat(timespec="seconds")


class MemoryReminderLedger:
    def __init__(self, metrics: MetricsStore) -> None:
        self._metrics = metrics
        self._last: dict[str, LastReminder] = {}
        self._ignored: dict[str, int] = {}
        self._days: dict[str, dict[str, int]] = {}
        self._templates: dict[str, dict[str, int]] = {}
        self._replies: dict[str, list[int]] = {}
        self._runs: list[dict[str, Any]] = []
        self._lock = asyncio.Lock()

    async def record_report(self, report: ReminderReport, *, now: float | None = None) -> None:
        moment = _moment(now)
        day = metrics_day(moment)
        segments = {
            result.session_id: await self._segment(result.session_id)
            for result in report.results
            if result.status == "sent"
        }
        async with self._lock:
            for result in report.results:
                self._bump(self._days, day, result.status)
                if result.status != "failed":
                    self._bump(self._templates, result.template_id, result.status)
                if result.status != "sent":
                    continue
                segment = segments[result.session_id]
                self._bump(self._days, day, f"sent_{segment}")
                previous = self._last.get(result.session_id)
                if previous is not None and not previous.replied:
                    self._ignored[result.session_id] = self._ignored.get(result.session_id, 0) + 1
                self._last[result.session_id] = LastReminder(moment, day, result.template_id, segment)
            self._runs.insert(0, run_row(report, day))
            del self._runs[RUN_LIMIT:]

    async def record_reply(self, session_id: str, *, now: float | None = None) -> None:
        moment = _moment(now)
        async with self._lock:
            last = self._last.get(session_id)
            if last is None or moment < last.ts:
                return
            last.replied = True
            self._ignored[session_id] = 0
            if not reply_counts(last, moment):
                return
            last.answered = True
            self._bump(self._days, last.day, "returned")
            self._bump(self._days, last.day, f"returned_{last.segment}")
            self._bump(self._templates, last.template, "returned")
            samples = self._replies.setdefault(last.day, [])
            samples.append(int(moment - last.ts))
            del samples[:-REPLY_SAMPLE_LIMIT]

    async def snapshot(self, *, now: float | None = None) -> dict[str, Any]:
        moment = _moment(now)
        async with self._lock:
            week = recent_days(moment, FUNNEL_WEEK_DAYS)
            return reminder_view(
                now=moment,
                day_counts={day: dict(counts) for day, counts in self._days.items()},
                template_counts={name: dict(counts) for name, counts in self._templates.items()},
                reply_seconds=[value for day in week for value in self._replies.get(day, [])],
                runs=[dict(run) for run in self._runs],
            )

    async def chat_marks(self, sessions: list[str]) -> dict[str, dict[str, Any]]:
        async with self._lock:
            marks = {}
            for session in sessions:
                last = self._last.get(session)
                marks[session] = {
                    "lastReminderAt": _iso(last.ts) if last is not None else None,
                    "ignored": self._ignored.get(session, 0),
                }
            return marks

    async def _segment(self, session_id: str) -> str:
        return SEGMENT_ACTIVE if await self._metrics.is_activated(session_id) else SEGMENT_NEW

    @staticmethod
    def _bump(table: dict[str, dict[str, int]], key: str, name: str) -> None:
        counts = table.setdefault(key, {})
        counts[name] = counts.get(name, 0) + 1


class RedisReminderLedger:
    def __init__(self, redis: Redis, metrics: MetricsStore) -> None:
        self._redis = redis
        self._metrics = metrics

    async def record_report(self, report: ReminderReport, *, now: float | None = None) -> None:
        moment = _moment(now)
        day = metrics_day(moment)
        pipe = self._redis.pipeline()
        for result in report.results:
            pipe.hincrby(_day_key(day), result.status, 1)
            if result.status != "failed":
                pipe.hincrby(_template_key(result.template_id), result.status, 1)
                pipe.sadd(_TEMPLATES_KEY, result.template_id)
            if result.status != "sent":
                continue
            segment = SEGMENT_ACTIVE if await self._metrics.is_activated(result.session_id) else SEGMENT_NEW
            pipe.hincrby(_day_key(day), f"sent_{segment}", 1)
            previous = await self._redis.hget(_last_key(result.session_id), "replied")
            if previous == "0":
                pipe.incr(_ignored_key(result.session_id))
            pipe.delete(_last_key(result.session_id))
            pipe.hset(
                _last_key(result.session_id),
                mapping={
                    "ts": str(moment),
                    "day": day,
                    "template": result.template_id,
                    "segment": segment,
                    "answered": "0",
                    "replied": "0",
                },
            )
        pipe.lpush(_RUNS_KEY, json.dumps(run_row(report, day)))
        pipe.ltrim(_RUNS_KEY, 0, RUN_LIMIT - 1)
        await pipe.execute()

    async def record_reply(self, session_id: str, *, now: float | None = None) -> None:
        moment = _moment(now)
        raw = await self._redis.hgetall(_last_key(session_id))
        last = _last_reminder(raw)
        if last is None or moment < last.ts:
            return
        pipe = self._redis.pipeline()
        pipe.hset(_last_key(session_id), "replied", "1")
        pipe.set(_ignored_key(session_id), 0)
        await pipe.execute()
        if not reply_counts(last, moment):
            return
        if await self._redis.hincrby(_last_key(session_id), "answered", 1) != 1:
            return
        pipe = self._redis.pipeline()
        pipe.hincrby(_day_key(last.day), "returned", 1)
        pipe.hincrby(_day_key(last.day), f"returned_{last.segment}", 1)
        pipe.hincrby(_template_key(last.template), "returned", 1)
        pipe.sadd(_TEMPLATES_KEY, last.template)
        pipe.rpush(_reply_key(last.day), int(moment - last.ts))
        pipe.ltrim(_reply_key(last.day), -REPLY_SAMPLE_LIMIT, -1)
        await pipe.execute()

    async def snapshot(self, *, now: float | None = None) -> dict[str, Any]:
        moment = _moment(now)
        days = recent_days(moment, FUNNEL_WINDOW_DAYS)
        week = days[:FUNNEL_WEEK_DAYS]
        templates = sorted(str(item) for item in await self._redis.smembers(_TEMPLATES_KEY))
        pipe = self._redis.pipeline()
        for day in days:
            pipe.hgetall(_day_key(day))
        for template in templates:
            pipe.hgetall(_template_key(template))
        for day in week:
            pipe.lrange(_reply_key(day), 0, -1)
        pipe.lrange(_RUNS_KEY, 0, -1)
        raw = await pipe.execute()
        day_raw = raw[: len(days)]
        template_raw = raw[len(days) : len(days) + len(templates)]
        reply_raw = raw[len(days) + len(templates) : -1]
        return reminder_view(
            now=moment,
            day_counts={day: _counts(item) for day, item in zip(days, day_raw, strict=True)},
            template_counts={name: _counts(item) for name, item in zip(templates, template_raw, strict=True)},
            reply_seconds=[nonneg_int(value) for values in reply_raw for value in values],
            runs=[run for item in raw[-1] if (run := _parse_run(item)) is not None],
        )

    async def chat_marks(self, sessions: list[str]) -> dict[str, dict[str, Any]]:
        if not sessions:
            return {}
        pipe = self._redis.pipeline()
        for session in sessions:
            pipe.hget(_last_key(session), "ts")
            pipe.get(_ignored_key(session))
        raw = await pipe.execute()
        marks = {}
        for index, session in enumerate(sessions):
            ts = raw[index * 2]
            marks[session] = {
                "lastReminderAt": _iso(float(ts)) if ts else None,
                "ignored": nonneg_int(raw[index * 2 + 1]),
            }
        return marks


def _counts(raw: Any) -> dict[str, int]:
    data = raw if isinstance(raw, dict) else {}
    return {str(name): nonneg_int(value) for name, value in data.items()}


def _last_reminder(raw: Any) -> LastReminder | None:
    data = raw if isinstance(raw, dict) else {}
    try:
        ts = float(data["ts"])
    except (KeyError, TypeError, ValueError):
        return None
    return LastReminder(
        ts=ts,
        day=str(data.get("day") or ""),
        template=str(data.get("template") or UNKNOWN_TEMPLATE),
        segment=SEGMENT_ACTIVE if data.get("segment") == SEGMENT_ACTIVE else SEGMENT_NEW,
        answered=nonneg_int(data.get("answered")) > 0,
        replied=data.get("replied") == "1",
    )


def _parse_run(raw: Any) -> dict[str, Any] | None:
    try:
        payload = json.loads(raw)
    except (TypeError, json.JSONDecodeError):
        return None
    return payload if isinstance(payload, dict) else None


def _day_key(day: str) -> str:
    return f"reminder:day:{day}"


def _template_key(template: str) -> str:
    return f"reminder:tpl:{template}"


def _last_key(session: str) -> str:
    return f"reminder:last:{session}"


def _ignored_key(session: str) -> str:
    return f"reminder:ignored:{session}"


def _reply_key(day: str) -> str:
    return f"reminder:reply_seconds:{day}"
