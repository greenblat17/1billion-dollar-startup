from __future__ import annotations

import asyncio
import re
from dataclasses import dataclass
from datetime import date, timedelta
from typing import Any, Protocol

from redis.asyncio import Redis

from app.metrics import (
    MemoryMetricsStore,
    MetricsStore,
    RedisMetricsStore,
    _CHATS_KEY,
    _dau_key,
    metrics_day,
    nonneg_int,
    recent_days,
    telegram_chat_id,
)
from app.metrics import _moment as metrics_moment

STREAK_BUCKETS = ("0", "1", "2_6", "7_13", "14_plus")
RETENTION_OFFSETS = (1, 7, 30)
COHORT_WEEKS = 8
PROFILE_DAYS = 7

_DAY_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
_BACKFILL_KEY = "streak:backfill:v1"
_RELEASED_KEY = "streak:released_day"
_USERS_KEY = "streak:users"
_FUNNEL_USER_PREFIX = "metrics:funnel:user:"


@dataclass
class StreakState:
    current: int = 0
    best: int = 0
    last_day: str = ""
    first_day: str = ""


@dataclass(frozen=True)
class StreakUpdate:
    current: int
    best: int
    first_today: bool
    first_ever: bool
    new_record: bool

    def to_json(self) -> dict[str, Any]:
        return {
            "current": self.current,
            "best": self.best,
            "firstToday": self.first_today,
            "firstEver": self.first_ever,
            "newRecord": self.new_record,
        }


class StreakStore(Protocol):
    async def record_activity(self, session_id: str, *, now: float | None = None) -> StreakUpdate: ...

    async def profile(self, session_id: str, *, now: float | None = None) -> dict[str, Any]: ...

    async def shown(self, session_id: str, *, now: float | None = None) -> int: ...

    async def bucket_for(self, session_id: str, *, now: float | None = None) -> str: ...

    async def backfill(self, *, now: float | None = None) -> None: ...

    async def snapshot(self, *, now: float | None = None) -> dict[str, Any]: ...


def build_streak_store(metrics: MetricsStore) -> StreakStore:
    if isinstance(metrics, RedisMetricsStore):
        return RedisStreakStore(metrics.redis)
    if isinstance(metrics, MemoryMetricsStore):
        return MemoryStreakStore(metrics)
    raise TypeError(f"unsupported metrics store: {type(metrics).__name__}")


def bucket(current: int) -> str:
    if current <= 0:
        return "0"
    if current == 1:
        return "1"
    if current <= 6:
        return "2_6"
    if current <= 13:
        return "7_13"
    return "14_plus"


def advance(state: StreakState, day: str) -> StreakUpdate:
    if state.last_day == day:
        return StreakUpdate(state.current, state.best, False, False, False)
    old_best = state.best
    if state.last_day and _day_before(day) == state.last_day:
        state.current += 1
    else:
        state.current = 1
    first_ever = not state.first_day
    if first_ever:
        state.first_day = day
    new_record = state.current > old_best and old_best >= 2
    if state.current > state.best:
        state.best = state.current
    state.last_day = day
    return StreakUpdate(state.current, state.best, True, first_ever, new_record)


def displayed(state: StreakState, today: str) -> int:
    if state.last_day == today or (state.last_day and _day_before(today) == state.last_day):
        return state.current
    return 0


def retention_view(
    today: date,
    members: list[tuple[str, str]],
    active: dict[str, set[str]],
    released_day: str | None,
) -> dict[str, Any]:
    valid = [(session, activated) for session, activated in members if _parse_day(activated) is not None]
    monday = _monday(today)
    cohorts = []
    for offset in range(COHORT_WEEKS):
        start = monday - timedelta(weeks=offset)
        group = [item for item in valid if _in_week(item[1], start)]
        cohorts.append({"week": start.isoformat(), **_slice(group, today, active)})
    released = released_day if released_day and _parse_day(released_day) else None
    before = [item for item in valid if released is not None and item[1] < released]
    after = [item for item in valid if released is not None and item[1] >= released]
    return {
        "cohorts": cohorts,
        "before": _slice(before, today, active),
        "after": _slice(after, today, active),
        "releasedDay": released,
    }


