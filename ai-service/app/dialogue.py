from __future__ import annotations

import asyncio
import hashlib
import json
import math
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from app.audit_artifacts import recorded_at
from typing import Any, Protocol
from uuid import uuid4

from redis.asyncio import Redis
from redis.exceptions import WatchError


@dataclass
class ChatMessage:
    role: str
    content: str
    created_at: float = field(default_factory=recorded_at)


CONTENT_RETENTION_SECONDS = 7 * 24 * 60 * 60
SHARED_FREE_HISTORY_VERSION = "free:v1"
SHARED_FREE_ROLLBACK_SECONDS = CONTENT_RETENTION_SECONDS


def shared_free_scope_id(session_id: str) -> str:
    return f"{session_id}:{SHARED_FREE_HISTORY_VERSION}"


def _merge_free_messages(speaky: list[ChatMessage], kora: list[ChatMessage],
                         max_messages: int, now: float) -> list[ChatMessage]:
    cutoff = now - CONTENT_RETENTION_SECONDS
    seen: set[tuple[float, str, str]] = set()
    merged: list[ChatMessage] = []
    for message in [*speaky, *kora]:
        stamp = message.created_at
        if not math.isfinite(stamp) or not cutoff < stamp <= now:
            continue
        identity = (stamp, message.role, message.content)
        if identity not in seen:
            seen.add(identity)
            merged.append(message)
    merged.sort(key=lambda message: message.created_at)
    return _trim(merged, max_messages)


def _append_shared(messages: list[ChatMessage], pair: list[ChatMessage], max_messages: int) -> list[ChatMessage]:
    ordered = [*_current_messages(messages), *pair]
    ordered.sort(key=lambda message: message.created_at)
    return _trim(ordered, max_messages)


def _shared_marker(session_id: str) -> str:
    return f"shared-free:v1:migrated:{session_id}"


def _shared_turn_key(session_id: str, turn_id: str) -> str:
    digest = hashlib.sha256(turn_id.encode("utf-8")).hexdigest()
    return f"shared-free:v1:turn:{session_id}:{digest}"


def _turn_digest(personality: str, user_text: str, reply: str) -> str:
    payload = json.dumps([personality, user_text, reply], ensure_ascii=False)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


_DUAL_WRITE_UNTIL_KEY = "shared-free:v1:dual-write-until"


def _current_messages(messages: list[ChatMessage]) -> list[ChatMessage]:
    cutoff = time.time() - CONTENT_RETENTION_SECONDS
    return [item for item in messages if item.created_at > cutoff]


class DialogueStore(Protocol):
    async def create(self, session_id: str | None = None) -> str: ...

    async def exists(self, session_id: str) -> bool: ...

    async def history(self, session_id: str) -> list[ChatMessage]: ...

    async def record_turn(self, session_id: str, user_text: str, reply: str) -> None: ...

    async def shared_free_history(self, session_id: str) -> list[ChatMessage]: ...

    async def record_shared_free_turn(self, session_id: str, personality: str,
                                      user_text: str, reply: str, turn_id: str) -> None: ...

    async def complete_turn(
        self,
        session_id: str,
        user_text: str,
        generate: Callable[[list[ChatMessage], str], Awaitable[str]],
    ) -> str: ...

    async def aclose(self) -> None: ...


@dataclass
class _Session:
    messages: list[ChatMessage] = field(default_factory=list)
    updated_at: float = field(default_factory=time.monotonic)


