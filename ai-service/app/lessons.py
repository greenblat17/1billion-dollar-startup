from __future__ import annotations

import asyncio
import json
from datetime import datetime, timezone
from typing import Any, Protocol
from uuid import uuid4

from redis.asyncio import Redis


class LessonStore(Protocol):
    async def open(self, user_session_id: str) -> str: ...

    async def current(self, user_session_id: str) -> str | None: ...

    async def append_turn(
        self,
        user_session_id: str,
        transcript: str,
        reply_text: str,
        corrections: list[dict[str, str]],
    ) -> None: ...

    async def seal(self, user_session_id: str) -> bool: ...

    async def get(self, lesson_id: str) -> dict[str, Any] | None: ...

    async def aclose(self) -> None: ...


class MemoryLessonStore:
    def __init__(self) -> None:
        self._lessons: dict[str, dict[str, Any]] = {}
        self._open: dict[str, str] = {}
        self._by_user: dict[str, list[str]] = {}
        self._locks: dict[str, asyncio.Lock] = {}
        self._meta = asyncio.Lock()

    async def open(self, user_session_id: str) -> str:
        user_session_id = _require_session_id(user_session_id)
        async with await self._lock_for(user_session_id):
            existing = self._live_open(user_session_id)
            if existing is not None:
                return existing
            lesson_id = str(uuid4())
            self._lessons[lesson_id] = _new_lesson(user_session_id)
            self._open[user_session_id] = lesson_id
            self._by_user.setdefault(user_session_id, []).append(lesson_id)
            return lesson_id

    async def current(self, user_session_id: str) -> str | None:
        user_session_id = _require_session_id(user_session_id)
        async with await self._lock_for(user_session_id):
            return self._live_open(user_session_id)

    async def append_turn(
        self,
        user_session_id: str,
        transcript: str,
        reply_text: str,
        corrections: list[dict[str, str]],
    ) -> None:
        user_session_id = _require_session_id(user_session_id)
        async with await self._lock_for(user_session_id):
            lesson_id = self._live_open(user_session_id)
            if lesson_id is None:
                return
            lesson = self._lessons[lesson_id]
            lesson["turns"].append(_turn(transcript, reply_text, corrections))

    async def seal(self, user_session_id: str) -> bool:
        user_session_id = _require_session_id(user_session_id)
        async with await self._lock_for(user_session_id):
            lesson_id = self._live_open(user_session_id)
            if lesson_id is None:
                self._open.pop(user_session_id, None)
                return False
            lesson = self._lessons[lesson_id]
            lesson["status"] = "closed"
            lesson["endedAt"] = _now()
            self._open.pop(user_session_id, None)
            return True

    async def get(self, lesson_id: str) -> dict[str, Any] | None:
        lesson = self._lessons.get(lesson_id)
        if lesson is None:
            return None
        return _copy_lesson(lesson)

    async def aclose(self) -> None:
        return None

    async def _lock_for(self, user_session_id: str) -> asyncio.Lock:
        async with self._meta:
            lock = self._locks.get(user_session_id)
            if lock is None:
                lock = asyncio.Lock()
                self._locks[user_session_id] = lock
            return lock

    def _live_open(self, user_session_id: str) -> str | None:
        lesson_id = self._open.get(user_session_id)
        if lesson_id is None:
            return None
        lesson = self._lessons.get(lesson_id)
        if lesson is None or lesson.get("status") != "open":
            self._open.pop(user_session_id, None)
            return None
        return lesson_id


