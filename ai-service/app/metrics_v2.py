from __future__ import annotations

import json
import re
import time
from datetime import date, timedelta
from contextvars import ContextVar, Token
from typing import Any
from redis.asyncio import Redis
from redis.exceptions import WatchError

from app.load_test import load_test_active
from app.metrics import metrics_day, recent_days

_EVENTS_KEY = "metrics:v2:events"
_EVENTS_LIMIT = 10_000
_EVENTS_TTL_SECONDS = 30 * 24 * 60 * 60
_LLM_REQUEST_DAYS = 15
_CLIENTS = ("telegram", "android", "ios", "desktop", "unknown")
_MOBILE = {"android", "ios", "desktop"}
_TELEGRAM_VOICE_MILESTONES = (1, 3, 5, 10, 20)
_TELEGRAM_RECEIPT_TTL = 7 * 24 * 60 * 60
_TELEGRAM_JOURNEY_SINCE = "metrics:v2:telegram:journey:since"
_TELEGRAM_JOURNEY_ONLY_START = "metrics:v2:telegram:journey:only-start"
_TELEGRAM_JOURNEY_MILESTONES = "metrics:v2:telegram:journey:milestones"
_TELEGRAM_HISTORICAL_VOICE_USERS = "metrics:v2:telegram:journey:historical-only"
_TELEGRAM_HISTORICAL_BACKFILL_DONE = "metrics:v2:telegram:journey:historical-backfill-done"
_TELEGRAM_HISTORICAL_VOICE_SINCE = "2026-09-24"
_VOICE_LLM_COST_SINCE = "metrics:v2:voice-llm-cost:since"
_LEGACY_BEFORE_DAY = "2026-10-06"
_TELEGRAM_MESSAGE_ID = re.compile(r"message:[0-9]+\Z")
_CHAT_ID = re.compile(r"tg-(?:ChatId\(chatId=|ChatIdWithThreadId\(chatIdWithThreadId=\()?(-?\d+)")
_session: ContextVar[str] = ContextVar("metrics_v2_session", default="")
_platform: ContextVar[str] = ContextVar("metrics_v2_platform", default="")


def bind_metrics(session_id: str, platform: str | None = None) -> tuple[Token, Token]:
    return _session.set(session_id or ""), _platform.set((platform or "").strip().lower())


def reset_metrics(tokens: tuple[Token, Token]) -> None:
    _session.reset(tokens[0])
    _platform.reset(tokens[1])


def current_session() -> str:
    return _session.get()


def client_for(session_id: str, platform: str | None = None) -> str:
    text = (session_id or "").strip()
    if text.startswith("tg-"):
        return "telegram"
    if text.startswith("app-"):
        name = (platform if platform is not None else _platform.get()).strip().lower()
        return name if name in _MOBILE else "unknown"
    return "unknown"


def _telegram_chat_id(session_id: str) -> str:
    match = _CHAT_ID.match(session_id)
    return f"tg-{match.group(1)}" if match else session_id


def _cohort_counts(cohorts: dict[str, dict[str, str]], field: str) -> dict[str, int]:
    counts: dict[str, int] = {}
    for user in cohorts.values():
        day = user.get(field)
        if day:
            counts[day] = counts.get(day, 0) + 1
    return counts


def cost_micro(value: Any) -> int | None:
    if isinstance(value, bool) or value is None:
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    if number < 0:
        return None
    return int(round(number * 1_000_000))


def read_provider_cost(response: Any) -> int | None:
    if not isinstance(response, dict):
        response = response.model_dump() if callable(getattr(response, "model_dump", None)) else response
    usage = response.get("usage") if isinstance(response, dict) else getattr(response, "usage", None)
    if usage is None:
        return None
    raw = usage.get("cost") if isinstance(usage, dict) else getattr(usage, "cost", None)
    return cost_micro(raw)


class MetricsV2:
    async def record_llm(
        self, session_id: str, kind: str, model: str, prompt_tokens: int, completion_tokens: int,
        cost: int | None, *, platform: str | None = None, now: float | None = None,
    ) -> None: ...

    async def record_tts(
        self, session_id: str, model: str, chars: int, cost: int | None,
        *, platform: str | None = None, now: float | None = None,
    ) -> None: ...

    async def record_stt(
        self, session_id: str, model: str, seconds: float,
        *, platform: str | None = None, now: float | None = None,
    ) -> None: ...

    async def record_realtime(
        self, session_id: str, model: str, counts: dict[str, int],
        *, platform: str | None = None, now: float | None = None,
    ) -> None: ...

    async def record_transcript(
        self, session_id: str, model: str, amount: int,
        *, platform: str | None = None, now: float | None = None,
    ) -> None: ...

    async def record_turn(self, session_id: str, *, platform: str | None = None, now: float | None = None) -> None: ...

    async def record_action(
        self, session_id: str, action: str, *, platform: str | None = None, now: float | None = None,
    ) -> None: ...

    async def record_telegram_message(
        self, session_id: str, action: str, event_id: str, *, now: float | None = None,
    ) -> None: ...

    async def record_error(
        self, session_id: str, service: str, outcome: str, *, platform: str | None = None, now: float | None = None,
    ) -> None: ...

    async def remember_platform(self, session_id: str, platform: str) -> None: ...

    async def platform_of(self, session_id: str) -> str | None: ...

    async def snapshot(self, *, now: float | None = None) -> dict[str, Any]: ...

    async def overview(self, from_day: str, to_day: str) -> dict[str, Any]: ...

    async def record_voice_llm_usage(
        self, session_id: str, calls: int, priced_calls: int, cost_micro_usd: int, *, now: float | None = None,
    ) -> None: ...

    def prometheus(self, snapshot: dict[str, Any]) -> str: ...


