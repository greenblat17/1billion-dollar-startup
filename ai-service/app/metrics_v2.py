from __future__ import annotations

import json
import re
import time
from contextvars import ContextVar, Token
from typing import Any
from redis.asyncio import Redis
from redis.exceptions import WatchError

from app.metrics import metrics_day

_EVENTS_KEY = "metrics:v2:events"
_EVENTS_LIMIT = 10_000
_EVENTS_TTL_SECONDS = 30 * 24 * 60 * 60
_CLIENTS = ("telegram", "android", "ios", "desktop", "unknown")
_MOBILE = {"android", "ios", "desktop"}
_TELEGRAM_VOICE_MILESTONES = (1, 3, 5, 10, 20)
_TELEGRAM_RECEIPT_TTL = 7 * 24 * 60 * 60
_TELEGRAM_JOURNEY_SINCE = "metrics:v2:telegram:journey:since"
_TELEGRAM_JOURNEY_ONLY_START = "metrics:v2:telegram:journey:only-start"
_TELEGRAM_JOURNEY_MILESTONES = "metrics:v2:telegram:journey:milestones"
_TELEGRAM_MESSAGE_ID = re.compile(r"message:[0-9]+\Z")
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
    usage = getattr(response, "usage", None)
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

    async def record_llm(self, session_id, kind, model, prompt_tokens, completion_tokens, cost, *, platform=None, now=None):
        await self._add(session_id, platform, now, "llm", kind, model, {
            "prompt": prompt_tokens, "completion": completion_tokens, "calls": 1,
            **({} if cost is None else {"cost": cost}),
        }, cost is not None)

    async def record_tts(self, session_id, model, chars, cost, *, platform=None, now=None):
        await self._add(session_id, platform, now, "tts", "tts", model, {
            "chars": chars, "calls": 1, **({} if cost is None else {"cost": cost}),
        }, cost is not None)

    async def record_stt(self, session_id, model, seconds, *, platform=None, now=None):
        await self._add(session_id, platform, now, "stt", "stt", model, {
            "ms": int(round(max(seconds, 0) * 1000)), "calls": 1,
        }, False)

    async def record_realtime(self, session_id, model, counts, *, platform=None, now=None):
        await self._add(session_id, platform, now, "rt", "realtime", model, {key: int(counts.get(key) or 0) for key in (
            "in_text", "in_audio", "out_text", "out_audio", "cached_text", "cached_audio",
        )} | {"calls": 1}, False)
        await self.record_turn(session_id, platform=platform, now=now)

    async def record_transcript(self, session_id, model, amount, *, platform=None, now=None):
        await self._add(session_id, platform, now, "transcript", "stt", model, {"amount": amount, "calls": 1}, False)

    async def record_turn(self, session_id, *, platform=None, now=None):
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
        name = _token(action) or "other"
        await self._add(session_id, platform, now, "action", name, "", {"count": 1}, False)

    async def record_telegram_message(self, session_id, action, event_id, *, now=None):
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
        await self._add(session_id, platform, now, "error", _token(service), _token(outcome), {"count": 1}, False)

    async def remember_platform(self, session_id, platform):
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
        }

    def prometheus(self, snapshot):
        return _prometheus(snapshot)

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

    async def record_llm(self, session_id, kind, model, prompt_tokens, completion_tokens, cost, *, platform=None, now=None):
        await self._add(session_id, platform, now, "llm", kind, model, {
            "prompt": prompt_tokens, "completion": completion_tokens, "calls": 1,
            **({} if cost is None else {"cost": cost}),
        }, cost is not None)

    async def record_tts(self, session_id, model, chars, cost, *, platform=None, now=None):
        await self._add(session_id, platform, now, "tts", "tts", model, {
            "chars": chars, "calls": 1, **({} if cost is None else {"cost": cost}),
        }, cost is not None)

    async def record_stt(self, session_id, model, seconds, *, platform=None, now=None):
        await self._add(session_id, platform, now, "stt", "stt", model, {
            "ms": int(round(max(seconds, 0) * 1000)), "calls": 1,
        }, False)

    async def record_realtime(self, session_id, model, counts, *, platform=None, now=None):
        await self._add(session_id, platform, now, "rt", "realtime", model, {key: int(counts.get(key) or 0) for key in (
            "in_text", "in_audio", "out_text", "out_audio", "cached_text", "cached_audio",
        )} | {"calls": 1}, False)
        await self.record_turn(session_id, platform=platform, now=now)

    async def record_transcript(self, session_id, model, amount, *, platform=None, now=None):
        await self._add(session_id, platform, now, "transcript", "stt", model, {"amount": amount, "calls": 1}, False)

    async def record_turn(self, session_id, *, platform=None, now=None):
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
        await self._add(session_id, platform, now, "action", _token(action) or "other", "", {"count": 1}, False)

    async def record_telegram_message(self, session_id, action, event_id, *, now=None):
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
                            if voices == 0:
                                pipe.sadd(_TELEGRAM_JOURNEY_ONLY_START, session_id)
                    else:
                        voices += 1
                        pipe.hset(user_key, "voices", voices)
                        pipe.srem(_TELEGRAM_JOURNEY_ONLY_START, session_id)
                        if voices in _TELEGRAM_VOICE_MILESTONES:
                            pipe.hincrby(_TELEGRAM_JOURNEY_MILESTONES, str(voices), 1)
                    await pipe.execute()
                    return
                except WatchError:
                    continue
        raise RuntimeError("could not record Telegram voice milestone after concurrent updates")

    async def record_error(self, session_id, service, outcome, *, platform=None, now=None):
        await self._add(session_id, platform, now, "error", _token(service), _token(outcome), {"count": 1}, False)

    async def remember_platform(self, session_id, platform):
        name = (platform or "").strip().lower()
        session = session_id.strip()
        if session.startswith("app-") and name in _MOBILE:
            await self._redis.set(_platform_key(session), name)

    async def platform_of(self, session_id):
        return await self._redis.get(_platform_key(session_id.strip()))

    async def snapshot(self, *, now=None):
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
            since, only_start, milestones = await pipe.execute()
        return {
            "clients": columns,
            "telegramJourney": _telegram_journey_snapshot(
                since or "", int(only_start), {int(key): int(value) for key, value in milestones.items()},
            ),
        }

    def prometheus(self, snapshot):
        return _prometheus(snapshot)

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
