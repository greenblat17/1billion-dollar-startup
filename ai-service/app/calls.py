"""A practice call is one sealed conversation, separate from the rolling dialogue."""
from __future__ import annotations

import asyncio
import json
import time
from copy import deepcopy
from datetime import datetime
from typing import Any, Awaitable, Callable
from uuid import uuid4
from zoneinfo import ZoneInfo

from redis.asyncio import Redis
from app.audit_artifacts import recorded_at

from app.metrics import METRICS_TIMEZONE

_TZ = ZoneInfo(METRICS_TIMEZONE)
RAW_CONTENT_SECONDS = 7 * 24 * 60 * 60
GoalLookup = Callable[[str], Awaitable[int | None]]


def moscow_day(moment: float | None = None) -> str:
    return datetime.fromtimestamp(moment if moment is not None else time.time(), _TZ).date().isoformat()


class CallStore:
    def __init__(self, redis: Redis | None = None, goal_of: GoalLookup | None = None, clock: Callable[[], float] | None = None) -> None:
        self._redis = redis
        self._goal_of = goal_of
        self._clock = clock or time.time
        self._calls: dict[str, dict] = {}
        self._open: dict[str, str] = {}
        self._days: dict[str, dict] = {}
        self._locks: dict[str, asyncio.Lock] = {}

    def lock(self, session_id: str) -> asyncio.Lock:
        return self._locks.setdefault(session_id, asyncio.Lock())

    async def open(self, session_id: str) -> dict[str, Any]:
        session_id = _session_id(session_id)
        async with self.lock(session_id):
            day = moscow_day(self._clock())
            unseen = await self._seal_previous_day(session_id, day)
            call = await self._live(session_id)
            already_active = bool(call and (call["turns"] or call.get("openingDelivered")))
            if call is None:
                call = _new_call(session_id, day, self._clock())
                await self._write_call(call)
                await self._set_open(session_id, call["id"])
                await self._append_day(session_id, day, call["id"])
            return {**await self._summary(session_id, call, unseen=unseen, crossed=False), "alreadyActive": already_active}

    async def is_open_today(self, session_id: str) -> bool:
        session_id = _session_id(session_id)
        async with self.lock(session_id):
            call = await self._live(session_id)
            return bool(
                call is not None and call["day"] == moscow_day(self._clock())
                and (call["turns"] or call.get("openingDelivered"))
            )

    async def summary(self, session_id: str) -> dict[str, Any] | None:
        session_id = _session_id(session_id)
        async with self.lock(session_id):
            call = await self._live(session_id)
            if call is None:
                return None
            return await self._summary(session_id, call, unseen=None, crossed=False)

    async def append_turn(
        self,
        session_id: str,
        transcript: str,
        reply: str,
        corrections: list[dict[str, str]],
        seconds: float,
        words: list[dict[str, Any]],
    ) -> dict[str, Any] | None:
        session_id = _session_id(session_id)
        text = transcript.strip()
        if not text or seconds <= 0:
            return await self.summary(session_id)
        async with self.lock(session_id):
            call = await self._live(session_id)
            if call is None:
                return None
            before = await self._today_seconds(session_id, call["day"])
            call["turns"].append({
                "transcript": text,
                "reply": reply,
                "receivedAt": recorded_at(self._clock()),
                "corrections": [dict(item) for item in corrections if isinstance(item, dict)],
                "seconds": seconds,
                "words": [dict(word) for word in words if isinstance(word, dict)],
            })
            call["seconds"] = round(float(call.get("seconds") or 0) + seconds, 3)
            await self._write_call(call)
            after = before + seconds
            goal = await self._goal_seconds(session_id)
            day = await self._day(session_id, call["day"])
            crossed = bool(goal) and before < goal <= after and not day.get("goalNoted")
            if crossed:
                day["goalNoted"] = True
                await self._write_day(session_id, call["day"], day)
            return await self._summary(session_id, call, unseen=None, crossed=crossed)

    async def seal(self, session_id: str) -> str | None:
        session_id = _session_id(session_id)
        async with self.lock(session_id):
            call = await self._live(session_id)
            if call is None:
                return None
            await self._close(session_id, call)
            return str(call["id"])

    async def note_telegram_voice(self, session_id: str, call_id: str, message_id: int) -> bool:
        """Remember delivered user voices and identify the first reply to a call starter."""
        session_id = _session_id(session_id)
        if message_id <= 0:
            raise ValueError("messageId must be positive")
        async with self.lock(session_id):
            call = await self._live(session_id)
            if call is None or call["id"] != call_id:
                raise ValueError("call is not open")
            first = call.get("firstTelegramVoiceId")
            if first is None:
                call["firstTelegramVoiceId"] = message_id
            if (call.get("lastTelegramVoiceId") or 0) <= message_id:
                call["lastTelegramVoiceId"] = message_id
            await self._write_call(call)
            return bool(call.get("openingDelivered") and call["firstTelegramVoiceId"] == message_id)

    async def get(self, call_id: str) -> dict[str, Any] | None:
        call = await self._read_call(call_id)
        return deepcopy(call) if call is not None else None

    async def save_opening(self, call_id: str, question: str) -> None:
        call = await self._read_call(call_id)
        if call is None:
            raise KeyError(call_id)
        async with self.lock(str(call["sessionId"])):
            current = await self._read_call(call_id)
            if current is None or current["status"] != "open":
                raise ValueError("call is closed")
            if not current.get("openingQuestion"):
                current["openingQuestion"] = question
                await self._write_call(current)

    async def mark_opening_delivered(self, call_id: str) -> tuple[str, str] | None:
        call = await self._read_call(call_id)
        if call is None:
            raise KeyError(call_id)
        async with self.lock(str(call["sessionId"])):
            current = await self._read_call(call_id)
            if current is None or not current.get("openingQuestion"):
                raise ValueError("call has no opening")
            if current.get("openingDelivered"):
                return None
            current["openingDelivered"] = True
            await self._write_call(current)
            return str(current["sessionId"]), str(current["openingQuestion"])

    async def save_review(self, call_id: str, review: dict[str, Any]) -> bool:
        call = await self._read_call(call_id)
        if call is None or call.get("status") != "closed":
            return False
        async with self.lock(str(call["sessionId"])):
            current = await self._read_call(call_id)
            if current is None or current.get("status") != "closed":
                return False
            current["review"] = deepcopy(review)
            await self._write_call(current)
            return True

    async def aclose(self) -> None:
        if self._redis is not None:
            await self._redis.aclose()

    async def prune_all(self) -> int:
        if self._redis is None:
            for call in self._calls.values():
                _expire_call_content(call, self._clock())
            return len(self._calls)
        changed = 0
        async for key in self._redis.scan_iter(match="call:*", count=100):
            if key.startswith(("call:open:", "call:day:")):
                continue
            raw = await self._redis.get(key)
            if not raw:
                continue
            call = json.loads(raw)
            if not isinstance(call, dict) or "turns" not in call:
                continue
            async with self.lock(str(call["sessionId"])):
                raw = await self._redis.get(key)
                if not raw:
                    continue
                call = json.loads(raw)
                before = json.dumps(call, sort_keys=True)
                _expire_call_content(call, self._clock())
                if json.dumps(call, sort_keys=True) != before:
                    await self._redis.set(key, json.dumps(call))
                    changed += 1
        return changed

    async def _seal_previous_day(self, session_id: str, day: str) -> str | None:
        call = await self._live(session_id)
        if call is None or call.get("day") == day:
            return None
        await self._close(session_id, call)
        if call.get("reviewOffered"):
            return None
        call["reviewOffered"] = True
        await self._write_call(call)
        return str(call["id"])

    async def _close(self, session_id: str, call: dict) -> None:
        call["status"] = "closed"
        call["endedUnix"] = self._clock()
        call["todaySeconds"] = await self._today_seconds(session_id, str(call["day"]))
        call["goalSeconds"] = await self._goal_seconds(session_id)
        await self._write_call(call)
        await self._set_open(session_id, None)

    async def _summary(self, session_id: str, call: dict, unseen: str | None, crossed: bool) -> dict[str, Any]:
        return {
            "callId": call["id"],
            "todaySeconds": await self._today_seconds(session_id, str(call["day"])),
            "goalSeconds": await self._goal_seconds(session_id),
            "goalJustCrossed": crossed,
            "unseenCallId": unseen,
        }

    async def _today_seconds(self, session_id: str, day: str) -> float:
        total = 0.0
        for call_id in (await self._day(session_id, day)).get("ids") or []:
            call = await self._read_call(str(call_id))
            if call is not None:
                total += float(call.get("seconds") or 0)
        return round(total, 3)

    async def _goal_seconds(self, session_id: str) -> float:
        if self._goal_of is None:
            return 0.0
        minutes = await self._goal_of(session_id)
        if minutes not in {5, 10, 15}:
            return 0.0
        return float(minutes * 60)

    async def _live(self, session_id: str) -> dict | None:
        call_id = await self._open_id(session_id)
        if not call_id:
            return None
        call = await self._read_call(call_id)
        if call is None or call.get("status") != "open":
            await self._set_open(session_id, None)
            return None
        return call

    async def _day(self, session_id: str, day: str) -> dict:
        if self._redis is not None:
            raw = await self._redis.get(_day_key(session_id, day))
            loaded = json.loads(raw) if raw else None
        else:
            loaded = self._days.get(_day_key(session_id, day))
        if not isinstance(loaded, dict):
            return {"ids": [], "goalNoted": False}
        loaded.setdefault("ids", [])
        loaded.setdefault("goalNoted", False)
        return loaded

    async def _append_day(self, session_id: str, day: str, call_id: str) -> None:
        day_state = await self._day(session_id, day)
        if call_id not in day_state["ids"]:
            day_state["ids"].append(call_id)
        await self._write_day(session_id, day, day_state)

    async def _write_day(self, session_id: str, day: str, day_state: dict) -> None:
        key = _day_key(session_id, day)
        if self._redis is not None:
            await self._redis.set(key, json.dumps(day_state))
        else:
            self._days[key] = deepcopy(day_state)

    async def _open_id(self, session_id: str) -> str | None:
        if self._redis is not None:
            return await self._redis.get(_open_key(session_id))
        return self._open.get(session_id)

    async def _set_open(self, session_id: str, call_id: str | None) -> None:
        if self._redis is not None:
            if call_id is None:
                await self._redis.delete(_open_key(session_id))
            else:
                await self._redis.set(_open_key(session_id), call_id)
        elif call_id is None:
            self._open.pop(session_id, None)
        else:
            self._open[session_id] = call_id

    async def _read_call(self, call_id: str) -> dict | None:
        if self._redis is not None:
            raw = await self._redis.get(_call_key(call_id))
            loaded = json.loads(raw) if raw else None
        else:
            loaded = self._calls.get(call_id)
        return _expire_call_content(deepcopy(loaded), self._clock()) if isinstance(loaded, dict) else None

    async def _write_call(self, call: dict) -> None:
        call = _expire_call_content(call, self._clock())
        if self._redis is not None:
            await self._redis.set(_call_key(str(call["id"])), json.dumps(call))
        else:
            self._calls[str(call["id"])] = deepcopy(call)


