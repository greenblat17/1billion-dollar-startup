from __future__ import annotations

import asyncio
import json
import math
import re
import time
from dataclasses import dataclass, field, replace
from datetime import date, datetime, timedelta
from typing import Any, Protocol
from zoneinfo import ZoneInfo

from redis.asyncio import Redis

METRICS_TIMEZONE = "Europe/Moscow"
LLM_WINDOW_SECONDS = 60
LLM_RETAIN_SECONDS = 120
LLM_PURPOSES = ("reply", "notes", "onboarding", "session_review")
CHAT_LIMIT = 200
FUNNEL_WINDOW_DAYS = 14
FUNNEL_WEEK_DAYS = 7
ERROR_WINDOW_DAYS = 14
ERROR_RETAIN_SECONDS = 30 * 24 * 60 * 60
RECENT_ERROR_LIMIT = 15
ERROR_MESSAGE_MAX_CHARS = 240
ERROR_REASONS = frozenset({"timeout", "rate_limit", "provider_5xx", "provider_4xx", "network", "invalid_input", "internal", "unknown"})
ENGAGED_EXCHANGES = 3
DIRECT_SOURCE = "direct"
USERNAME_MAX_CHARS = 64
NAME_MAX_CHARS = 128
REMINDER_SENT_TTL_SECONDS = 2 * 24 * 60 * 60
REMINDER_GRACE = timedelta(hours=2)

_TZ = ZoneInfo(METRICS_TIMEZONE)
_EVENTS_KEY = "metrics:llm:events"
CORRECTION_OUTCOMES = frozenset({
    "shown", "empty", "filtered", "deadline", "rate_limit", "provider_5xx",
    "provider_4xx", "network", "no_choices", "empty_text", "provider_timeout",
    "invalid_json", "invalid_schema", "token_limit", "other_error",
})
PARTIAL_FEATURES = frozenset({"streak", "call_turn", "call_summary", "assessment", "review", "review_verification", "closing_voice", "closing_callback", "follow_up_card"})
PARTIAL_OUTCOMES = frozenset({"attempted", "succeeded", "failed", "skipped"})
_PARTIAL_RECENT_KEY = "metrics:partial:recent"
PROVIDER_SERVICES = frozenset({"stt", "reply_llm", "tts"})
PROVIDER_NAMES = frozenset({"groq", "deepgram", "openrouter", "openai", "unknown"})
PROVIDER_RESULTS = frozenset({"ok", "timeout", "rate_limit", "provider_5xx", "provider_4xx", "network", "invalid_input", "internal"})
_RECENT_ERRORS_KEY = "metrics:clip-errors:recent"
_CHATS_KEY = "metrics:chats"
_FUNNEL_SOURCES_KEY = "metrics:funnel:sources"
_SOURCE_RE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
# Production Ktor builds ids from tgbotapi ChatId.toString(), e.g. "tg-ChatId(chatId=123)".
_TELEGRAM_SESSION_RE = re.compile(r"tg-(-?\d+)|tg-ChatId\(chatId=(-?\d+)\)")
_CLOCK_RE = re.compile(r"(\d{1,2}):(\d{2})")


@dataclass(frozen=True)
class MetricRates:
    prompt_rub_per_million: float | None = None
    completion_rub_per_million: float | None = None
    stt_rub_per_minute: float | None = None
    tts_rub_per_million_chars: float | None = None

    @property
    def configured(self) -> bool:
        return (
            self.prompt_rub_per_million is not None
            and self.completion_rub_per_million is not None
            and self.stt_rub_per_minute is not None
            and self.tts_rub_per_million_chars is not None
        )


# List prices in USD. The page shows rubles at a fixed rate and does not fetch FX.
RUB_PER_USD = 90.0
LLM_PROMPT_USD_PER_MILLION = 0.20  # OpenRouter openai/gpt-5.6-luna input
LLM_COMPLETION_USD_PER_MILLION = 1.20  # OpenRouter openai/gpt-5.6-luna output
STT_USD_PER_HOUR = 0.111  # Groq whisper-large-v3
TTS_USD_PER_MILLION_CHARS = 0.62  # OpenRouter hexgrad/kokoro-82m

DEFAULT_RATES = MetricRates(
    prompt_rub_per_million=LLM_PROMPT_USD_PER_MILLION * RUB_PER_USD,
    completion_rub_per_million=LLM_COMPLETION_USD_PER_MILLION * RUB_PER_USD,
    stt_rub_per_minute=STT_USD_PER_HOUR / 60 * RUB_PER_USD,
    tts_rub_per_million_chars=TTS_USD_PER_MILLION_CHARS * RUB_PER_USD,
)


@dataclass
class DayTotals:
    prompt_tokens: int = 0
    completion_tokens: int = 0
    llm_requests: int = 0
    llm_failures: int = 0
    llm_by_purpose: dict[str, int] = field(default_factory=dict)
    stt_ms: int = 0
    tts_chars: int = 0
    turns: int = 0


@dataclass(frozen=True)
class LlmSample:
    ts: float
    prompt_tokens: int
    completion_tokens: int
    elapsed_ms: int


@dataclass(frozen=True)
class ChatRow:
    session_id: str
    turns: int
    last_unix: float
    username: str = ""
    name: str = ""


@dataclass(frozen=True)
class ChatProfile:
    username: str = ""
    name: str = ""


@dataclass(frozen=True)
class ReminderTarget:
    session_id: str
    name: str = ""


@dataclass
class FunnelUser:
    source: str = ""
    first_day: str = ""
    start_day: str = ""
    activated_day: str = ""
    engaged_day: str = ""
    exchanges: int = 0
    last_return_day: str = ""


@dataclass
class FunnelCounts:
    start: int = 0
    activated: int = 0
    engaged: int = 0
    returned: int = 0


@dataclass
class FunnelDelta:
    day: str
    source: str
    start: int = 0
    activated: int = 0
    engaged: int = 0
    returned: int = 0