class MemoryDialogueStore:
    def __init__(self, max_messages: int, ttl_seconds: int) -> None:
        self._max_messages = max_messages
        self._ttl_seconds = ttl_seconds
        self._sessions: dict[str, _Session] = {}
        self._locks: dict[str, asyncio.Lock] = {}
        self._meta = asyncio.Lock()
        self._shared_migrated: set[str] = set()
        self._shared_turns: dict[str, dict[str, tuple[str, float]]] = {}
        self._shared_dual_until: float | None = None

    async def create(self, session_id: str | None = None) -> str:
        requested = (session_id or "").strip()
        if requested:
            async with await self._lock_for(requested):
                if self._live_session(requested) is None:
                    self._sessions[requested] = _Session()
                return requested
        new_id = str(uuid4())
        self._sessions[new_id] = _Session()
        return new_id

    async def exists(self, session_id: str) -> bool:
        async with await self._lock_for(session_id):
            return self._live_session(session_id) is not None

    async def history(self, session_id: str) -> list[ChatMessage]:
        async with await self._lock_for(session_id):
            session = self._live_session(session_id)
            if session is None:
                return []
            session.messages = _current_messages(session.messages)
            return list(session.messages)

    async def record_turn(self, session_id: str, user_text: str, reply: str) -> None:
        async with await self._lock_for(session_id):
            session = self._live_session(session_id)
            if session is None:
                session = _Session()
                self._sessions[session_id] = session
            session.messages = _current_messages(session.messages)
            session.messages.append(ChatMessage("user", user_text))
            session.messages.append(ChatMessage("assistant", reply))
            session.messages = _trim(session.messages, self._max_messages)
            session.updated_at = time.monotonic()

    async def shared_free_history(self, session_id: str) -> list[ChatMessage]:
        async with await self._lock_for(shared_free_scope_id(session_id)):
            self._migrate_shared(session_id)
            current = self._live_session(shared_free_scope_id(session_id))
            if current is None:
                return []
            current.messages = _current_messages(current.messages)
            return list(current.messages)

    async def record_shared_free_turn(self, session_id: str, personality: str,
                                      user_text: str, reply: str, turn_id: str) -> None:
        if personality not in {"speaky", "kora_h"}:
            raise ValueError("unknown personality")
        async with await self._lock_for(shared_free_scope_id(session_id)):
            self._migrate_shared(session_id)
            written = self._shared_turns.setdefault(session_id, {})
            now = time.time()
            written = {key: value for key, value in written.items()
                       if value[1] > now - CONTENT_RETENTION_SECONDS}
            self._shared_turns[session_id] = written
            digest = _turn_digest(personality, user_text, reply)
            if turn_id in written:
                if written[turn_id][0] != digest:
                    raise ValueError("shared free history turn conflict")
                return
            pair = [ChatMessage("user", user_text), ChatMessage("assistant", reply)]
            canonical = self._sessions[shared_free_scope_id(session_id)]
            canonical.messages = _append_shared(canonical.messages, pair, self._max_messages)
            canonical.updated_at = time.monotonic()
            if now < (self._shared_dual_until or 0):
                legacy_id = session_id if personality == "speaky" else f"{session_id}:kora_h"
                legacy = self._live_session(legacy_id) or _Session()
                legacy.messages = _trim(_current_messages(legacy.messages) + pair, self._max_messages)
                legacy.updated_at = time.monotonic()
                self._sessions[legacy_id] = legacy
            written[turn_id] = digest, now

    def _migrate_shared(self, session_id: str) -> None:
        canonical_id = shared_free_scope_id(session_id)
        if session_id in self._shared_migrated and self._live_session(canonical_id) is not None:
            return
        now = time.time()
        if self._shared_dual_until is None:
            self._shared_dual_until = now + SHARED_FREE_ROLLBACK_SECONDS
        speaky = self._live_session(session_id)
        kora = self._live_session(f"{session_id}:kora_h")
        messages = _merge_free_messages(speaky.messages if speaky else [], kora.messages if kora else [],
                                        self._max_messages, now)
        self._sessions[canonical_id] = _Session(messages)
        self._shared_migrated.add(session_id)
        self._shared_turns.pop(session_id, None)

    async def complete_turn(
        self,
        session_id: str,
        user_text: str,
        generate: Callable[[list[ChatMessage], str], Awaitable[str]],
    ) -> str:
        async with await self._lock_for(session_id):
            session = self._live_session(session_id)
            history = _current_messages(session.messages) if session is not None else []
            reply = await generate(history, user_text)
            if session is None:
                session = _Session()
                self._sessions[session_id] = session
            session.messages = history
            session.messages.append(ChatMessage("user", user_text))
            session.messages.append(ChatMessage("assistant", reply))
            session.messages = _trim(session.messages, self._max_messages)
            session.updated_at = time.monotonic()
            return reply

    async def aclose(self) -> None:
        return None

    async def prune_all(self) -> int:
        changed = 0
        for session_id in list(self._sessions):
            async with await self._lock_for(session_id):
                session = self._live_session(session_id)
                if session is None:
                    continue
                current = _current_messages(session.messages)
                if len(current) != len(session.messages):
                    session.messages = current
                    changed += 1
        for session_id in list(self._shared_migrated):
            if self._live_session(shared_free_scope_id(session_id)) is None:
                self._shared_migrated.discard(session_id)
                self._shared_turns.pop(session_id, None)
        return changed

    async def _lock_for(self, session_id: str) -> asyncio.Lock:
        async with self._meta:
            lock = self._locks.get(session_id)
            if lock is None:
                lock = asyncio.Lock()
                self._locks[session_id] = lock
            return lock

    def _live_session(self, session_id: str) -> _Session | None:
        session = self._sessions.get(session_id)
        if session is None:
            return None
        if time.monotonic() - session.updated_at > self._ttl_seconds:
            self._sessions.pop(session_id, None)
            return None
        return session