class MemoryMetricsV2(MetricsV2):
    def __init__(self) -> None:
        self.days: dict[tuple[str, str], dict[str, int]] = {}
        self.currencies: dict[tuple[str, str], str] = {}
        self.dau: dict[tuple[str, str], set[str]] = {}
        self.platforms: dict[str, str] = {}
        self.chats: dict[tuple[str, str], dict[str, int]] = {}
        self.events: list[dict[str, Any]] = []
        self._telegram_seen: set[tuple[str, str]] = set()
        self._telegram_only_start: set[str] = set()
        self._telegram_voice_counts: dict[str, int] = {}
        self._telegram_milestones = {threshold: 0 for threshold in _TELEGRAM_VOICE_MILESTONES}
        self._telegram_since = ""
        self._voice_llm_cost_since = ""

    async def record_llm(self, session_id, kind, model, prompt_tokens, completion_tokens, cost, *, platform=None, now=None):
        if load_test_active():
            return
        await self._add(session_id, platform, now, "llm", kind, model, {
            "prompt": prompt_tokens, "completion": completion_tokens, "calls": 1,
            **({} if cost is None else {"cost": cost}),
        }, cost is not None)

    async def record_tts(self, session_id, model, chars, cost, *, platform=None, now=None):
        if load_test_active():
            return
        await self._add(session_id, platform, now, "tts", "tts", model, {
            "chars": chars, "calls": 1, **({} if cost is None else {"cost": cost}),
        }, cost is not None)

    async def record_stt(self, session_id, model, seconds, *, platform=None, now=None):
        if load_test_active():
            return
        await self._add(session_id, platform, now, "stt", "stt", model, {
            "ms": int(round(max(seconds, 0) * 1000)), "calls": 1,
        }, False)

    async def record_realtime(self, session_id, model, counts, *, platform=None, now=None):
        if load_test_active():
            return
        await self._add(session_id, platform, now, "rt", "realtime", model, {key: int(counts.get(key) or 0) for key in (
            "in_text", "in_audio", "out_text", "out_audio", "cached_text", "cached_audio",
        )} | {"calls": 1}, False)
        await self.record_turn(session_id, platform=platform, now=now)

    async def record_transcript(self, session_id, model, amount, *, platform=None, now=None):
        if load_test_active():
            return
        await self._add(session_id, platform, now, "transcript", "stt", model, {"amount": amount, "calls": 1}, False)

    async def record_turn(self, session_id, *, platform=None, now=None):
        if load_test_active():
            return
        client, day = await self._scope(session_id, platform, now)
        if client is None:
            return
        session = (session_id or current_session()).strip()
        self._bucket(day, client)["turns"] = self._bucket(day, client).get("turns", 0) + 1
        self.dau.setdefault((day, client), set()).add(session)
        self.chats.setdefault((day, client), {})
        self.chats[(day, client)][session] = self.chats[(day, client)].get(session, 0) + 1
        self._event(day, client, "turn", "", "")

    async def record_action(self, session_id, action, *, platform=None, now=None):
        if load_test_active():
            return
        name = _token(action) or "other"
        await self._add(session_id, platform, now, "action", name, "", {"count": 1}, False)

    async def record_telegram_message(self, session_id, action, event_id, *, now=None):
        if load_test_active():
            return
        if not _telegram_journey_event(session_id, action, event_id):
            return
        receipt = (session_id, event_id)
        if receipt in self._telegram_seen:
            return
        self._telegram_seen.add(receipt)
        self._telegram_since = self._telegram_since or metrics_day(now if now is not None else time.time())
        if action == "start":
            if self._telegram_voice_counts.get(session_id, 0) == 0:
                self._telegram_only_start.add(session_id)
        else:
            count = self._telegram_voice_counts.get(session_id, 0) + 1
            self._telegram_voice_counts[session_id] = count
            self._telegram_only_start.discard(session_id)
            if count in _TELEGRAM_VOICE_MILESTONES:
                self._telegram_milestones[count] += 1

    async def record_error(self, session_id, service, outcome, *, platform=None, now=None):
        if load_test_active():
            return
        await self._add(session_id, platform, now, "error", _token(service), _token(outcome), {"count": 1}, False)

    async def remember_platform(self, session_id, platform):
        if load_test_active():
            return
        name = (platform or "").strip().lower()
        if session_id.strip().startswith("app-") and name in _MOBILE:
            self.platforms[session_id.strip()] = name

    async def platform_of(self, session_id):
        return self.platforms.get(session_id.strip())

    async def snapshot(self, *, now=None):
        day = metrics_day(now if now is not None else time.time())
        columns = []
        for client in _CLIENTS:
            bucket = self.days.get((day, client), {})
            if client == "unknown" and not bucket and not self.dau.get((day, client)):
                continue
            column = _column(client, bucket, len(self.dau.get((day, client), set())))
            column["chats"] = _chat_rows(self.chats.get((day, client), {}))
            columns.append(column)
        return {
            "clients": columns,
            "telegramJourney": _telegram_journey_snapshot(
                self._telegram_since, len(self._telegram_only_start), self._telegram_milestones,
            ),
            "llmRequestsByDay": _llm_requests_from_memory(self.days, now if now is not None else time.time()),
        }

    def prometheus(self, snapshot):
        return _prometheus(snapshot)

    async def overview(self, from_day: str, to_day: str) -> dict[str, Any]:
        days = _overview_days(from_day, to_day)
        model_since = min((day for day, client in self.days if client == "telegram"), default="")
        return {
            "totalChats": len({session for session in self._telegram_voice_counts} | self._telegram_only_start),
            "modelSince": model_since,
            "voiceLlmSince": self._voice_llm_cost_since,
            "days": [_overview_day(day, self.days.get((day, "telegram"), {})) for day in days],
            "legacyActiveChatsInPeriod": 0,
            "legacyDays": [],
            "redisDauDays": [],
            "chatGrowthDays": [],
            "chatGrowthUnattributed": 0,
        }

    async def record_voice_llm_usage(self, session_id, calls, priced_calls, cost_micro_usd, *, now=None):
        if load_test_active() or calls <= 0:
            return
        self._voice_llm_cost_since = self._voice_llm_cost_since or metrics_day(now if now is not None else time.time())
        await self._add(session_id, None, now, "voice_llm", "usage", "", {
            "calls": calls, "priced": priced_calls, "cost": cost_micro_usd,
        }, priced_calls > 0)

    async def _scope(self, session_id, platform, now):
        session = (session_id or current_session()).strip()
        if not session:
            return None, None
        saved = platform if platform is not None else await self.platform_of(session)
        return client_for(session, saved), metrics_day(now if now is not None else time.time())

    def _bucket(self, day, client):
        return self.days.setdefault((day, client), {})

    async def _add(self, session_id, platform, now, family, kind, model, fields, billed):
        client, day = await self._scope(session_id, platform, now)
        if client is None:
            return
        bucket = self._bucket(day, client)
        for name, value in fields.items():
            key = f"{family}|{kind}|{model}|{name}"
            bucket[key] = bucket.get(key, 0) + int(value)
        if billed:
            self.currencies[(day, client)] = "USD"
        self._event(day, client, family, kind, model, fields)

    def _event(self, day, client, family, kind, model, fields=None):
        self.events.append({
            "day": day, "client": client, "family": family, "kind": kind, "model": model,
            **(fields or {}),
        })
        if len(self.events) > _EVENTS_LIMIT:
            del self.events[:-_EVENTS_LIMIT]