class MetricsStore(Protocol):
    async def record_provider(self, service: str, kind: str, result: str, *, provider: str = "unknown", now: float | None = None) -> None: ...
    async def record_partial(self, feature: str, outcome: str, *, reason: str = "unknown", now: float | None = None) -> None: ...
    async def record_correction(self, outcome: str, elapsed_ms: int, attempts: int = 0, *, now: float | None = None) -> None: ...
    async def record_clip_result(
        self,
        error_code: str | None,
        *,
        session_id: str | None = None,
        stage: str | None = None,
        message: str | None = None,
        job_id: str | None = None,
        attempt_id: str | None = None,
        reason: str | None = None,
        now: float | None = None,
    ) -> None: ...

    async def record_llm(
        self,
        prompt_tokens: int,
        completion_tokens: int,
        elapsed_ms: int,
        *,
        purpose: str = "reply",
        success: bool = True,
        now: float | None = None,
    ) -> None: ...

    async def record_turn(
        self,
        session_id: str,
        stt_seconds: float,
        tts_chars: int,
        *,
        now: float | None = None,
    ) -> None: ...

    async def record_start(
        self,
        session_id: str,
        source: str | None = None,
        *,
        now: float | None = None,
    ) -> None: ...

    async def record_voice(self, session_id: str, *, now: float | None = None) -> None: ...

    async def record_exchange(self, session_id: str, *, now: float | None = None) -> None: ...

    async def record_profile(self, session_id: str, username: str | None, name: str | None) -> None: ...

    async def snapshot(self, *, now: float | None = None) -> dict[str, Any]: ...

    async def llm_range(self, from_day: date, to_day: date) -> dict[str, Any]: ...

    async def claim_reminders(self, *, now: float | None = None, mode: str = "auto") -> list[ReminderTarget]: ...

    async def reminder_forecast(self, *, now: float | None = None) -> int: ...

    async def schedule_reminder(
        self,
        session_id: str,
        action: str,
        *,
        text: str | None = None,
        run_id: str = "",
    ) -> dict[str, str]: ...

    async def reminder_time(self, session_id: str) -> str | None: ...

    async def reminder_summary(self) -> dict[str, Any]: ...

    async def is_known(self, session_id: str) -> bool: ...

    async def is_activated(self, session_id: str) -> bool: ...

    async def aclose(self) -> None: ...


class MemoryMetricsStore:
    def __init__(self, rates: MetricRates) -> None:
        self._rates = rates
        self._days: dict[str, DayTotals] = {}
        self._corrections: dict[str, dict[str, dict[str, int]]] = {}
        self._clip_results: dict[str, dict[str, int]] = {}
        self._clip_reasons: dict[str, dict[str, int]] = {}
        self._partial: dict[str, dict[str, int]] = {}
        self._partial_recent: list[dict[str, Any]] = []
        self._provider: dict[str, dict[str, int]] = {}
        self._recent_errors: list[dict[str, Any]] = []
        self._dau: dict[str, set[str]] = {}
        self._samples: list[LlmSample] = []
        self._chats: dict[str, ChatRow] = {}
        self._profiles: dict[str, ChatProfile] = {}
        self._funnel_users: dict[str, FunnelUser] = {}
        self._funnel_days: dict[str, FunnelCounts] = {}
        self._funnel_sources: dict[tuple[str, str], FunnelCounts] = {}
        self._reminded: set[tuple[str, str]] = set()
        self._reminder_times: dict[str, str] = {}
        self._reminder_pending: dict[str, str] = {}
        self._lock = asyncio.Lock()

    async def record_llm(
        self,
        prompt_tokens: int,
        completion_tokens: int,
        elapsed_ms: int,
        *,
        purpose: str = "reply",
        success: bool = True,
        now: float | None = None,
    ) -> None:
        if purpose not in LLM_PURPOSES:
            raise ValueError(f"unknown LLM purpose: {purpose}")
        moment = _moment(now)
        sample = LlmSample(
            ts=moment,
            prompt_tokens=nonneg_int(prompt_tokens) if success else 0,
            completion_tokens=nonneg_int(completion_tokens) if success else 0,
            elapsed_ms=nonneg_int(elapsed_ms),
        )
        async with self._lock:
            day = self._days.setdefault(metrics_day(moment), DayTotals())
            day.prompt_tokens += sample.prompt_tokens
            day.completion_tokens += sample.completion_tokens
            day.llm_requests += 1
            day.llm_failures += int(not success)
            day.llm_by_purpose[purpose] = day.llm_by_purpose.get(purpose, 0) + 1
            if success:
                self._samples.append(sample)
                self._samples = [item for item in self._samples if moment - item.ts <= LLM_RETAIN_SECONDS]

    async def record_correction(self, outcome: str, elapsed_ms: int, attempts: int = 0, *, now: float | None = None) -> None:
        _check_correction_outcome(outcome)
        _check_correction_attempts(attempts)
        async with self._lock:
            counters = self._corrections.setdefault(metrics_day(_moment(now)), {})
            item = counters.setdefault(outcome, {"count": 0, "elapsedMs": 0, "secondAttempts": 0})
            item["count"] += 1
            item["elapsedMs"] += nonneg_int(elapsed_ms)
            item["secondAttempts"] += int(attempts == 2)

    async def record_clip_result(
        self,
        error_code: str | None,
        *,
        session_id: str | None = None,
        stage: str | None = None,
        message: str | None = None,
        job_id: str | None = None,
        attempt_id: str | None = None,
        reason: str | None = None,
        now: float | None = None,
    ) -> None:
        moment = _moment(now)
        day_name = metrics_day(moment)
        field = _clip_result_field(error_code)
        async with self._lock:
            counts = self._clip_results.setdefault(day_name, {})
            counts[field] = counts.get(field, 0) + 1
            if error_code is not None:
                reason_key = _clip_reason_key(stage, reason)
                reasons = self._clip_reasons.setdefault(day_name, {})
                reasons[reason_key] = reasons.get(reason_key, 0) + 1
                username = self._profiles.get(session_id or "", ChatProfile()).username
                telegram_id = telegram_chat_id(session_id or "")
                self._recent_errors.insert(0, _error_event(moment, error_code, stage, message, username, telegram_id,
                                                         job_id, attempt_id, reason))
                self._recent_errors = self._recent_errors[:RECENT_ERROR_LIMIT]

    async def record_partial(self, feature: str, outcome: str, *, reason: str = "unknown", now: float | None = None) -> None:
        _check_partial(feature, outcome)
        moment = _moment(now)
        async with self._lock:
            counts = self._partial.setdefault(metrics_day(moment), {})
            key = f"{feature}:{outcome}"
            counts[key] = counts.get(key, 0) + 1
            if outcome == "failed":
                self._partial_recent.insert(0, _partial_event(moment, feature, reason))
                self._partial_recent = self._partial_recent[:RECENT_ERROR_LIMIT]

    async def record_provider(self, service: str, kind: str, result: str, *, provider: str = "unknown", now: float | None = None) -> None:
        _check_provider(service, kind, result, provider)
        async with self._lock:
            counts = self._provider.setdefault(metrics_day(_moment(now)), {})
            key = f"{service}:{provider}:{kind}:{result}"
            counts[key] = counts.get(key, 0) + 1

    async def record_turn(
        self,
        session_id: str,
        stt_seconds: float,
        tts_chars: int,
        *,
        now: float | None = None,
    ) -> None:
        session = session_id.strip()
        if not session:
            return
        moment = _moment(now)
        async with self._lock:
            self._add_turn(metrics_day(moment), session, seconds_to_ms(stt_seconds), nonneg_int(tts_chars), moment)

    async def record_start(
        self,
        session_id: str,
        source: str | None = None,
        *,
        now: float | None = None,
    ) -> None:
        await self._record_funnel(session_id, "start", source, now)

    async def record_voice(self, session_id: str, *, now: float | None = None) -> None:
        await self._record_funnel(session_id, "voice", None, now)

    async def record_exchange(self, session_id: str, *, now: float | None = None) -> None:
        await self._record_funnel(session_id, "exchange", None, now)

    async def record_profile(self, session_id: str, username: str | None, name: str | None) -> None:
        session = session_id.strip()
        if not session:
            return
        async with self._lock:
            self._profiles[session] = normalize_profile(username, name)

    async def snapshot(self, *, now: float | None = None) -> dict[str, Any]:
        moment = _moment(now)
        async with self._lock:
            day_name = metrics_day(moment)
            chats = []
            for row in self._chats.values():
                profile = self._profiles.get(row.session_id, ChatProfile())
                chats.append(replace(row, username=profile.username, name=profile.name))
            payload = build_snapshot(
                now=moment,
                day=self._days.get(day_name, DayTotals()),
                dau=len(self._dau.get(day_name, ())),
                samples=list(self._samples),
                chats=chats,
                rates=self._rates,
            )
            payload.update(funnel_view(moment, self._funnel_days, self._funnel_sources))
            payload["corrections"] = {key: value.copy() for key, value in self._corrections.get(day_name, {}).items()}
            payload["errors"] = error_view(moment, self._clip_results, self._recent_errors, self._clip_reasons)
            payload["partialFailures"] = self._partial.get(day_name, {}).copy()
            payload["partialRecent"] = _partial_recent_view(moment, self._partial_recent)
            payload["providerOutcomes"] = self._provider.get(day_name, {}).copy()
            return payload

    async def llm_range(self, from_day: date, to_day: date) -> dict[str, Any]:
        async with self._lock:
            days = [self._days.get(day.isoformat(), DayTotals()) for day in llm_days(from_day, to_day)]
            return llm_range_snapshot(from_day, to_day, days)

    async def schedule_reminder(
        self,
        session_id: str,
        action: str,
        *,
        text: str | None = None,
        run_id: str = "",
    ) -> dict[str, str]:
        session = session_id.strip()
        if not session:
            raise ValueError("session id required")
        async with self._lock:
            decision = schedule_decision(action, self._reminder_pending.get(session), text, run_id)
            self._apply_schedule(session, decision)
            return public_schedule(decision)

    async def reminder_time(self, session_id: str) -> str | None:
        session = session_id.strip()
        if not session:
            return None
        async with self._lock:
            return self._reminder_times.get(session)

    async def reminder_summary(self) -> dict[str, Any]:
        async with self._lock:
            hours = empty_reminder_hours()
            for session, clock in self._reminder_times.items():
                add_reminder_hour(hours, session, clock)
            return reminder_summary(hours)

    def _apply_schedule(self, session: str, decision: dict[str, str]) -> None:
        if "write_pending" in decision:
            if decision["write_pending"]:
                self._reminder_pending[session] = decision["write_pending"]
            else:
                self._reminder_pending.pop(session, None)
        if decision.get("clear_time"):
            self._reminder_times.pop(session, None)
        elif decision.get("time"):
            self._reminder_times[session] = decision["time"]

    async def claim_reminders(self, *, now: float | None = None, mode: str = "auto") -> list[ReminderTarget]:
        moment = _moment(now)
        day_name = metrics_day(moment)
        async with self._lock:
            targets = []
            for session in self._opted_in(day_name, moment, mode):
                if (day_name, session) in self._reminded:
                    continue
                self._reminded.add((day_name, session))
                targets.append(ReminderTarget(session, self._profiles.get(session, ChatProfile()).name))
            return targets

    async def reminder_forecast(self, *, now: float | None = None) -> int:
        moment = _moment(now)
        day_name = metrics_day(moment)
        async with self._lock:
            return sum(
                1
                for session in self._opted_in(day_name, moment, "manual")
                if (day_name, session) not in self._reminded
            )

    async def is_known(self, session_id: str) -> bool:
        async with self._lock:
            return session_id in self._funnel_users or session_id in self._chats

    async def is_activated(self, session_id: str) -> bool:
        async with self._lock:
            user = self._funnel_users.get(session_id)
            return bool(user and user.activated_day)

    async def aclose(self) -> None:
        return None

    def _opted_in(self, day_name: str, moment: float, mode: str) -> list[str]:
        return opted_reminder_sessions(
            self._reminder_times,
            self._dau.get(day_name, set()),
            moment,
            mode,
        )

    def _add_turn(self, day_name: str, session: str, stt_ms: int, tts_chars: int, moment: float) -> None:
        day = self._days.setdefault(day_name, DayTotals())
        day.stt_ms += stt_ms
        day.tts_chars += tts_chars
        day.turns += 1
        self._dau.setdefault(day_name, set()).add(session)
        previous = self._chats.get(session)
        turns = (previous.turns if previous is not None else 0) + 1
        self._chats[session] = ChatRow(session_id=session, turns=turns, last_unix=moment)

    async def _record_funnel(
        self,
        session_id: str,
        kind: str,
        source: str | None,
        now: float | None,
    ) -> None:
        session = session_id.strip()
        if not session:
            return
        moment = _moment(now)
        async with self._lock:
            user = self._funnel_users.setdefault(session, FunnelUser())
            delta = apply_funnel(user, kind=kind, source=normalize_source(source), day=metrics_day(moment))
            _bump_funnel(self._funnel_days, self._funnel_sources, delta)


