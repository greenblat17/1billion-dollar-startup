from __future__ import annotations

import asyncio
import json
import time
from datetime import datetime, time as clock, timezone
from typing import Any, Protocol
from uuid import uuid4
from zoneinfo import ZoneInfo

from redis.asyncio import Redis

from app.metrics import FUNNEL_WINDOW_DAYS, METRICS_TIMEZONE, recent_days


class LessonStore(Protocol):
    async def open(self, user_session_id: str) -> str: ...

    async def current(self, user_session_id: str) -> str | None: ...

    async def append_turn(
        self,
        user_session_id: str,
        transcript: str,
        reply_text: str,
        corrections: list[dict[str, str]],
        speech_seconds: float = 0.0,
    ) -> None: ...

    async def seal(self, user_session_id: str) -> str | None: ...

    async def get(self, lesson_id: str) -> dict[str, Any] | None: ...

    async def stored_scores(self, lesson_id: str) -> dict[str, int] | None: ...

    async def save_scores(self, lesson_id: str, scores: dict[str, int]) -> bool: ...

    async def recent_sealed(self, *, now: float | None = None) -> list[dict[str, Any]]: ...

    async def aclose(self) -> None: ...


class MemoryLessonStore:
    def __init__(self) -> None:
        self._lessons: dict[str, dict[str, Any]] = {}
        self._open: dict[str, str] = {}
        self._by_user: dict[str, list[str]] = {}
        self._sealed: dict[str, float] = {}
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
        speech_seconds: float = 0.0,
    ) -> None:
        user_session_id = _require_session_id(user_session_id)
        async with await self._lock_for(user_session_id):
            lesson_id = self._live_open(user_session_id)
            if lesson_id is None:
                return
            lesson = self._lessons[lesson_id]
            lesson["turns"].append(_turn(transcript, reply_text, corrections, speech_seconds))

    async def seal(self, user_session_id: str) -> str | None:
        user_session_id = _require_session_id(user_session_id)
        async with await self._lock_for(user_session_id):
            lesson_id = self._live_open(user_session_id)
            if lesson_id is None:
                self._open.pop(user_session_id, None)
                return None
            lesson = self._lessons[lesson_id]
            ended_unix = time.time()
            lesson["status"] = "closed"
            lesson["endedAt"] = _now()
            lesson["endedUnix"] = ended_unix
            self._open.pop(user_session_id, None)
            self._sealed[lesson_id] = ended_unix
            return lesson_id

    async def stored_scores(self, lesson_id: str) -> dict[str, int] | None:
        lesson = self._lessons.get(lesson_id)
        if lesson is None:
            return None
        return _copy_scores(lesson)

    async def save_scores(self, lesson_id: str, scores: dict[str, int]) -> bool:
        lesson = self._lessons.get(lesson_id)
        if lesson is None or lesson.get("status") != "closed":
            return False
        lesson["scores"] = {key: int(scores[key]) for key in ("grammar", "vocabulary", "fluency")}
        return True

    async def recent_sealed(self, *, now: float | None = None) -> list[dict[str, Any]]:
        start = _window_start(now if now is not None else time.time())
        rows = []
        for lesson_id, ended_unix in self._sealed.items():
            if ended_unix < start:
                continue
            lesson = self._lessons.get(lesson_id)
            if lesson is None or lesson.get("status") != "closed":
                continue
            rows.append(_sealed_row(lesson, ended_unix))
        rows.sort(key=lambda row: float(row["endedUnix"]), reverse=True)
        return rows

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
        speech_seconds: float = 0.0,
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
            lesson["turns"].append(_turn(transcript, reply_text, corrections, speech_seconds))
            await self._redis.set(_lesson_key(lesson_id), _dump_lesson(lesson))

    async def seal(self, user_session_id: str) -> str | None:
        user_session_id = _require_session_id(user_session_id)
        async with await self._lock_for(user_session_id):
            lesson_id = await self._live_open(user_session_id)
            if lesson_id is None:
                await self._redis.delete(_open_key(user_session_id))
                return None
            raw = await self._redis.get(_lesson_key(lesson_id))
            if raw is None:
                await self._redis.delete(_open_key(user_session_id))
                return None
            lesson = _load_lesson(raw)
            ended_unix = time.time()
            lesson["status"] = "closed"
            lesson["endedAt"] = _now()
            lesson["endedUnix"] = ended_unix
            await self._redis.set(_lesson_key(lesson_id), _dump_lesson(lesson))
            await self._redis.delete(_open_key(user_session_id))
            await self._redis.zadd(_sealed_key(), {lesson_id: ended_unix})
            return lesson_id

    async def stored_scores(self, lesson_id: str) -> dict[str, int] | None:
        lesson = await self.get(lesson_id)
        if lesson is None:
            return None
        return _copy_scores(lesson)

    async def save_scores(self, lesson_id: str, scores: dict[str, int]) -> bool:
        raw = await self._redis.get(_lesson_key(lesson_id))
        if raw is None:
            return False
        lesson = _load_lesson(raw)
        if lesson.get("status") != "closed":
            return False
        lesson["scores"] = {key: int(scores[key]) for key in ("grammar", "vocabulary", "fluency")}
        await self._redis.set(_lesson_key(lesson_id), _dump_lesson(lesson))
        return True

    async def recent_sealed(self, *, now: float | None = None) -> list[dict[str, Any]]:
        start = _window_start(now if now is not None else time.time())
        scored = await self._redis.zrevrangebyscore(_sealed_key(), "+inf", start, withscores=True)
        rows = []
        for lesson_id, ended_unix in scored:
            lesson = await self.get(str(lesson_id))
            if lesson is None or lesson.get("status") != "closed":
                continue
            rows.append(_sealed_row(lesson, float(ended_unix)))
        return rows

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