class MemoryStreakStore:
    def __init__(self, metrics: MemoryMetricsStore) -> None:
        self._metrics = metrics
        self._users: dict[str, StreakState] = {}
        self._backfilled = False
        self._released_day: str | None = None
        self._lock = asyncio.Lock()

    async def record_activity(self, session_id: str, *, now: float | None = None) -> StreakUpdate:
        session = session_id.strip()
        if not session:
            return _empty_update()
        day = metrics_day(_moment(now))
        async with self._lock:
            state = self._users.setdefault(session, StreakState())
            return advance(state, day)

    async def profile(self, session_id: str, *, now: float | None = None) -> dict[str, Any]:
        moment = _moment(now)
        session = session_id.strip()
        days = list(reversed(recent_days(moment, PROFILE_DAYS)))
        async with self._lock:
            state = self._users.get(session, StreakState())
            last7 = [session in self._metrics._dau.get(day, ()) for day in days]
            return _profile(state, metrics_day(moment), last7)

    async def shown(self, session_id: str, *, now: float | None = None) -> int:
        today = metrics_day(_moment(now))
        async with self._lock:
            return displayed(self._users.get(session_id.strip(), StreakState()), today)

    async def bucket_for(self, session_id: str, *, now: float | None = None) -> str:
        return bucket(await self.shown(session_id, now=now))

    async def backfill(self, *, now: float | None = None) -> None:
        async with self._lock:
            if self._backfilled:
                return
            self._backfilled = True
            self._released_day = metrics_day(_moment(now))
            for day_name in sorted(self._metrics._dau):
                if _parse_day(day_name) is None:
                    continue
                for session in sorted(self._metrics._dau[day_name]):
                    if not session:
                        continue
                    advance(self._users.setdefault(session, StreakState()), day_name)

    async def snapshot(self, *, now: float | None = None) -> dict[str, Any]:
        moment = _moment(now)
        today = _parse_day(metrics_day(moment)) or date.today()
        async with self._lock:
            sessions = _telegram_sessions(set(self._metrics._funnel_users) | set(self._metrics._chats) | set(self._users))
            counts = {name: 0 for name in STREAK_BUCKETS}
            for session in sessions:
                counts[bucket(displayed(self._users.get(session, StreakState()), today.isoformat()))] += 1
            members = [
                (session, user.activated_day)
                for session, user in self._metrics._funnel_users.items()
                if user.activated_day
            ]
            needed = _needed_days(members, today)
            active = {day: set(self._metrics._dau.get(day, ())) for day in needed}
            released = self._released_day
        return {
            "buckets": [{"bucket": name, "users": counts[name]} for name in STREAK_BUCKETS],
            "retention": retention_view(today, members, active, released),
        }


class RedisStreakStore:
    def __init__(self, redis: Redis) -> None:
        self._redis = redis
        self._lock = asyncio.Lock()

    async def record_activity(self, session_id: str, *, now: float | None = None) -> StreakUpdate:
        session = session_id.strip()
        if not session:
            return _empty_update()
        day = metrics_day(_moment(now))
        async with self._lock:
            state = _state(await self._redis.hgetall(_user_key(session)))
            update = advance(state, day)
            if update.first_today:
                await self._save(session, state)
            return update

    async def profile(self, session_id: str, *, now: float | None = None) -> dict[str, Any]:
        moment = _moment(now)
        session = session_id.strip()
        days = list(reversed(recent_days(moment, PROFILE_DAYS)))
        state = _state(await self._redis.hgetall(_user_key(session)))
        pipe = self._redis.pipeline()
        for day in days:
            pipe.sismember(_dau_key(day), session)
        flags = await pipe.execute()
        return _profile(state, metrics_day(moment), [bool(flag) for flag in flags])

    async def shown(self, session_id: str, *, now: float | None = None) -> int:
        state = _state(await self._redis.hgetall(_user_key(session_id.strip())))
        return displayed(state, metrics_day(_moment(now)))

    async def bucket_for(self, session_id: str, *, now: float | None = None) -> str:
        return bucket(await self.shown(session_id, now=now))

    async def backfill(self, *, now: float | None = None) -> None:
        claimed = await self._redis.set(_BACKFILL_KEY, "1", nx=True)
        if not claimed:
            return
        try:
            days = []
            async for key in self._redis.scan_iter(match="metrics:dau:*"):
                day_name = str(key).removeprefix("metrics:dau:")
                if _parse_day(day_name) is not None:
                    days.append(day_name)
            for day_name in sorted(days):
                members = await self._redis.smembers(_dau_key(day_name))
                for session in sorted(str(member) for member in members):
                    if not session:
                        continue
                    async with self._lock:
                        state = _state(await self._redis.hgetall(_user_key(session)))
                        update = advance(state, day_name)
                        if update.first_today:
                            await self._save(session, state)
            await self._redis.set(_RELEASED_KEY, metrics_day(_moment(now)))
        except Exception:
            await self._redis.delete(_BACKFILL_KEY)
            raise

    async def snapshot(self, *, now: float | None = None) -> dict[str, Any]:
        moment = _moment(now)
        today = _parse_day(metrics_day(moment)) or date.today()
        sessions = await self._known_sessions()
        pipe = self._redis.pipeline()
        ordered = sorted(sessions)
        for session in ordered:
            pipe.hgetall(_user_key(session))
        raw_states = await pipe.execute() if ordered else []
        counts = {name: 0 for name in STREAK_BUCKETS}
        for session, raw in zip(ordered, raw_states, strict=True):
            del session
            counts[bucket(displayed(_state(raw), today.isoformat()))] += 1
        members = await self._activations()
        needed = _needed_days(members, today)
        active_pipe = self._redis.pipeline()
        needed_days = sorted(needed)
        for day in needed_days:
            active_pipe.smembers(_dau_key(day))
        raw_active = await active_pipe.execute() if needed_days else []
        active = {
            day: {str(member) for member in members_raw}
            for day, members_raw in zip(needed_days, raw_active, strict=True)
        }
        released = await self._redis.get(_RELEASED_KEY)
        return {
            "buckets": [{"bucket": name, "users": counts[name]} for name in STREAK_BUCKETS],
            "retention": retention_view(today, members, active, str(released) if released else None),
        }

    async def _save(self, session: str, state: StreakState) -> None:
        pipe = self._redis.pipeline()
        pipe.hset(_user_key(session), mapping=_mapping(state))
        pipe.sadd(_USERS_KEY, session)
        await pipe.execute()

    async def _known_sessions(self) -> set[str]:
        known: set[str] = set()
        async for key in self._redis.scan_iter(match=f"{_FUNNEL_USER_PREFIX}*"):
            known.add(str(key).removeprefix(_FUNNEL_USER_PREFIX))
        known.update(str(member) for member in await self._redis.zrange(_CHATS_KEY, 0, -1))
        known.update(str(member) for member in await self._redis.smembers(_USERS_KEY))
        return _telegram_sessions(known)

    async def _activations(self) -> list[tuple[str, str]]:
        keys = [str(key) async for key in self._redis.scan_iter(match=f"{_FUNNEL_USER_PREFIX}*")]
        if not keys:
            return []
        pipe = self._redis.pipeline()
        for key in keys:
            pipe.hget(key, "activated_day")
        raw = await pipe.execute()
        members = []
        for key, activated in zip(keys, raw, strict=True):
            day_name = str(activated or "")
            if day_name:
                members.append((key.removeprefix(_FUNNEL_USER_PREFIX), day_name))
        return members