class RedisMetricsStore:
    def __init__(self, redis: Redis, rates: MetricRates) -> None:
        self._redis = redis
        self._rates = rates
        self._lock = asyncio.Lock()

    async def record_clip_result(
        self,
        error_code: str | None,
        *,
        session_id: str | None = None,
        stage: str | None = None,
        message: str | None = None,
        job_id: str | None = None,
        attempt_id: str | None = None,
        reason: str | None = None,
        now: float | None = None,
    ) -> None:
        moment = _moment(now)
        key = _clip_results_key(metrics_day(moment))
        pipe = self._redis.pipeline()
        field = _clip_result_field(error_code)
        pipe.hincrby(key, field, 1)
        pipe.expire(key, ERROR_RETAIN_SECONDS)
        if error_code is not None:
            reasons_key = _clip_reasons_key(metrics_day(moment))
            pipe.hincrby(reasons_key, _clip_reason_key(stage, reason), 1)
            pipe.expire(reasons_key, ERROR_RETAIN_SECONDS)
            username = await self._redis.hget(_chat_key(session_id), "username") if session_id else None
            telegram_id = telegram_chat_id(session_id or "")
            pipe.lpush(_RECENT_ERRORS_KEY, json.dumps(_error_event(moment, error_code, stage, message, username,
                                                                  telegram_id, job_id, attempt_id, reason)))
            pipe.ltrim(_RECENT_ERRORS_KEY, 0, RECENT_ERROR_LIMIT - 1)
            pipe.expire(_RECENT_ERRORS_KEY, ERROR_RETAIN_SECONDS)
        await pipe.execute()

    async def record_partial(self, feature: str, outcome: str, *, reason: str = "unknown", now: float | None = None) -> None:
        _check_partial(feature, outcome)
        key = _partial_key(metrics_day(_moment(now)))
        pipe = self._redis.pipeline()
        pipe.hincrby(key, f"{feature}:{outcome}", 1)
        pipe.expire(key, ERROR_RETAIN_SECONDS)
        if outcome == "failed":
            pipe.lpush(_PARTIAL_RECENT_KEY, json.dumps(_partial_event(_moment(now), feature, reason)))
            pipe.ltrim(_PARTIAL_RECENT_KEY, 0, RECENT_ERROR_LIMIT - 1)
            pipe.expire(_PARTIAL_RECENT_KEY, ERROR_RETAIN_SECONDS)
        await pipe.execute()

    async def record_provider(self, service: str, kind: str, result: str, *, provider: str = "unknown", now: float | None = None) -> None:
        _check_provider(service, kind, result, provider)
        key = _provider_key(metrics_day(_moment(now)))
        pipe = self._redis.pipeline()
        pipe.hincrby(key, f"{service}:{provider}:{kind}:{result}", 1)
        pipe.expire(key, ERROR_RETAIN_SECONDS)
        await pipe.execute()

    async def record_llm(
        self,
        prompt_tokens: int,
        completion_tokens: int,
        elapsed_ms: int,
        *,
        purpose: str = "reply",
        success: bool = True,
        now: float | None = None,
    ) -> None:
        if purpose not in LLM_PURPOSES:
            raise ValueError(f"unknown LLM purpose: {purpose}")
        moment = _moment(now)
        prompt = nonneg_int(prompt_tokens) if success else 0
        completion = nonneg_int(completion_tokens) if success else 0
        elapsed = nonneg_int(elapsed_ms)
        payload = json.dumps(
            {"ts": moment, "prompt": prompt, "completion": completion, "elapsed_ms": elapsed},
        )
        async with self._lock:
            pipe = self._redis.pipeline()
            day_key = _day_key(metrics_day(moment))
            pipe.hincrby(day_key, "llm_requests", 1)
            pipe.hincrby(day_key, f"llm_{purpose}_requests", 1)
            if success:
                pipe.hincrby(day_key, "prompt_tokens", prompt)
                pipe.hincrby(day_key, "completion_tokens", completion)
                pipe.lpush(_EVENTS_KEY, payload)
            else:
                pipe.hincrby(day_key, "llm_failures", 1)
            await pipe.execute()
            if success:
                await self._prune_events(moment)

    async def record_correction(self, outcome: str, elapsed_ms: int, attempts: int = 0, *, now: float | None = None) -> None:
        _check_correction_outcome(outcome)
        _check_correction_attempts(attempts)
        pipe = self._redis.pipeline()
        key = _correction_key(metrics_day(_moment(now)))
        pipe.hincrby(key, f"{outcome}:count", 1)
        pipe.hincrby(key, f"{outcome}:elapsed_ms", nonneg_int(elapsed_ms))
        pipe.hincrby(key, f"{outcome}:second_attempts", int(attempts == 2))
        await pipe.execute()

    async def record_turn(
        self,
        session_id: str,
        stt_seconds: float,
        tts_chars: int,
        *,
        now: float | None = None,
    ) -> None:
        session = session_id.strip()
        if not session:
            return
        moment = _moment(now)
        day_name = metrics_day(moment)
        async with self._lock:
            pipe = self._redis.pipeline()
            day_key = _day_key(day_name)
            pipe.hincrby(day_key, "stt_ms", seconds_to_ms(stt_seconds))
            pipe.hincrby(day_key, "tts_chars", nonneg_int(tts_chars))
            pipe.hincrby(day_key, "turns", 1)
            pipe.sadd(_dau_key(day_name), session)
            pipe.hincrby(_chat_key(session), "turns", 1)
            pipe.zadd(_CHATS_KEY, {session: moment})
            await pipe.execute()

    async def record_start(
        self,
        session_id: str,
        source: str | None = None,
        *,
        now: float | None = None,
    ) -> None:
        await self._record_funnel(session_id, "start", source, now)

    async def record_voice(self, session_id: str, *, now: float | None = None) -> None:
        await self._record_funnel(session_id, "voice", None, now)

    async def record_exchange(self, session_id: str, *, now: float | None = None) -> None:
        await self._record_funnel(session_id, "exchange", None, now)

    async def record_profile(self, session_id: str, username: str | None, name: str | None) -> None:
        session = session_id.strip()
        if not session:
            return
        profile = normalize_profile(username, name)
        await self._redis.hset(
            _chat_key(session),
            mapping={"username": profile.username, "name": profile.name},
        )

    async def snapshot(self, *, now: float | None = None) -> dict[str, Any]:
        moment = _moment(now)
        day_name = metrics_day(moment)
        async with self._lock:
            totals = await self._redis.hgetall(_day_key(day_name))
            dau = int(await self._redis.scard(_dau_key(day_name)))
            raw_events = await self._redis.lrange(_EVENTS_KEY, 0, -1)
            ranked = await self._redis.zrevrange(_CHATS_KEY, 0, CHAT_LIMIT - 1, withscores=True)
            chats = await self._chat_rows(ranked)
        payload = build_snapshot(
            now=moment,
            day=_day_totals(totals),
            dau=dau,
            samples=[sample for item in raw_events if (sample := _parse_sample(item)) is not None],
            chats=chats,
            rates=self._rates,
        )
        payload.update(await self._funnel_snapshot(moment))
        payload["corrections"] = _correction_counts(await self._redis.hgetall(_correction_key(day_name)))
        payload["partialFailures"] = {
            key: nonneg_int(value) for key, value in (await self._redis.hgetall(_partial_key(day_name))).items()
        }
        recent_partial = [_parse_error_event(item) for item in await self._redis.lrange(_PARTIAL_RECENT_KEY, 0, RECENT_ERROR_LIMIT - 1)]
        payload["partialRecent"] = _partial_recent_view(moment, [item for item in recent_partial if item is not None])
        payload["providerOutcomes"] = {
            key: nonneg_int(value) for key, value in (await self._redis.hgetall(_provider_key(day_name))).items()
        }
        days = recent_days(moment, ERROR_WINDOW_DAYS)
        pipe = self._redis.pipeline()
        for day in days:
            pipe.hgetall(_clip_results_key(day))
            pipe.hgetall(_clip_reasons_key(day))
        raw = await pipe.execute()
        daily = dict(zip(days, raw[::2], strict=True))
        reasons = dict(zip(days, raw[1::2], strict=True))
        recent = [_parse_error_event(item) for item in await self._redis.lrange(_RECENT_ERRORS_KEY, 0, RECENT_ERROR_LIMIT - 1)]
        payload["errors"] = error_view(moment, daily, [item for item in recent if item is not None], reasons)
        return payload

    async def llm_range(self, from_day: date, to_day: date) -> dict[str, Any]:
        pipe = self._redis.pipeline()
        for day in llm_days(from_day, to_day):
            pipe.hgetall(_day_key(day.isoformat()))
        raw_days = await pipe.execute()
        return llm_range_snapshot(from_day, to_day, [_day_totals(raw) for raw in raw_days])

    @property
    def redis(self) -> Redis:
        return self._redis

    async def schedule_reminder(
        self,
        session_id: str,
        action: str,
        *,
        text: str | None = None,
        run_id: str = "",
    ) -> dict[str, str]:
        session = session_id.strip()
        if not session:
            raise ValueError("session id required")
        pending = await self._redis.get(_reminder_pending_key(session))
        decision = schedule_decision(action, pending, text, run_id)
        if "write_pending" in decision:
            if decision["write_pending"]:
                await self._redis.set(_reminder_pending_key(session), decision["write_pending"])
            else:
                await self._redis.delete(_reminder_pending_key(session))
        if decision.get("clear_time"):
            await self._redis.delete(_reminder_time_key(session))
        elif decision.get("time"):
            await self._redis.set(_reminder_time_key(session), decision["time"])
        return public_schedule(decision)

    async def reminder_time(self, session_id: str) -> str | None:
        session = session_id.strip()
        if not session:
            return None
        value = await self._redis.get(_reminder_time_key(session))
        return str(value) if value else None

    async def reminder_summary(self) -> dict[str, Any]:
        hours = empty_reminder_hours()
        prefix = _reminder_time_key("")
        keys: list[str] = []
        seen: set[str] = set()

        async def count_batch() -> None:
            if not keys:
                return
            values = await self._redis.mget(keys)
            for key, value in zip(keys, values):
                if value:
                    add_reminder_hour(hours, key.removeprefix(prefix), str(value))
            keys.clear()

        async for key in self._redis.scan_iter(match=f"{prefix}*", count=200):
            key = str(key)
            if key in seen:
                continue
            seen.add(key)
            keys.append(key)
            if len(keys) == 200:
                await count_batch()
        await count_batch()
        return reminder_summary(hours)

    async def claim_reminders(self, *, now: float | None = None, mode: str = "auto") -> list[ReminderTarget]:
        moment = _moment(now)
        day_name = metrics_day(moment)
        targets = []
        for session in await self._opted_in(day_name, moment, mode):
            claimed = await self._redis.set(
                _reminder_sent_key(day_name, session),
                "1",
                nx=True,
                ex=REMINDER_SENT_TTL_SECONDS,
            )
            if not claimed:
                continue
            name = await self._redis.hget(_chat_key(session), "name")
            targets.append(ReminderTarget(session, name or ""))
        return targets

    async def reminder_forecast(self, *, now: float | None = None) -> int:
        moment = _moment(now)
        day_name = metrics_day(moment)
        sessions = await self._opted_in(day_name, moment, "manual")
        if not sessions:
            return 0
        pipe = self._redis.pipeline()
        for session in sessions:
            pipe.exists(_reminder_sent_key(day_name, session))
        return sum(1 for sent in await pipe.execute() if not sent)

    async def is_known(self, session_id: str) -> bool:
        return bool(await self._redis.exists(_funnel_user_key(session_id))) or await self._redis.zscore(_CHATS_KEY, session_id) is not None

    async def is_activated(self, session_id: str) -> bool:
        return bool(await self._redis.hget(_funnel_user_key(session_id), "activated_day"))

    async def _opted_in(self, day_name: str, moment: float, mode: str) -> list[str]:
        times: dict[str, str] = {}
        prefix = _reminder_time_key("")
        async for key in self._redis.scan_iter(match=f"{prefix}*"):
            session = str(key).removeprefix(prefix)
            value = await self._redis.get(key)
            if value:
                times[session] = str(value)
        active = {str(member) for member in await self._redis.smembers(_dau_key(day_name))}
        return opted_reminder_sessions(times, active, moment, mode)

    async def aclose(self) -> None:
        await self._redis.aclose()

    async def _record_funnel(
        self,
        session_id: str,
        kind: str,
        source: str | None,
        now: float | None,
    ) -> None:
        session = session_id.strip()
        if not session:
            return
        moment = _moment(now)
        day = metrics_day(moment)
        async with self._lock:
            user = _funnel_user(await self._redis.hgetall(_funnel_user_key(session)))
            delta = apply_funnel(user, kind=kind, source=normalize_source(source), day=day)
            await self._redis.hset(_funnel_user_key(session), mapping=_funnel_user_mapping(user))
            await self._persist_delta(delta)

    async def _persist_delta(self, delta: FunnelDelta) -> None:
        if not _delta_counts(delta):
            return
        pipe = self._redis.pipeline()
        for field, amount in _delta_counts(delta):
            pipe.hincrby(_funnel_day_key(delta.day), field, amount)
            pipe.hincrby(_funnel_source_key(delta.day, delta.source), field, amount)
        pipe.sadd(_FUNNEL_SOURCES_KEY, delta.source)
        await pipe.execute()

    async def _funnel_snapshot(self, moment: float) -> dict[str, Any]:
        days = recent_days(moment, FUNNEL_WINDOW_DAYS)
        sources = sorted(str(item) for item in await self._redis.smembers(_FUNNEL_SOURCES_KEY))
        pipe = self._redis.pipeline()
        for day in days:
            pipe.hgetall(_funnel_day_key(day))
        for source in sources:
            for day in days:
                pipe.hgetall(_funnel_source_key(day, source))
        raw = await pipe.execute()
        day_counts = {day: _funnel_counts(raw[index]) for index, day in enumerate(days)}
        source_counts: dict[tuple[str, str], FunnelCounts] = {}
        cursor = len(days)
        for source in sources:
            for day in days:
                source_counts[(day, source)] = _funnel_counts(raw[cursor])
                cursor += 1
        return funnel_view(moment, day_counts, source_counts)

    async def _prune_events(self, moment: float) -> None:
        raw_events = await self._redis.lrange(_EVENTS_KEY, 0, -1)
        fresh = []
        for item in raw_events:
            sample = _parse_sample(item)
            if sample is not None and moment - sample.ts <= LLM_RETAIN_SECONDS:
                fresh.append(item if isinstance(item, str) else str(item))
        pipe = self._redis.pipeline()
        pipe.delete(_EVENTS_KEY)
        if fresh:
            pipe.rpush(_EVENTS_KEY, *fresh)
        await pipe.execute()

    async def _chat_rows(self, ranked: Any) -> list[ChatRow]:
        members = list(ranked or [])
        if not members:
            return []
        pipe = self._redis.pipeline()
        parsed: list[tuple[str, float]] = []
        for member, score in members:
            session = member if isinstance(member, str) else str(member)
            parsed.append((session, float(score)))
            pipe.hmget(_chat_key(session), "turns", "username", "name")
        fields = await pipe.execute()
        return [
            ChatRow(
                session_id=session,
                turns=nonneg_int(turn_count),
                last_unix=score,
                username=username or "",
                name=name or "",
            )
            for (session, score), (turn_count, username, name) in zip(parsed, fields, strict=True)
        ]