class RedisMetricsV2(MetricsV2):
    def __init__(self, redis: Redis) -> None:
        self._redis = redis

    async def _backfill_historical_voice_users(self) -> None:
        if await self._redis.exists(_TELEGRAM_HISTORICAL_BACKFILL_DONE):
            return
        found_history = False
        async for key in self._redis.scan_iter(match="metrics:funnel:user:*"):
            session = str(key).removeprefix("metrics:funnel:user:")
            if client_for(session) != "telegram":
                continue
            first_voice_day = await self._redis.hget(key, "activated_day")
            if not first_voice_day or first_voice_day < _TELEGRAM_HISTORICAL_VOICE_SINCE:
                continue
            found_history = True
            journey_key = f"metrics:v2:telegram:journey:user:{session}"
            if int(await self._redis.hget(journey_key, "voices") or 0) == 0:
                await self._redis.sadd(_TELEGRAM_HISTORICAL_VOICE_USERS, session)
                # A new voice can arrive while the scan is running.
                if int(await self._redis.hget(journey_key, "voices") or 0) > 0:
                    await self._redis.srem(_TELEGRAM_HISTORICAL_VOICE_USERS, session)
                else:
                    await self._redis.srem(_TELEGRAM_JOURNEY_ONLY_START, session)
        await self._redis.set(_TELEGRAM_HISTORICAL_BACKFILL_DONE, "1" if found_history else "0")

    async def record_llm(self, session_id, kind, model, prompt_tokens, completion_tokens, cost, *, platform=None, now=None):
        if load_test_active():
            return
        await self._add(session_id, platform, now, "llm", kind, model, {
            "prompt": prompt_tokens, "completion": completion_tokens, "calls": 1,
            **({} if cost is None else {"cost": cost}),
        }, cost is not None)

    async def record_tts(self, session_id, model, chars, cost, *, platform=None, now=None):
        if load_test_active():
            return
        await self._add(session_id, platform, now, "tts", "tts", model, {
            "chars": chars, "calls": 1, **({} if cost is None else {"cost": cost}),
        }, cost is not None)

    async def record_stt(self, session_id, model, seconds, *, platform=None, now=None):
        if load_test_active():
            return
        await self._add(session_id, platform, now, "stt", "stt", model, {
            "ms": int(round(max(seconds, 0) * 1000)), "calls": 1,
        }, False)

    async def record_realtime(self, session_id, model, counts, *, platform=None, now=None):
        if load_test_active():
            return
        await self._add(session_id, platform, now, "rt", "realtime", model, {key: int(counts.get(key) or 0) for key in (
            "in_text", "in_audio", "out_text", "out_audio", "cached_text", "cached_audio",
        )} | {"calls": 1}, False)
        await self.record_turn(session_id, platform=platform, now=now)

    async def record_transcript(self, session_id, model, amount, *, platform=None, now=None):
        if load_test_active():
            return
        await self._add(session_id, platform, now, "transcript", "stt", model, {"amount": amount, "calls": 1}, False)

    async def record_turn(self, session_id, *, platform=None, now=None):
        if load_test_active():
            return
        client, day, session = await self._scope(session_id, platform, now)
        if client is None:
            return
        pipe = self._redis.pipeline()
        pipe.hincrby(_day_key(day, client), "turns", 1)
        pipe.expire(_day_key(day, client), _EVENTS_TTL_SECONDS)
        pipe.sadd(_dau_key(day, client), session)
        pipe.expire(_dau_key(day, client), _EVENTS_TTL_SECONDS)
        pipe.hincrby(_chat_key(day, client), session, 1)
        pipe.expire(_chat_key(day, client), _EVENTS_TTL_SECONDS)
        pipe.lpush(_EVENTS_KEY, _event_json(day, client, "turn", "", ""))
        pipe.ltrim(_EVENTS_KEY, 0, _EVENTS_LIMIT - 1)
        pipe.expire(_EVENTS_KEY, _EVENTS_TTL_SECONDS)
        await pipe.execute()

    async def record_action(self, session_id, action, *, platform=None, now=None):
        if load_test_active():
            return
        await self._add(session_id, platform, now, "action", _token(action) or "other", "", {"count": 1}, False)

    async def record_telegram_message(self, session_id, action, event_id, *, now=None):
        if load_test_active():
            return
        if not _telegram_journey_event(session_id, action, event_id):
            return
        user_key = f"metrics:v2:telegram:journey:user:{session_id}"
        receipt_key = f"metrics:v2:telegram:journey:receipt:{session_id}:{event_id}"
        day = metrics_day(now if now is not None else time.time())
        for _ in range(5):
            async with self._redis.pipeline() as pipe:
                try:
                    await pipe.watch(user_key, receipt_key)
                    if await pipe.exists(receipt_key):
                        return
                    previous = await pipe.hgetall(user_key)
                    voices = int(previous.get("voices") or 0)
                    started = previous.get("started") == "1"
                    pipe.multi()
                    pipe.set(receipt_key, "1", ex=_TELEGRAM_RECEIPT_TTL)
                    pipe.setnx(_TELEGRAM_JOURNEY_SINCE, day)
                    if action == "start":
                        if not started:
                            pipe.hset(user_key, "started", "1")
                            if voices == 0 and not await self._redis.sismember(_TELEGRAM_HISTORICAL_VOICE_USERS, session_id):
                                pipe.sadd(_TELEGRAM_JOURNEY_ONLY_START, session_id)
                    else:
                        voices += 1
                        pipe.hset(user_key, "voices", voices)
                        pipe.srem(_TELEGRAM_JOURNEY_ONLY_START, session_id)
                        pipe.srem(_TELEGRAM_HISTORICAL_VOICE_USERS, session_id)
                        if voices in _TELEGRAM_VOICE_MILESTONES:
                            pipe.hincrby(_TELEGRAM_JOURNEY_MILESTONES, str(voices), 1)
                    await pipe.execute()
                    return
                except WatchError:
                    continue
        raise RuntimeError("could not record Telegram voice milestone after concurrent updates")

    async def record_error(self, session_id, service, outcome, *, platform=None, now=None):
        if load_test_active():
            return
        await self._add(session_id, platform, now, "error", _token(service), _token(outcome), {"count": 1}, False)

    async def remember_platform(self, session_id, platform):
        if load_test_active():
            return
        name = (platform or "").strip().lower()
        session = session_id.strip()
        if session.startswith("app-") and name in _MOBILE:
            await self._redis.set(_platform_key(session), name)

    async def platform_of(self, session_id):
        return await self._redis.get(_platform_key(session_id.strip()))

    async def snapshot(self, *, now=None):
        await self._backfill_historical_voice_users()
        day = metrics_day(now if now is not None else time.time())
        columns = []
        for client in _CLIENTS:
            raw = await self._redis.hgetall(_day_key(day, client))
            members = await self._redis.scard(_dau_key(day, client))
            bucket = {str(key): int(value) for key, value in raw.items() if str(key) != "currency"}
            if client == "unknown" and not bucket and not members:
                continue
            column = _column(client, bucket, int(members or 0))
            currency = raw.get("currency")
            if currency:
                column["costCurrency"] = currency
            chats = await self._redis.hgetall(_chat_key(day, client))
            column["chats"] = _chat_rows({str(key): int(value) for key, value in chats.items()})
            columns.append(column)
        async with self._redis.pipeline() as pipe:
            pipe.get(_TELEGRAM_JOURNEY_SINCE)
            pipe.scard(_TELEGRAM_JOURNEY_ONLY_START)
            pipe.hgetall(_TELEGRAM_JOURNEY_MILESTONES)
            pipe.scard(_TELEGRAM_HISTORICAL_VOICE_USERS)
            pipe.get(_TELEGRAM_HISTORICAL_BACKFILL_DONE)
            since, only_start, milestones, historical_only, historical_backfill = await pipe.execute()
        milestones = {int(key): int(value) for key, value in milestones.items()}
        milestones[1] = milestones.get(1, 0) + int(historical_only)
        return {
            "clients": columns,
            "telegramJourney": {
                **_telegram_journey_snapshot(since or "", int(only_start), milestones),
                **({"firstVoiceSince": _TELEGRAM_HISTORICAL_VOICE_SINCE} if historical_backfill == "1" else {}),
            },
            "llmRequestsByDay": await self._llm_requests_history(now if now is not None else time.time()),
        }

    def prometheus(self, snapshot):
        return _prometheus(snapshot)

    async def overview(self, from_day: str, to_day: str) -> dict[str, Any]:
        days = _overview_days(from_day, to_day)
        async with self._redis.pipeline() as pipe:
            for day in days:
                pipe.hgetall(_day_key(day, "telegram"))
            buckets = await pipe.execute()
        async with self._redis.pipeline() as pipe:
            for day in days:
                pipe.hgetall(f"metrics:day:{day}")
                pipe.hgetall(f"metrics:funnel:{day}")
            history_buckets = await pipe.execute()
        today_name = metrics_day(time.time())
        dau_keys = [key async for key in self._redis.scan_iter(match="metrics:dau:*", count=100)]
        recorded_dau_days = sorted(key.removeprefix("metrics:dau:") for key in dau_keys
                                   if re.fullmatch(r"metrics:dau:\d{4}-\d{2}-\d{2}", key)
                                   and key <= f"metrics:dau:{max(to_day, today_name)}")
        history_keys = [key async for key in self._redis.scan_iter(match="metrics:day:*", count=100)]
        first_history_day = min((key.removeprefix("metrics:day:") for key in history_keys
                                 if re.fullmatch(r"metrics:day:\d{4}-\d{2}-\d{2}", key)), default="")
        dau_since = min(recorded_dau_days, default="")
        history_since = min(filter(None, (first_history_day, dau_since)), default="")
        async with self._redis.pipeline() as pipe:
            for day in recorded_dau_days:
                pipe.smembers(f"metrics:dau:{day}")
            dau_members = await pipe.execute()
        dau_by_day = {
            day: {_telegram_chat_id(str(member)) for member in members
                  if client_for(str(member)) == "telegram"}
            for day, members in zip(recorded_dau_days, dau_members, strict=True)
        }
        raw_dau_by_day = {day: {str(member) for member in members}
                          for day, members in zip(recorded_dau_days, dau_members, strict=True)}
        legacy_sessions: set[str] = set()
        legacy_rows = []
        for index, day in enumerate(days):
            if day >= _LEGACY_BEFORE_DAY:
                continue
            raw, funnel = history_buckets[index * 2:index * 2 + 2]
            telegram_members = dau_by_day.get(day, set())
            legacy_sessions.update(telegram_members)
            legacy_rows.append(_legacy_overview_day(day, raw, funnel, len(telegram_members), day in dau_by_day))
        # Legacy funnel keys have no TTL and include starts as well as voice users.
        chats: set[str] = set()
        funnel_keys = []
        async for key in self._redis.scan_iter(match="metrics:funnel:user:*", count=200):
            session = str(key).removeprefix("metrics:funnel:user:")
            if client_for(session) == "telegram":
                chats.add(_telegram_chat_id(session))
                funnel_keys.append(key)
        async with self._redis.pipeline() as pipe:
            for key in funnel_keys:
                pipe.hgetall(key)
            funnel_users = await pipe.execute()
        cohorts: dict[str, dict[str, str]] = {}
        attributed_chats: set[str] = set()
        for key, user in zip(funnel_keys, funnel_users, strict=True):
            chat = _telegram_chat_id(str(key).removeprefix("metrics:funnel:user:"))
            cohort = cohorts.setdefault(chat, {})
            for field in ("first_day", "start_day", "activated_day", "engaged_day"):
                value = str(user.get(field) or "")
                if re.fullmatch(r"\d{4}-\d{2}-\d{2}", value) and (field not in cohort or value < cohort[field]):
                    cohort[field] = value
            source = str(user.get("source") or "").strip()
            source_day = str(user.get("first_day") or "9999-12-31")
            if source and ("source" not in cohort or
                           (source_day, source) < (cohort["_source_day"], cohort["source"])):
                cohort["source"] = source
                cohort["_source_day"] = source_day
            if "first_day" in cohort:
                attributed_chats.add(chat)
        async for key in self._redis.scan_iter(match="metrics:v2:telegram:journey:user:*", count=200):
            chats.add(_telegram_chat_id(str(key).removeprefix("metrics:v2:telegram:journey:user:")))
        first_seen = _cohort_counts(cohorts, "first_day")
        first_voice = _cohort_counts(cohorts, "activated_day")
        first_start = _cohort_counts(cohorts, "start_day")
        engaged = _cohort_counts(cohorts, "engaged_day")
        prior_active: set[str] = set()
        for day in recorded_dau_days:
            if day >= from_day:
                break
            prior_active.update(dau_by_day[day])
        today = date.fromisoformat(today_name)
        retention = []
        for offset in (1, 7, 30):
            eligible = returned = 0
            for chat, user in cohorts.items():
                activated = user.get("activated_day", "")
                if not (from_day <= activated <= to_day):
                    continue
                target = (date.fromisoformat(activated) + timedelta(days=offset)).isoformat()
                if target >= today.isoformat() or target < dau_since:
                    continue
                eligible += 1
                returned += chat in dau_by_day.get(target, set())
            retention.append({"day": offset, "eligible": eligible, "returned": returned})
        period_active: set[str] = set()
        period_sessions: set[str] = set()
        history_rows = []
        for index, day in enumerate(days):
            raw, funnel = history_buckets[index * 2:index * 2 + 2]
            active = dau_by_day.get(day, set())
            period_active.update(active)
            period_sessions.update(_telegram_chat_id(member) for member in raw_dau_by_day.get(day, set()))
            trailing = [recorded for recorded in recorded_dau_days if day >= recorded >=
                        (date.fromisoformat(day) - timedelta(days=6)).isoformat()]
            weekly = set().union(*(dau_by_day[recorded] for recorded in trailing)) if trailing else set()
            monthly_start = (date.fromisoformat(day) - timedelta(days=29)).isoformat()
            monthly = set().union(*(dau_by_day[recorded] for recorded in recorded_dau_days
                                     if monthly_start <= recorded <= day))
            tracked = bool(history_since and day >= history_since)
            history_rows.append({
                **_legacy_overview_day(day, raw, funnel, len(active), day in dau_by_day),
                "tracked": tracked,
                "newChats": first_seen.get(day, 0),
                "totalChatsToDate": sum(count for first, count in first_seen.items() if first <= day),
                "chatsWithTurnToDate": len(prior_active | active),
                "firstVoiceChats": first_voice.get(day, int(funnel.get("activated") or 0) if not first_voice else 0),
                "firstVoiceChatsToDate": sum(count for first, count in first_voice.items() if first <= day),
                "firstStarts": first_start.get(day, int(funnel.get("start") or 0) if not first_start else 0),
                "engagedChats": engaged.get(day, int(funnel.get("engaged") or 0) if not engaged else 0),
                "returningActiveChats": len(active & prior_active),
                "activeChats7d": len(weekly) if dau_since and day >= (date.fromisoformat(dau_since) + timedelta(days=6)).isoformat() else None,
                "activeChats30d": len(monthly) if dau_since and monthly_start >= dau_since else None,
            })
            prior_active.update(active)
        sources: dict[str, dict[str, int]] = {}
        for user in cohorts.values():
            source = user.get("source") or "direct"
            row = sources.setdefault(source, {"newChats": 0, "firstVoiceChats": 0})
            if from_day <= user.get("first_day", "") <= to_day:
                row["newChats"] += 1
            if from_day <= user.get("activated_day", "") <= to_day:
                row["firstVoiceChats"] += 1
        model_days = []
        async for key in self._redis.scan_iter(match="metrics:v2:day:*:telegram", count=100):
            day = str(key).removeprefix("metrics:v2:day:").removesuffix(":telegram")
            if re.fullmatch(r"\d{4}-\d{2}-\d{2}", day):
                model_days.append(day)
        return {
            "totalChats": len(chats),
            "modelSince": min(model_days, default=""),
            "voiceLlmSince": await self._redis.get(_VOICE_LLM_COST_SINCE) or "",
            "days": [_overview_day(day, bucket) for day, bucket in zip(days, buckets)],
            "legacyActiveChatsInPeriod": len(legacy_sessions),
            "legacyDays": legacy_rows,
            "historySince": history_since,
            "historyActiveChatsInPeriod": len(period_active),
            "historyActiveSessionsInPeriod": len(period_sessions),
            "historyDays": history_rows,
            "retention": retention,
            "sourceBreakdown": [{"source": source, **values} for source, values in sorted(sources.items())
                                if values["newChats"] or values["firstVoiceChats"]],
            "redisDauDays": [
                {"day": day, "tracked": bool(dau_since and day >= dau_since),
                 "activeChatsWithTurn": len(dau_by_day.get(day, set()))}
                for day in days
            ],
            "chatGrowthDays": [
                {"day": day, "tracked": bool(first_seen and day >= min(first_seen)),
                 "newChats": first_seen.get(day, 0),
                 "totalChatsToDate": sum(count for first, count in first_seen.items() if first <= day)}
                for day in days
            ],
            "chatGrowthUnattributed": len(chats - attributed_chats),
        }

    async def record_voice_llm_usage(self, session_id, calls, priced_calls, cost_micro_usd, *, now=None):
        if load_test_active() or calls <= 0:
            return
        await self._add(session_id, None, now, "voice_llm", "usage", "", {
            "calls": calls, "priced": priced_calls, "cost": cost_micro_usd,
        }, priced_calls > 0)
        await self._redis.setnx(_VOICE_LLM_COST_SINCE, metrics_day(now if now is not None else time.time()))

    async def _scope(self, session_id, platform, now):
        session = (session_id or current_session()).strip()
        if not session:
            return None, None, None
        saved = platform if platform is not None else await self.platform_of(session)
        return client_for(session, saved), metrics_day(now if now is not None else time.time()), session

    async def _add(self, session_id, platform, now, family, kind, model, fields, billed):
        client, day, _session = await self._scope(session_id, platform, now)
        if client is None:
            return
        pipe = self._redis.pipeline()
        key = _day_key(day, client)
        for name, value in fields.items():
            pipe.hincrby(key, f"{family}|{kind}|{model}|{name}", int(value))
        pipe.expire(key, _EVENTS_TTL_SECONDS)
        if billed:
            pipe.hset(key, "currency", "USD")
        pipe.lpush(_EVENTS_KEY, _event_json(day, client, family, kind, model, fields))
        pipe.ltrim(_EVENTS_KEY, 0, _EVENTS_LIMIT - 1)
        pipe.expire(_EVENTS_KEY, _EVENTS_TTL_SECONDS)
        await pipe.execute()

    async def _llm_requests_history(self, now: float) -> list[dict[str, Any]]:
        days = recent_days(now, _LLM_REQUEST_DAYS)
        async with self._redis.pipeline() as pipe:
            for day in days:
                for client in _CLIENTS:
                    pipe.hgetall(_day_key(day, client))
            raws = await pipe.execute()
        buckets = {}
        index = 0
        for day in days:
            for client in _CLIENTS:
                raw = raws[index]
                index += 1
                buckets[(day, client)] = {
                    str(key): int(value) for key, value in raw.items() if str(key) != "currency"
                }
        return _llm_request_rows(days, buckets)