class RedisDialogueStore:
    def __init__(self, redis: Redis, max_messages: int, ttl_seconds: int) -> None:
        self._redis = redis
        self._max_messages = max_messages
        self._ttl_seconds = min(ttl_seconds, CONTENT_RETENTION_SECONDS)
        self._locks: dict[str, asyncio.Lock] = {}
        self._meta = asyncio.Lock()

    async def create(self, session_id: str | None = None) -> str:
        requested = (session_id or "").strip()
        session_id = requested or str(uuid4())
        async with await self._lock_for(session_id):
            key = _session_key(session_id)
            if requested and await self._redis.exists(key):
                await self._redis.expire(key, self._ttl_seconds)
                return session_id
            added = await self._redis.set(
                key,
                _dump_messages([]),
                ex=self._ttl_seconds,
                nx=True,
            )
            if not added:
                await self._redis.expire(key, self._ttl_seconds)
            return session_id

    async def exists(self, session_id: str) -> bool:
        return bool(await self._redis.exists(_session_key(session_id)))

    async def history(self, session_id: str) -> list[ChatMessage]:
        key = _session_key(session_id)
        raw = await self._redis.get(key)
        if raw is None:
            return []
        messages = _load_messages(raw)
        current = _current_messages(messages)
        return current if len(current) == len(messages) else (await self._prune_key(key))[0]

    async def record_turn(self, session_id: str, user_text: str, reply: str) -> None:
        async with await self._lock_for(session_id):
            key = _session_key(session_id)
            raw = await self._redis.get(key)
            history = _current_messages(_load_messages(raw)) if raw is not None else []
            messages = history + [
                ChatMessage("user", user_text),
                ChatMessage("assistant", reply),
            ]
            messages = _trim(messages, self._max_messages)
            await self._redis.set(key, _dump_messages(messages), ex=self._ttl_seconds)

    async def shared_free_history(self, session_id: str) -> list[ChatMessage]:
        await self._migrate_shared(session_id)
        raw = await self._redis.get(_session_key(shared_free_scope_id(session_id)))
        return _current_messages(_load_messages(raw)) if raw is not None else []

    async def record_shared_free_turn(self, session_id: str, personality: str,
                                      user_text: str, reply: str, turn_id: str) -> None:
        if personality not in {"speaky", "kora_h"}:
            raise ValueError("unknown personality")
        await self._migrate_shared(session_id)
        canonical_key = _session_key(shared_free_scope_id(session_id))
        legacy_key = _session_key(session_id if personality == "speaky" else f"{session_id}:kora_h")
        marker = _shared_marker(session_id)
        turn_key = _shared_turn_key(session_id, turn_id)
        digest = _turn_digest(personality, user_text, reply)
        until = float(await self._redis.get(_DUAL_WRITE_UNTIL_KEY) or 0)
        for _ in range(64):
            async with self._redis.pipeline(transaction=True) as pipe:
                try:
                    await pipe.watch(canonical_key, legacy_key, marker, turn_key)
                    canonical_raw, legacy_raw, migrated, written = await pipe.mget(
                        canonical_key, legacy_key, marker, turn_key,
                    )
                    if not migrated or canonical_raw is None:
                        await pipe.unwatch()
                        await self._migrate_shared(session_id)
                        continue
                    if written:
                        if isinstance(written, bytes):
                            written = written.decode("utf-8")
                        if written != digest:
                            raise ValueError("shared free history turn conflict")
                        return
                    pair = [ChatMessage("user", user_text), ChatMessage("assistant", reply)]
                    canonical = _append_shared(_load_messages(canonical_raw), pair, self._max_messages)
                    pipe.multi()
                    pipe.set(canonical_key, _dump_messages(canonical), ex=self._ttl_seconds)
                    if time.time() < until:
                        legacy = _current_messages(_load_messages(legacy_raw)) if legacy_raw is not None else []
                        pipe.set(legacy_key, _dump_messages(_trim(legacy + pair, self._max_messages)),
                                 ex=self._ttl_seconds)
                    pipe.set(marker, "1", ex=self._ttl_seconds)
                    pipe.set(turn_key, digest, ex=self._ttl_seconds)
                    await pipe.execute()
                    return
                except WatchError:
                    continue
        raise RuntimeError("shared free history write contention")

    async def _migrate_shared(self, session_id: str) -> None:
        canonical_key = _session_key(shared_free_scope_id(session_id))
        speaky_key = _session_key(session_id)
        kora_key = _session_key(f"{session_id}:kora_h")
        marker = _shared_marker(session_id)
        await self._redis.set(_DUAL_WRITE_UNTIL_KEY,
                              str(time.time() + SHARED_FREE_ROLLBACK_SECONDS), nx=True)
        for _ in range(64):
            async with self._redis.pipeline(transaction=True) as pipe:
                try:
                    await pipe.watch(speaky_key, kora_key, canonical_key, marker)
                    speaky_raw, kora_raw, canonical_raw, migrated = await pipe.mget(
                        speaky_key, kora_key, canonical_key, marker,
                    )
                    if migrated and canonical_raw is not None:
                        return
                    speaky = _load_messages(speaky_raw) if speaky_raw is not None else []
                    kora = _load_messages(kora_raw) if kora_raw is not None else []
                    messages = _merge_free_messages(speaky, kora, self._max_messages, time.time())
                    pipe.multi()
                    pipe.set(canonical_key, _dump_messages(messages), ex=self._ttl_seconds)
                    pipe.set(marker, "1", ex=self._ttl_seconds)
                    await pipe.execute()
                    return
                except WatchError:
                    continue
        raise RuntimeError("shared free history migration contention")

    async def complete_turn(
        self,
        session_id: str,
        user_text: str,
        generate: Callable[[list[ChatMessage], str], Awaitable[str]],
    ) -> str:
        async with await self._lock_for(session_id):
            key = _session_key(session_id)
            raw = await self._redis.get(key)
            history = _current_messages(_load_messages(raw)) if raw is not None else []
            reply = await generate(history, user_text)
            messages = history + [
                ChatMessage("user", user_text),
                ChatMessage("assistant", reply),
            ]
            messages = _trim(messages, self._max_messages)
            await self._redis.set(key, _dump_messages(messages), ex=self._ttl_seconds)
            return reply

    async def aclose(self) -> None:
        await self._redis.aclose()

    async def prune_all(self) -> int:
        """Remove old and legacy untimestamped turns even from inactive sessions."""
        changed = 0
        async for key in self._redis.scan_iter(match="session:*", count=100):
            _, pruned = await self._prune_key(key)
            changed += int(pruned)
        return changed

    async def _prune_key(self, key: str) -> tuple[list[ChatMessage], bool]:
        for _ in range(64):
            async with self._redis.pipeline(transaction=True) as pipe:
                try:
                    await pipe.watch(key)
                    raw = await pipe.get(key)
                    if raw is None:
                        return [], False
                    messages = _load_messages(raw)
                    current = _current_messages(messages)
                    if len(current) == len(messages):
                        return current, False
                    pipe.multi()
                    pipe.set(key, _dump_messages(current), ex=self._ttl_seconds)
                    await pipe.execute()
                    return current, True
                except WatchError:
                    continue
        raise RuntimeError("dialogue retention write contention")

    async def _lock_for(self, session_id: str) -> asyncio.Lock:
        async with self._meta:
            lock = self._locks.get(session_id)
            if lock is None:
                lock = asyncio.Lock()
                self._locks[session_id] = lock
            return lock