def build_metrics_store(settings: Any, redis: Redis | None = None) -> MetricsStore:
    if redis is not None:
        return RedisMetricsStore(redis, DEFAULT_RATES)
    url = getattr(settings, "redis_url", None)
    if url:
        return RedisMetricsStore(Redis.from_url(url, decode_responses=True), DEFAULT_RATES)
    return MemoryMetricsStore(DEFAULT_RATES)


def metrics_day(moment: float) -> str:
    return datetime.fromtimestamp(moment, _TZ).date().isoformat()


def recent_days(moment: float, count: int) -> list[str]:
    current = datetime.fromtimestamp(moment, _TZ).date()
    return [(current - timedelta(days=offset)).isoformat() for offset in range(count)]


def _clip_result_field(error_code: str | None) -> str:
    if error_code is None:
        return "ok"
    return error_code if error_code in {"timeout", "pipeline_failed"} else "pipeline_failed"


def _clip_reason_key(stage: str | None, reason: str | None) -> str:
    safe_stage = stage if stage in {"stt", "llm", "tts", "state", "metrics", "onboarding"} else "unknown"
    safe_reason = reason if reason in ERROR_REASONS else "unknown"
    return f"{safe_stage}:{safe_reason}"


def _error_event(
    moment: float,
    code: str,
    stage: str | None,
    message: str | None,
    username: str | None,
    telegram_id: int | None,
    job_id: str | None = None,
    attempt_id: str | None = None,
    reason: str | None = None,
) -> dict[str, Any]:
    safe_code = code if code in {"timeout", "pipeline_failed", "onboarding_stt_failed"} else "pipeline_failed"
    safe_stage = stage if stage in {"stt", "llm", "tts", "state", "metrics", "onboarding"} else "unknown"
    text = " ".join((message or "").split())
    text = re.sub(r"(?i)\b(bearer\s+)\S+", r"\1[redacted]", text)
    text = re.sub(r"(?i)\b(?:sk|gsk)[-_][A-Za-z0-9_-]{8,}\b", "[redacted]", text)
    text = re.sub(
        r"""(?i)(\b(?:api[_-]?key|access[_-]?token|token|password|secret)\b\s*[:=]\s*["']?)[^\s,;}"']+""",
        r"\1[redacted]",
        text,
    )
    text = _TELEGRAM_SESSION_RE.sub("[session]", text)
    return {
        "ts": moment,
        "code": safe_code,
        "stage": safe_stage,
        "reason": reason if reason in ERROR_REASONS else "unknown",
        "message": text[:ERROR_MESSAGE_MAX_CHARS] or "—",
        "username": normalize_profile(username, None).username,
        "telegramId": telegram_id,
        "jobId": job_id or "",
        "attemptId": attempt_id or "",
    }