def build_metrics_v2(settings: Any, redis: Redis | None = None) -> MetricsV2:
    if redis is not None:
        return RedisMetricsV2(redis)
    url = getattr(settings, "redis_url", None)
    if url:
        return RedisMetricsV2(Redis.from_url(url, decode_responses=True))
    return MemoryMetricsV2()


def _telegram_journey_event(session_id: str, action: str, event_id: str) -> bool:
    return (
        client_for(session_id) == "telegram"
        and action in {"start", "voice"}
        and bool(_TELEGRAM_MESSAGE_ID.fullmatch(event_id))
    )


def _telegram_journey_snapshot(since: str, only_start: int, milestones: dict[int, int]) -> dict[str, Any]:
    return {
        "since": since,
        "onlyStart": only_start,
        "atLeast": {str(threshold): milestones.get(threshold, 0) for threshold in _TELEGRAM_VOICE_MILESTONES},
    }


def _llm_calls(bucket: dict[str, int]) -> int:
    return sum(value for key, value in bucket.items() if key.startswith("llm|") and key.endswith("|calls"))


def _overview_days(from_day: str, to_day: str) -> list[str]:
    from datetime import date, timedelta
    start, end = date.fromisoformat(from_day), date.fromisoformat(to_day)
    if end < start or (end - start).days >= 30:
        raise ValueError("overview period must be 1-30 days")
    return [(start + timedelta(days=offset)).isoformat() for offset in range((end - start).days + 1)]