class RedisLessonStore:
    def __init__(self, redis: Redis) -> None:
        self._redis = redis
        self._locks: dict[str, asyncio.Lock] = {}
        self._meta = asyncio.Lock()

    async def open(self, user_session_id: str) -> str:
        user_session_id = _require_session_id(user_session_id)
        async with await self._lock_for(user_session_id):
            existing = await self._live_open(user_session_id)
            if existing is not None:
                return existing
            lesson_id = str(uuid4())
            await self._redis.set(_lesson_key(lesson_id), _dump_lesson(_new_lesson(user_session_id)))
            await self._redis.set(_open_key(user_session_id), lesson_id)
            await self._redis.rpush(_by_user_key(user_session_id), lesson_id)
            return lesson_id

    async def current(self, user_session_id: str) -> str | None:
        user_session_id = _require_session_id(user_session_id)
        async with await self._lock_for(user_session_id):
            return await self._live_open(user_session_id)

    async def append_turn(
        self,
        user_session_id: str,
        transcript: str,
        reply_text: str,
        corrections: list[dict[str, str]],
    ) -> None:
        user_session_id = _require_session_id(user_session_id)
        async with await self._lock_for(user_session_id):
            lesson_id = await self._live_open(user_session_id)
            if lesson_id is None:
                return
            raw = await self._redis.get(_lesson_key(lesson_id))
            if raw is None:
                await self._redis.delete(_open_key(user_session_id))
                return
            lesson = _load_lesson(raw)
            if lesson.get("status") != "open":
                await self._redis.delete(_open_key(user_session_id))
                return
            lesson["turns"].append(_turn(transcript, reply_text, corrections))
            await self._redis.set(_lesson_key(lesson_id), _dump_lesson(lesson))

    async def seal(self, user_session_id: str) -> bool:
        user_session_id = _require_session_id(user_session_id)
        async with await self._lock_for(user_session_id):
            lesson_id = await self._live_open(user_session_id)
            if lesson_id is None:
                await self._redis.delete(_open_key(user_session_id))
                return False
            raw = await self._redis.get(_lesson_key(lesson_id))
            if raw is None:
                await self._redis.delete(_open_key(user_session_id))
                return False
            lesson = _load_lesson(raw)
            lesson["status"] = "closed"
            lesson["endedAt"] = _now()
            await self._redis.set(_lesson_key(lesson_id), _dump_lesson(lesson))
            await self._redis.delete(_open_key(user_session_id))
            return True

    async def get(self, lesson_id: str) -> dict[str, Any] | None:
        raw = await self._redis.get(_lesson_key(lesson_id))
        if raw is None:
            return None
        return _copy_lesson(_load_lesson(raw))

    async def aclose(self) -> None:
        await self._redis.aclose()

    async def _lock_for(self, user_session_id: str) -> asyncio.Lock:
        async with self._meta:
            lock = self._locks.get(user_session_id)
            if lock is None:
                lock = asyncio.Lock()
                self._locks[user_session_id] = lock
            return lock

    async def _live_open(self, user_session_id: str) -> str | None:
        lesson_id = await self._redis.get(_open_key(user_session_id))
        if not lesson_id:
            return None
        raw = await self._redis.get(_lesson_key(lesson_id))
        if raw is None:
            await self._redis.delete(_open_key(user_session_id))
            return None
        lesson = _load_lesson(raw)
        if lesson.get("status") != "open":
            await self._redis.delete(_open_key(user_session_id))
            return None
        return str(lesson_id)


def build_lesson_store(settings: Any, redis: Redis | None = None) -> LessonStore:
    if redis is not None:
        return RedisLessonStore(redis)
    url = getattr(settings, "redis_url", None)
    if url:
        return RedisLessonStore(Redis.from_url(url, decode_responses=True))
    return MemoryLessonStore()


def _require_session_id(user_session_id: str) -> str:
    text = (user_session_id or "").strip()
    if not text:
        raise ValueError("session id required")
    return text


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _new_lesson(user_session_id: str) -> dict[str, Any]:
    return {
        "userSessionId": user_session_id,
        "startedAt": _now(),
        "endedAt": None,
        "status": "open",
        "turns": [],
    }


def _turn(transcript: str, reply_text: str, corrections: list[dict[str, str]]) -> dict[str, Any]:
    return {
        "transcript": transcript,
        "replyText": reply_text,
        "corrections": [dict(item) for item in corrections],
    }


def _copy_lesson(lesson: dict[str, Any]) -> dict[str, Any]:
    turns = lesson.get("turns") if isinstance(lesson.get("turns"), list) else []
    return {
        "userSessionId": lesson.get("userSessionId"),
        "startedAt": lesson.get("startedAt"),
        "endedAt": lesson.get("endedAt"),
        "status": lesson.get("status"),
        "turns": [
            _turn(
                str(turn.get("transcript") or ""),
                str(turn.get("replyText") or ""),
                list(turn.get("corrections") or []),
            )
            for turn in turns
            if isinstance(turn, dict)
        ],
    }


def _open_key(user_session_id: str) -> str:
    return f"lesson:open:{user_session_id}"


def _lesson_key(lesson_id: str) -> str:
    return f"lesson:{lesson_id}"


def _by_user_key(user_session_id: str) -> str:
    return f"lessons:by_user:{user_session_id}"


def _dump_lesson(lesson: dict[str, Any]) -> str:
    return json.dumps(lesson)


def _load_lesson(raw: str | bytes) -> dict[str, Any]:
    if isinstance(raw, bytes):
        raw = raw.decode("utf-8")
    payload = json.loads(raw)
    if not isinstance(payload, dict):
        return _new_lesson("")
    payload.setdefault("turns", [])
    return payload