def _new_call(session_id: str, day: str, started: float) -> dict:
    return {
        "id": uuid4().hex,
        "sessionId": session_id,
        "status": "open",
        "day": day,
        "startedUnix": started,
        "endedUnix": None,
        "seconds": 0.0,
        "turns": [],
        "openingQuestion": None,
        "openingDelivered": False,
        "review": None,
        "reviewOffered": False,
        "todaySeconds": 0.0,
        "goalSeconds": 0.0,
    }


def _expire_call_content(call: dict, now: float) -> dict:
    cutoff = now - RAW_CONTENT_SECONDS
    call["turns"] = [turn for turn in call.get("turns") or [] if isinstance(turn, dict)
                     and isinstance(turn.get("receivedAt"), (int, float)) and turn["receivedAt"] > cutoff]
    if float(call.get("startedUnix") or 0) <= cutoff:
        call["openingQuestion"] = None
    if not call["turns"]:
        call["review"] = None
    return call


def _session_id(value: str) -> str:
    text = str(value or "").strip()
    if not text:
        raise ValueError("sessionId required")
    return text


def _call_key(call_id: str) -> str:
    return f"call:{call_id}"


def _open_key(session_id: str) -> str:
    return f"call:open:{session_id}"


def _day_key(session_id: str, day: str) -> str:
    return f"call:day:{session_id}:{day}"