def _overview_day(day: str, raw: dict[str, Any]) -> dict[str, Any]:
    counts = {"llm": 0, "stt": 0, "tts": 0}
    llm_cost_micro = 0
    voice_llm = {"calls": 0, "priced": 0, "cost": 0}
    for key, value in raw.items():
        parts = str(key).split("|")
        if len(parts) != 4:
            continue
        if parts[0] == "voice_llm" and parts[3] in voice_llm:
            voice_llm[parts[3]] += int(value)
        elif parts[0] in counts and parts[3] == "calls":
            counts[parts[0]] += int(value)
        elif parts[0] == "llm" and parts[3] == "cost":
            llm_cost_micro += int(value)
    return {"day": day, "llmCalls": counts["llm"], "sttCalls": counts["stt"],
            "ttsCalls": counts["tts"], "llmCostMicro": llm_cost_micro,
            "voiceLlmCalls": voice_llm["calls"], "voiceLlmPricedCalls": voice_llm["priced"],
            "voiceLlmCostMicro": voice_llm["cost"], "tracked": bool(raw)}


def _legacy_overview_day(day: str, raw: dict[str, Any], funnel: dict[str, Any],
                         active_chats: int, has_members: bool) -> dict[str, Any]:
    return {
        "day": day, "tracked": bool(raw or funnel or has_members),
        "activeChatsWithTurn": active_chats,
        "completedTurns": int(raw.get("turns") or 0),
        "promptTokens": int(raw.get("prompt_tokens") or 0),
        "completionTokens": int(raw.get("completion_tokens") or 0),
        "sttMs": int(raw.get("stt_ms") or 0),
        "ttsChars": int(raw.get("tts_chars") or 0),
        "firstVoiceChats": int(funnel.get("activated") or 0),
    }