def _partial_event(moment: float, feature: str, reason: str) -> dict[str, Any]:
    return {"ts": moment, "feature": feature, "reason": reason if reason in ERROR_REASONS else "unknown"}


def _partial_recent_view(moment: float, rows: list[dict[str, Any]]) -> list[dict[str, str]]:
    return [
        {"at": datetime.fromtimestamp(item["ts"], _TZ).isoformat(timespec="seconds"),
         "feature": str(item.get("feature") or "unknown"), "reason": str(item.get("reason") or "unknown")}
        for item in rows if isinstance(item.get("ts"), (int, float)) and 0 <= moment - item["ts"] <= ERROR_RETAIN_SECONDS
    ]


def _parse_error_event(raw: Any) -> dict[str, Any] | None:
    try:
        value = json.loads(raw)
        if not isinstance(value, dict) or not isinstance(value.get("ts"), (int, float)):
            return None
        return value
    except (TypeError, ValueError):
        return None


def error_view(
    moment: float,
    daily: dict[str, dict[str, Any]],
    recent: list[dict[str, Any]] | None = None,
    reasons: dict[str, dict[str, Any]] | None = None,
) -> dict[str, Any]:
    rows = []
    for day in recent_days(moment, ERROR_WINDOW_DAYS):
        counts = daily.get(day, {})
        ok = nonneg_int(counts.get("ok"))
        timeout = nonneg_int(counts.get("timeout"))
        pipeline_failed = nonneg_int(counts.get("pipeline_failed"))
        rows.append({"day": day, "ok": ok, "timeout": timeout, "pipelineFailed": pipeline_failed})
    return {
        "today": rows[0],
        "days": rows,
        "reasons": [
            {"stage": key.partition(":")[0], "reason": key.partition(":")[2], "count": nonneg_int(value)}
            for day in recent_days(moment, ERROR_WINDOW_DAYS)
            for key, value in (reasons or {}).get(day, {}).items()
        ],
        "recent": [
            {
                "at": datetime.fromtimestamp(item["ts"], _TZ).isoformat(timespec="seconds"),
                "code": item["code"],
                "stage": item["stage"],
                "reason": item.get("reason", "unknown"),
                "message": item["message"],
                "username": item.get("username", ""),
                "telegramId": item.get("telegramId"),
                "jobId": item.get("jobId", ""),
                "attemptId": item.get("attemptId", ""),
            }
            for item in sorted(recent or [], key=lambda entry: entry["ts"], reverse=True)
            if 0 <= moment - item["ts"] <= ERROR_RETAIN_SECONDS
        ],
    }