def _turn(
    transcript: str,
    reply_text: str,
    corrections: list[dict[str, str]],
    speech_seconds: float = 0.0,
) -> dict[str, Any]:
    return {
        "transcript": transcript,
        "replyText": reply_text,
        "corrections": [dict(item) for item in corrections],
        "speechSeconds": _speech_seconds(speech_seconds),
    }


def _copy_lesson(lesson: dict[str, Any]) -> dict[str, Any]:
    turns = lesson.get("turns") if isinstance(lesson.get("turns"), list) else []
    return {
        "userSessionId": lesson.get("userSessionId"),
        "startedAt": lesson.get("startedAt"),
        "endedAt": lesson.get("endedAt"),
        "status": lesson.get("status"),
        "scores": _copy_scores(lesson),
        "turns": [
            _turn(
                str(turn.get("transcript") or ""),
                str(turn.get("replyText") or ""),
                list(turn.get("corrections") or []),
                _speech_seconds(turn.get("speechSeconds")),
            )
            for turn in turns
            if isinstance(turn, dict)
        ],
    }


def format_lesson_report(
    rows: list[dict[str, Any]],
    profiles: dict[str, tuple[str, str]],
) -> dict[str, Any]:
    public = []
    total = 0
    for row in rows:
        seconds = _rounded_seconds(row.get("speechSeconds"))
        total += seconds
        username, name = profiles.get(str(row.get("userSessionId") or ""), ("", ""))
        public.append(
            {
                "sessionId": row.get("userSessionId"),
                "username": username or None,
                "name": name or None,
                "startedAt": row.get("startedAt"),
                "speechSeconds": seconds,
            }
        )
    count = len(public)
    return {
        "count": count,
        "averageSeconds": int(total / count + 0.5) if count else 0,
        "totalSeconds": total,
        "rows": public,
    }


def _sealed_row(lesson: dict[str, Any], ended_unix: float) -> dict[str, Any]:
    turns = lesson.get("turns") if isinstance(lesson.get("turns"), list) else []
    speech = sum(_speech_seconds(turn.get("speechSeconds")) for turn in turns if isinstance(turn, dict))
    return {
        "userSessionId": lesson.get("userSessionId"),
        "startedAt": lesson.get("startedAt"),
        "endedUnix": ended_unix,
        "speechSeconds": speech,
    }


def _window_start(moment: float) -> float:
    oldest = recent_days(moment, FUNNEL_WINDOW_DAYS)[-1]
    start = datetime.combine(datetime.fromisoformat(oldest).date(), clock.min, tzinfo=ZoneInfo(METRICS_TIMEZONE))
    return start.timestamp()


def _copy_scores(lesson: dict[str, Any]) -> dict[str, int] | None:
    raw = lesson.get("scores")
    if not isinstance(raw, dict):
        return None
    try:
        return {key: int(raw[key]) for key in ("grammar", "vocabulary", "fluency")}
    except (KeyError, TypeError, ValueError):
        return None


def _speech_seconds(value: Any) -> float:
    try:
        seconds = float(value)
    except (TypeError, ValueError):
        return 0.0
    if seconds <= 0:
        return 0.0
    return seconds


def _rounded_seconds(value: Any) -> int:
    return int(_speech_seconds(value) + 0.5)


def _sealed_key() -> str:
    return "lessons:sealed"


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