def _llm_requests_from_memory(days: dict[tuple[str, str], dict[str, int]], now: float) -> list[dict[str, Any]]:
    return _llm_request_rows(recent_days(now, _LLM_REQUEST_DAYS), days)


def _llm_request_rows(
    days: list[str], buckets: dict[tuple[str, str], dict[str, int]],
) -> list[dict[str, Any]]:
    rows = []
    for day in days:
        for client in _CLIENTS:
            bucket = buckets.get((day, client), {})
            if client == "unknown" and not bucket:
                continue
            rows.append({"day": day, "client": client, "calls": _llm_calls(bucket)})
    return rows


def _column(client: str, bucket: dict[str, int], dau: int) -> dict[str, Any]:
    prompt = completion = calls = cost = chars = stt_ms = 0
    realtime = {name: 0 for name in ("in_text", "in_audio", "out_text", "out_audio", "cached_text", "cached_audio")}
    actions: dict[str, int] = {}
    errors: dict[str, int] = {}
    models: dict[tuple[str, str], dict[str, int]] = {}
    for key, value in bucket.items():
        family, kind, model, name = (key.split("|", 3) + ["", "", "", ""])[:4]
        if family == "llm" and name == "prompt":
            prompt += value
            _model_row(models, kind, model)["promptTokens"] += value
        elif family == "llm" and name == "completion":
            completion += value
            _model_row(models, kind, model)["completionTokens"] += value
        elif family == "llm" and name == "calls":
            calls += value
        elif name == "cost":
            cost += value
            _model_row(models, kind, model)["costMicro"] += value
        elif family == "tts" and name == "chars":
            chars += value
        elif family == "tts" and name == "calls":
            calls += value
        elif family == "stt" and name == "ms":
            stt_ms += value
        elif family == "rt" and name in realtime:
            realtime[name] += value
        elif family == "action" and name == "count":
            actions[kind] = actions.get(kind, 0) + value
        elif family == "error" and name == "count":
            errors[f"{kind}:{model}"] = errors.get(f"{kind}:{model}", 0) + value
    return {
        "client": client,
        "dau": dau,
        "turns": bucket.get("turns", 0),
        "calls": calls,
        "llmCalls": _llm_calls(bucket),
        "costMicro": cost,
        "costCurrency": "USD" if cost else "",
        "promptTokens": prompt,
        "completionTokens": completion,
        "realtimeInText": realtime["in_text"],
        "realtimeInAudio": realtime["in_audio"],
        "realtimeOutText": realtime["out_text"],
        "realtimeOutAudio": realtime["out_audio"],
        "realtimeCachedText": realtime["cached_text"],
        "realtimeCachedAudio": realtime["cached_audio"],
        "sttSeconds": stt_ms / 1000,
        "ttsChars": chars,
        "actions": actions,
        "errors": errors,
        "chats": [],
        "models": [
            {"kind": kind, "model": model, **row}
            for (kind, model), row in sorted(models.items())
        ],
    }