def normalize_source(value: str | None) -> str | None:
    if value is None:
        return None
    text = value.strip()
    if _SOURCE_RE.fullmatch(text) is None:
        return None
    return text


def normalize_profile(username: str | None, name: str | None) -> ChatProfile:
    return ChatProfile(
        username=_one_line(username).removeprefix("@")[:USERNAME_MAX_CHARS],
        name=_one_line(name)[:NAME_MAX_CHARS],
    )


def telegram_chat_id(session_id: str) -> int | None:
    match = _TELEGRAM_SESSION_RE.fullmatch(session_id)
    if match is None:
        return None
    return int(match.group(1) or match.group(2))


def parse_reminder_clock(text: str) -> str | None:
    match = _CLOCK_RE.fullmatch(text.strip())
    if match is None:
        return None
    hour = int(match.group(1))
    minute = int(match.group(2))
    if hour > 23 or minute > 59:
        return None
    return f"{hour:02d}:{minute:02d}"


def empty_reminder_hours() -> dict[str, int]:
    return {f"{hour:02d}": 0 for hour in range(24)}


def add_reminder_hour(hours: dict[str, int], session: str, clock: str) -> None:
    if telegram_chat_id(session) is None:
        return
    normalized = parse_reminder_clock(clock)
    if normalized is not None:
        hours[normalized[:2]] += 1


def reminder_summary(hours: dict[str, int]) -> dict[str, Any]:
    return {"timezone": METRICS_TIMEZONE, "active": sum(hours.values()), "hours": hours}


def reminder_is_due(moment: float, hhmm: str) -> bool:
    clock = parse_reminder_clock(hhmm)
    if clock is None:
        return False
    local = datetime.fromtimestamp(moment, _TZ)
    hour, minute = (int(part) for part in clock.split(":"))
    start = local.replace(hour=hour, minute=minute, second=0, microsecond=0)
    return start <= local < start + REMINDER_GRACE


def opted_reminder_sessions(
    times: dict[str, str],
    active_today: set[str],
    moment: float,
    mode: str,
) -> list[str]:
    if mode not in {"auto", "manual"}:
        raise ValueError("invalid reminder mode")
    chosen = []
    for session, hhmm in times.items():
        if telegram_chat_id(session) is None or session in active_today:
            continue
        if mode != "manual" and not reminder_is_due(moment, hhmm):
            continue
        chosen.append(session)
    return sorted(chosen)