def build_dialogue_store(settings: Any, redis: Redis | None = None) -> DialogueStore:
    max_messages = settings.dialogue_max_messages
    ttl_seconds = settings.dialogue_ttl_seconds
    if redis is not None:
        return RedisDialogueStore(redis, max_messages, ttl_seconds)
    url = getattr(settings, "redis_url", None)
    if url:
        return RedisDialogueStore(Redis.from_url(url, decode_responses=True), max_messages, ttl_seconds)
    return MemoryDialogueStore(max_messages, ttl_seconds)


def _session_key(session_id: str) -> str:
    return f"session:{session_id}"


def _trim(messages: list[ChatMessage], max_messages: int) -> list[ChatMessage]:
    overflow = len(messages) - max_messages
    if overflow > 0:
        return messages[overflow:]
    return messages


def _dump_messages(messages: list[ChatMessage]) -> str:
    return json.dumps({"messages": [{"role": item.role, "content": item.content, "at": item.created_at} for item in messages]})


def _load_messages(raw: str | bytes) -> list[ChatMessage]:
    if isinstance(raw, bytes):
        raw = raw.decode("utf-8")
    payload = json.loads(raw)
    items = payload.get("messages") if isinstance(payload, dict) else []
    if not isinstance(items, list):
        return []
    messages: list[ChatMessage] = []
    for item in items:
        if not isinstance(item, dict):
            continue
        role = str(item.get("role") or "").strip()
        content = str(item.get("content") or "")
        created_at = item.get("at")
        if role:
            # Legacy turns have no timestamp, so their age cannot be proven within seven days.
            timestamp = float(created_at) if isinstance(created_at, (int, float)) else 0.0
            messages.append(ChatMessage(role=role, content=content, created_at=timestamp))
    return messages