def _model_row(models, kind, model):
    return models.setdefault((kind, model), {"promptTokens": 0, "completionTokens": 0, "costMicro": 0})


def _prometheus(snapshot: dict[str, Any]) -> str:
    lines = []
    journey = snapshot.get("telegramJourney")
    if journey is not None:
        lines.append("# TYPE speaking_telegram_voice_users gauge")
        lines.append(f"speaking_telegram_voice_users {journey['atLeast']['1']}")
    for column in snapshot.get("clients", []):
        client = column["client"]
        day = snapshot.get("day", "")
        labels = f'client="{client}",day="{day}"'
        lines.append(f"speaking_dau{{{labels}}} {column['dau']}")
        lines.append(f"speaking_turns{{{labels}}} {column['turns']}")
        if column["costCurrency"]:
            lines.append(
                f'speaking_cost_micro{{{labels},currency="{column["costCurrency"]}"}} {column["costMicro"]}'
            )
        lines.append(f"speaking_prompt_tokens{{{labels}}} {column['promptTokens']}")
        lines.append(f"speaking_completion_tokens{{{labels}}} {column['completionTokens']}")
        lines.append(f"speaking_llm_requests{{{labels}}} {column['llmCalls']}")
        lines.append(f"speaking_stt_seconds{{{labels}}} {column['sttSeconds']}")
        lines.append(f"speaking_tts_chars{{{labels}}} {column['ttsChars']}")
        for part, key in (
            ("in_text", "realtimeInText"), ("in_audio", "realtimeInAudio"),
            ("out_text", "realtimeOutText"), ("out_audio", "realtimeOutAudio"),
            ("cached_text", "realtimeCachedText"), ("cached_audio", "realtimeCachedAudio"),
        ):
            lines.append(f'speaking_realtime_tokens{{{labels},part="{part}"}} {column[key]}')
        for name, value in column["actions"].items():
            lines.append(f'speaking_actions{{{labels},action="{name}"}} {value}')
        for name, value in column["errors"].items():
            lines.append(f'speaking_errors{{{labels},outcome="{name}"}} {value}')
    seen = {(snapshot.get("day", ""), column["client"]) for column in snapshot.get("clients", [])}
    for row in snapshot.get("llmRequestsByDay", []):
        if (row["day"], row["client"]) in seen:
            continue
        seen.add((row["day"], row["client"]))
        labels = f'client="{row["client"]}",day="{row["day"]}"'
        lines.append(f"speaking_llm_requests{{{labels}}} {row['calls']}")
    return "\n".join(lines) + ("\n" if lines else "")


def _day_key(day: str, client: str) -> str:
    return f"metrics:v2:day:{day}:{client}"


def _dau_key(day: str, client: str) -> str:
    return f"metrics:v2:dau:{day}:{client}"


def _chat_key(day: str, client: str) -> str:
    return f"metrics:v2:chats:{day}:{client}"


def _chat_rows(counts: dict) -> list[dict[str, Any]]:
    ranked = sorted(counts.items(), key=lambda item: (-int(item[1]), str(item[0])))[:50]
    return [{"session": str(session), "turns": int(turns)} for session, turns in ranked]


def _platform_key(session_id: str) -> str:
    return f"metrics:v2:platform:{session_id}"


def _event_json(day, client, family, kind, model, fields=None) -> str:
    return json.dumps({
        "day": day, "client": client, "family": family, "kind": kind, "model": model, **(fields or {}),
    }, ensure_ascii=True)


def _token(value: str) -> str:
    text = "".join(ch if ch.isalnum() or ch in "-_:" else "-" for ch in (value or "").strip().lower())
    return text[:80]