def schedule_decision(action: str, pending: str | None, text: str | None, run_id: str) -> dict[str, str]:
    if action == "ask":
        saved = run_id.strip()
        if not saved:
            raise ValueError("run id required")
        return {"status": "asking", "write_pending": saved}
    if action == "decline":
        return {"status": "declined", "write_pending": ""}
    if action == "clear":
        return {"status": "cleared", "write_pending": "", "clear_time": "1"}
    if action == "submit":
        if not pending:
            return {"status": "ignored"}
        clock = parse_reminder_clock(text or "")
        if clock is None:
            return {"status": "invalid"}
        return {"status": "saved", "time": clock, "runId": pending, "write_pending": ""}
    raise ValueError("invalid schedule action")


def public_schedule(decision: dict[str, str]) -> dict[str, str]:
    body = {"status": decision["status"]}
    if decision.get("time"):
        body["time"] = decision["time"]
    if decision.get("runId"):
        body["runId"] = decision["runId"]
    return body


def _one_line(value: str | None) -> str:
    return " ".join((value or "").split())


def apply_funnel(user: FunnelUser, *, kind: str, source: str | None, day: str) -> FunnelDelta:
    if source and not user.source:
        user.source = source
    shown = user.source or DIRECT_SOURCE
    delta = FunnelDelta(day=day, source=shown)
    if kind in {"start", "voice"}:
        if not user.first_day:
            user.first_day = day
        elif user.first_day < day and user.last_return_day != day:
            user.last_return_day = day
            delta.returned = 1
    if kind == "start" and not user.start_day:
        user.start_day = day
        delta.start = 1
    elif kind == "voice" and not user.activated_day:
        user.activated_day = day
        delta.activated = 1
    elif kind == "exchange":
        user.exchanges += 1
        if user.exchanges >= ENGAGED_EXCHANGES and not user.engaged_day:
            user.engaged_day = day
            delta.engaged = 1
    return delta


def funnel_view(
    moment: float,
    day_counts: dict[str, FunnelCounts],
    source_counts: dict[tuple[str, str], FunnelCounts],
) -> dict[str, Any]:
    days = recent_days(moment, FUNNEL_WINDOW_DAYS)
    day_rows = []
    for day in days:
        counts = day_counts.get(day, FunnelCounts())
        day_rows.append(_funnel_row(day, counts))
    totals: dict[str, FunnelCounts] = {}
    visible = set(days)
    for (day, source), counts in source_counts.items():
        if day not in visible:
            continue
        bucket = totals.setdefault(source, FunnelCounts())
        bucket.start += counts.start
        bucket.activated += counts.activated
        bucket.engaged += counts.engaged
        bucket.returned += counts.returned
    sources = [
        {
            "source": name,
            "start": counts.start,
            "activated": counts.activated,
            "engaged": counts.engaged,
            "returned": counts.returned,
        }
        for name, counts in totals.items()
        if counts.start or counts.activated or counts.engaged or counts.returned
    ]
    sources.sort(key=lambda row: (-int(row["activated"]), str(row["source"])))
    return {
        "activated7": sum(int(row["activated"]) for row in day_rows[:FUNNEL_WEEK_DAYS]),
        "funnelDays": [_public_day(row) for row in day_rows],
        "funnelSources": sources,
    }


def _funnel_row(label: str, counts: FunnelCounts) -> dict[str, int | str]:
    return {
        "day": label,
        "source": label,
        "start": counts.start,
        "activated": counts.activated,
        "engaged": counts.engaged,
        "returned": counts.returned,
    }


def _public_day(row: dict[str, int | str]) -> dict[str, int | str]:
    return {
        "day": row["day"],
        "start": row["start"],
        "activated": row["activated"],
        "engaged": row["engaged"],
        "returned": row["returned"],
    }


def _bump_funnel(
    day_counts: dict[str, FunnelCounts],
    source_counts: dict[tuple[str, str], FunnelCounts],
    delta: FunnelDelta,
) -> None:
    if not _delta_counts(delta):
        return
    day_bucket = day_counts.setdefault(delta.day, FunnelCounts())
    source_bucket = source_counts.setdefault((delta.day, delta.source), FunnelCounts())
    for bucket in (day_bucket, source_bucket):
        bucket.start += delta.start
        bucket.activated += delta.activated
        bucket.engaged += delta.engaged
        bucket.returned += delta.returned


def _delta_counts(delta: FunnelDelta) -> list[tuple[str, int]]:
    return [
        (field, amount)
        for field, amount in (
            ("start", delta.start),
            ("activated", delta.activated),
            ("engaged", delta.engaged),
            ("returned", delta.returned),
        )
        if amount
    ]


def _funnel_user_key(session_id: str) -> str:
    return f"metrics:funnel:user:{session_id}"


def _reminder_sent_key(day_name: str, session_id: str) -> str:
    return f"reminder:sent:{day_name}:{session_id}"


def _reminder_time_key(session_id: str) -> str:
    return f"reminder-time:{session_id}"


def _reminder_pending_key(session_id: str) -> str:
    return f"reminder-pending:{session_id}"


def _funnel_day_key(day_name: str) -> str:
    return f"metrics:funnel:{day_name}"


def _funnel_source_key(day_name: str, source: str) -> str:
    return f"metrics:funnel:{day_name}:src:{source}"


def _funnel_user(raw: Any) -> FunnelUser:
    data = raw if isinstance(raw, dict) else {}
    return FunnelUser(
        source=str(data.get("source") or ""),
        first_day=str(data.get("first_day") or ""),
        start_day=str(data.get("start_day") or ""),
        activated_day=str(data.get("activated_day") or ""),
        engaged_day=str(data.get("engaged_day") or ""),
        exchanges=nonneg_int(data.get("exchanges")),
        last_return_day=str(data.get("last_return_day") or ""),
    )


def _funnel_user_mapping(user: FunnelUser) -> dict[str, str]:
    return {
        "source": user.source,
        "first_day": user.first_day,
        "start_day": user.start_day,
        "activated_day": user.activated_day,
        "engaged_day": user.engaged_day,
        "exchanges": str(user.exchanges),
        "last_return_day": user.last_return_day,
    }


def _funnel_counts(raw: Any) -> FunnelCounts:
    data = raw if isinstance(raw, dict) else {}
    return FunnelCounts(
        start=nonneg_int(data.get("start")),
        activated=nonneg_int(data.get("activated")),
        engaged=nonneg_int(data.get("engaged")),
        returned=nonneg_int(data.get("returned")),
    )


