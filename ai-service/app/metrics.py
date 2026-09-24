from __future__ import annotations

import asyncio
import json
import math
import time
from dataclasses import dataclass
from datetime import datetime
from typing import Any, Protocol
from zoneinfo import ZoneInfo

from redis.asyncio import Redis

METRICS_TIMEZONE = "Europe/Moscow"
LLM_WINDOW_SECONDS = 60
LLM_RETAIN_SECONDS = 120
CHAT_LIMIT = 200

_TZ = ZoneInfo(METRICS_TIMEZONE)
_EVENTS_KEY = "metrics:llm:events"
_CHATS_KEY = "metrics:chats"


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
LLM_PROMPT_USD_PER_MILLION = 0.15  # OpenRouter openai/gpt-4o-mini input
LLM_COMPLETION_USD_PER_MILLION = 0.60  # OpenRouter openai/gpt-4o-mini output
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


class MetricsStore(Protocol):
    async def record_llm(
        self,
        prompt_tokens: int,
        completion_tokens: int,
        elapsed_ms: int,
        *,
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

    async def snapshot(self, *, now: float | None = None) -> dict[str, Any]: ...

    async def aclose(self) -> None: ...


class MemoryMetricsStore:
    def __init__(self, rates: MetricRates) -> None:
        self._rates = rates
        self._days: dict[str, DayTotals] = {}
        self._dau: dict[str, set[str]] = {}
        self._samples: list[LlmSample] = []
        self._chats: dict[str, ChatRow] = {}
        self._lock = asyncio.Lock()

    async def record_llm(
        self,
        prompt_tokens: int,
        completion_tokens: int,
        elapsed_ms: int,
        *,
        now: float | None = None,
    ) -> None:
        moment = _moment(now)
        sample = LlmSample(
            ts=moment,
            prompt_tokens=nonneg_int(prompt_tokens),
            completion_tokens=nonneg_int(completion_tokens),
            elapsed_ms=nonneg_int(elapsed_ms),
        )
        async with self._lock:
            day = self._days.setdefault(metrics_day(moment), DayTotals())
            day.prompt_tokens += sample.prompt_tokens
            day.completion_tokens += sample.completion_tokens
            self._samples.append(sample)
            self._samples = [item for item in self._samples if moment - item.ts <= LLM_RETAIN_SECONDS]

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

    async def snapshot(self, *, now: float | None = None) -> dict[str, Any]:
        moment = _moment(now)
        async with self._lock:
            day_name = metrics_day(moment)
            return build_snapshot(
                now=moment,
                day=self._days.get(day_name, DayTotals()),
                dau=len(self._dau.get(day_name, ())),
                samples=list(self._samples),
                chats=list(self._chats.values()),
                rates=self._rates,
            )

    async def aclose(self) -> None:
        return None

    def _add_turn(self, day_name: str, session: str, stt_ms: int, tts_chars: int, moment: float) -> None:
        day = self._days.setdefault(day_name, DayTotals())
        day.stt_ms += stt_ms
        day.tts_chars += tts_chars
        day.turns += 1
        self._dau.setdefault(day_name, set()).add(session)
        previous = self._chats.get(session)
        turns = (previous.turns if previous is not None else 0) + 1
        self._chats[session] = ChatRow(session_id=session, turns=turns, last_unix=moment)


class RedisMetricsStore:
    def __init__(self, redis: Redis, rates: MetricRates) -> None:
        self._redis = redis
        self._rates = rates
        self._lock = asyncio.Lock()

    async def record_llm(
        self,
        prompt_tokens: int,
        completion_tokens: int,
        elapsed_ms: int,
        *,
        now: float | None = None,
    ) -> None:
        moment = _moment(now)
        prompt = nonneg_int(prompt_tokens)
        completion = nonneg_int(completion_tokens)
        elapsed = nonneg_int(elapsed_ms)
        payload = json.dumps(
            {"ts": moment, "prompt": prompt, "completion": completion, "elapsed_ms": elapsed},
        )
        async with self._lock:
            pipe = self._redis.pipeline()
            day_key = _day_key(metrics_day(moment))
            pipe.hincrby(day_key, "prompt_tokens", prompt)
            pipe.hincrby(day_key, "completion_tokens", completion)
            pipe.lpush(_EVENTS_KEY, payload)
            await pipe.execute()
            await self._prune_events(moment)

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

    async def snapshot(self, *, now: float | None = None) -> dict[str, Any]:
        moment = _moment(now)
        day_name = metrics_day(moment)
        async with self._lock:
            totals = await self._redis.hgetall(_day_key(day_name))
            dau = int(await self._redis.scard(_dau_key(day_name)))
            raw_events = await self._redis.lrange(_EVENTS_KEY, 0, -1)
            ranked = await self._redis.zrevrange(_CHATS_KEY, 0, CHAT_LIMIT - 1, withscores=True)
            chats = await self._chat_rows(ranked)
        return build_snapshot(
            now=moment,
            day=_day_totals(totals),
            dau=dau,
            samples=[sample for item in raw_events if (sample := _parse_sample(item)) is not None],
            chats=chats,
            rates=self._rates,
        )

    async def aclose(self) -> None:
        await self._redis.aclose()

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
            pipe.hget(_chat_key(session), "turns")
        turns = await pipe.execute()
        return [
            ChatRow(session_id=session, turns=nonneg_int(turn_count), last_unix=score)
            for (session, score), turn_count in zip(parsed, turns, strict=True)
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
            }
            for row in ordered
        ],
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


def _dau_key(day_name: str) -> str:
    return f"metrics:dau:{day_name}"


def _chat_key(session_id: str) -> str:
    return f"metrics:chat:{session_id}"


def _day_totals(raw: Any) -> DayTotals:
    data = raw if isinstance(raw, dict) else {}
    return DayTotals(
        prompt_tokens=nonneg_int(data.get("prompt_tokens")),
        completion_tokens=nonneg_int(data.get("completion_tokens")),
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