def _profile(state: StreakState, today: str, last7: list[bool]) -> dict[str, Any]:
    return {"current": displayed(state, today), "best": state.best, "last7": last7}


def _empty_update() -> StreakUpdate:
    return StreakUpdate(0, 0, False, False, False)


def _moment(now: float | None) -> float:
    return metrics_moment(now)


def _parse_day(value: str) -> date | None:
    if not _DAY_RE.fullmatch(value):
        return None
    try:
        return date.fromisoformat(value)
    except ValueError:
        return None


def _day_before(day: str) -> str | None:
    parsed = _parse_day(day)
    if parsed is None:
        return None
    return (parsed - timedelta(days=1)).isoformat()


def _monday(day: date) -> date:
    return day - timedelta(days=day.weekday())


def _in_week(activated: str, monday: date) -> bool:
    parsed = _parse_day(activated)
    if parsed is None:
        return False
    return monday <= parsed <= monday + timedelta(days=6)


def _slice(group: list[tuple[str, str]], today: date, active: dict[str, set[str]]) -> dict[str, Any]:
    return {
        "size": len(group),
        "d1": _rate(group, 1, today, active),
        "d7": _rate(group, 7, today, active),
        "d30": _rate(group, 30, today, active),
    }


def _rate(
    group: list[tuple[str, str]],
    offset: int,
    today: date,
    active: dict[str, set[str]],
) -> float | None:
    if not group:
        return None
    targets: list[tuple[str, str]] = []
    for session, activated in group:
        parsed = _parse_day(activated)
        if parsed is None:
            return None
        target = parsed + timedelta(days=offset)
        if target > today:
            return None
        targets.append((session, target.isoformat()))
    hits = sum(1 for session, day_name in targets if session in active.get(day_name, ()))
    return hits / len(group)


def _needed_days(members: list[tuple[str, str]], today: date) -> set[str]:
    needed: set[str] = set()
    for _session, activated in members:
        parsed = _parse_day(activated)
        if parsed is None:
            continue
        for offset in RETENTION_OFFSETS:
            target = parsed + timedelta(days=offset)
            if target <= today:
                needed.add(target.isoformat())
    return needed


def _telegram_sessions(sessions: set[str]) -> set[str]:
    return {session for session in sessions if telegram_chat_id(session) is not None}


def _state(raw: Any) -> StreakState:
    data = raw if isinstance(raw, dict) else {}
    return StreakState(
        current=nonneg_int(data.get("current")),
        best=nonneg_int(data.get("best")),
        last_day=str(data.get("last_day") or ""),
        first_day=str(data.get("first_day") or ""),
    )


def _mapping(state: StreakState) -> dict[str, str]:
    return {
        "current": str(state.current),
        "best": str(state.best),
        "last_day": state.last_day,
        "first_day": state.first_day,
    }


def _user_key(session: str) -> str:
    return f"streak:user:{session}"