def build_snapshot(
    *,
    now: float,
    day: DayTotals,
    dau: int,
    samples: list[LlmSample],
    chats: list[ChatRow],
    rates: MetricRates,
) -> dict[str, Any]:
    window = [sample for sample in samples if 0 <= now - sample.ts <= LLM_WINDOW_SECONDS]
    window_prompt = sum(sample.prompt_tokens for sample in window)
    window_completion = sum(sample.completion_tokens for sample in window)
    elapsed_ms = sum(sample.elapsed_ms for sample in window)
    total_rub = _total_rub(day, rates)
    rub_per_turn = None
    rub_per_dau = None
    if total_rub is not None:
        rub_per_turn = total_rub / day.turns if day.turns else 0.0
        rub_per_dau = total_rub / dau if dau else 0.0
    ordered = sorted(chats, key=lambda row: row.last_unix, reverse=True)[:CHAT_LIMIT]
    return {
        "timezone": METRICS_TIMEZONE,
        "day": metrics_day(now),
        "promptTokens": day.prompt_tokens,
        "completionTokens": day.completion_tokens,
        "llmRequests": day.llm_requests,
        "llmFailures": day.llm_failures,
        "llmRequestsByPurpose": {purpose: day.llm_by_purpose.get(purpose, 0) for purpose in LLM_PURPOSES},
        "tpm": window_prompt + window_completion,
        "tps": (window_completion / (elapsed_ms / 1000)) if elapsed_ms > 0 else 0.0,
        "turns": day.turns,
        "dau": dau,
        "sttSeconds": day.stt_ms / 1000,
        "ttsChars": day.tts_chars,
        "rubPerTurn": rub_per_turn,
        "rubPerDau": rub_per_dau,
        "ratesConfigured": rates.configured,
        "chats": [
            {
                "sessionId": row.session_id,
                "turns": row.turns,
                "lastAt": datetime.fromtimestamp(row.last_unix, _TZ).isoformat(timespec="seconds"),
                "username": row.username or None,
                "name": row.name or None,
            }
            for row in ordered
        ],
    }


def llm_days(from_day: date, to_day: date) -> list[date]:
    if to_day < from_day or (to_day - from_day).days >= 366:
        raise ValueError("LLM range must be between 1 and 366 days")
    return [from_day + timedelta(days=offset) for offset in range((to_day - from_day).days + 1)]


def llm_range_snapshot(from_day: date, to_day: date, days: list[DayTotals]) -> dict[str, Any]:
    return {
        "from": from_day.isoformat(),
        "to": to_day.isoformat(),
        "timezone": METRICS_TIMEZONE,
        "requests": sum(day.llm_requests for day in days),
        "failures": sum(day.llm_failures for day in days),
        "byPurpose": {
            purpose: sum(day.llm_by_purpose.get(purpose, 0) for day in days)
            for purpose in LLM_PURPOSES
        },
    }


def nonneg_int(value: Any) -> int:
    if isinstance(value, bool) or value is None:
        return 0
    try:
        number = int(value)
    except (TypeError, ValueError):
        return 0
    return number if number > 0 else 0


def seconds_to_ms(seconds: float) -> int:
    try:
        value = float(seconds)
    except (TypeError, ValueError):
        return 0
    if math.isnan(value) or math.isinf(value) or value <= 0:
        return 0
    return int(round(value * 1000))


def _moment(now: float | None) -> float:
    return time.time() if now is None else now


def _total_rub(day: DayTotals, rates: MetricRates) -> float | None:
    if not rates.configured:
        return None
    prompt_rate = rates.prompt_rub_per_million or 0
    completion_rate = rates.completion_rub_per_million or 0
    stt_rate = rates.stt_rub_per_minute or 0
    tts_rate = rates.tts_rub_per_million_chars or 0
    return (
        day.prompt_tokens * prompt_rate / 1_000_000
        + day.completion_tokens * completion_rate / 1_000_000
        + (day.stt_ms / 1000) / 60 * stt_rate
        + day.tts_chars * tts_rate / 1_000_000
    )


def _day_key(day_name: str) -> str:
    return f"metrics:day:{day_name}"


def _correction_key(day_name: str) -> str:
    return f"metrics:corrections:{day_name}"


def _check_correction_outcome(outcome: str) -> None:
    if outcome not in CORRECTION_OUTCOMES:
        raise ValueError("unknown correction outcome")


def _check_correction_attempts(attempts: int) -> None:
    if isinstance(attempts, bool) or attempts not in (0, 1, 2):
        raise ValueError("correction attempts must be 0, 1, or 2")


def _correction_counts(raw: dict[str, Any]) -> dict[str, dict[str, int]]:
    return {
        outcome: {
            "count": nonneg_int(raw.get(f"{outcome}:count")),
            "elapsedMs": nonneg_int(raw.get(f"{outcome}:elapsed_ms")),
            "secondAttempts": nonneg_int(raw.get(f"{outcome}:second_attempts")),
        }
        for outcome in sorted(CORRECTION_OUTCOMES)
        if raw.get(f"{outcome}:count") is not None
    }
def _clip_results_key(day_name: str) -> str:
    return f"metrics:clip-results:{day_name}"


def _clip_reasons_key(day_name: str) -> str:
    return f"metrics:clip-reasons:{day_name}"


def _partial_key(day_name: str) -> str:
    return f"metrics:partial:{day_name}"


def _check_partial(feature: str, outcome: str) -> None:
    if feature not in PARTIAL_FEATURES or outcome not in PARTIAL_OUTCOMES:
        raise ValueError("unknown partial result")


def _provider_key(day_name: str) -> str:
    return f"metrics:provider:{day_name}"


def _check_provider(service: str, kind: str, result: str, provider: str) -> None:
    if (service not in PROVIDER_SERVICES or provider not in PROVIDER_NAMES or
            kind not in {"attempt", "operation"} or result not in PROVIDER_RESULTS):
        raise ValueError("unknown provider result")


def _dau_key(day_name: str) -> str:
    return f"metrics:dau:{day_name}"


def _chat_key(session_id: str) -> str:
    return f"metrics:chat:{session_id}"


def _day_totals(raw: Any) -> DayTotals:
    data = raw if isinstance(raw, dict) else {}
    return DayTotals(
        prompt_tokens=nonneg_int(data.get("prompt_tokens")),
        completion_tokens=nonneg_int(data.get("completion_tokens")),
        llm_requests=nonneg_int(data.get("llm_requests")),
        llm_failures=nonneg_int(data.get("llm_failures")),
        llm_by_purpose={purpose: nonneg_int(data.get(f"llm_{purpose}_requests")) for purpose in LLM_PURPOSES},
        stt_ms=nonneg_int(data.get("stt_ms")),
        tts_chars=nonneg_int(data.get("tts_chars")),
        turns=nonneg_int(data.get("turns")),
    )


def _parse_sample(raw: Any) -> LlmSample | None:
    text = raw if isinstance(raw, str) else raw.decode("utf-8") if isinstance(raw, bytes) else None
    if text is None:
        return None
    try:
        payload = json.loads(text)
    except json.JSONDecodeError:
        return None
    if not isinstance(payload, dict):
        return None
    try:
        ts = float(payload["ts"])
    except (KeyError, TypeError, ValueError):
        return None
    return LlmSample(
        ts=ts,
        prompt_tokens=nonneg_int(payload.get("prompt")),
        completion_tokens=nonneg_int(payload.get("completion")),
        elapsed_ms=nonneg_int(payload.get("elapsed_ms")),
    )
