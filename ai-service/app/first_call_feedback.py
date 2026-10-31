from __future__ import annotations

import time
import logging
from typing import Any

from redis.asyncio import Redis
from redis.exceptions import WatchError


CHOICES = {"liked", "neutral", "disliked"}
ANSWER_WINDOW_SECONDS = 24 * 60 * 60
RATED_INDEX = "first-call-feedback:rated"
KORA_KEY_PREFIX = "kora-call-feedback:"
PENDING_KEY_PREFIX = "call-feedback:pending:"
KORA_INDEX_PREFIX = "kora:"
logger = logging.getLogger(__name__)


def offer_eligibility(call: dict[str, Any] | None, onboarding: dict[str, Any] | None,
                      session_id: str) -> str | None:
    if call is None or call.get("sessionId") != session_id:
        return "session_mismatch"
    if not call.get("turns"):
        return "no_user_turn"
    if not call.get("review"):
        return "review_unavailable"
    return None


class FirstCallFeedback:
    """One Speaky survey per session and one Kora H survey per call."""

    def __init__(self, redis: Redis | None = None) -> None:
        self.redis = redis
        self.memory: dict[str, dict[str, str]] = {}
        self.pending: dict[str, str] = {}

    def key(self, session_id: str) -> str:
        return f"first-call-feedback:{session_id}"

    def call_key(self, call_id: str) -> str:
        return f"{KORA_KEY_PREFIX}{call_id}"

    async def _read_key(self, key: str) -> dict[str, str]:
        return await self.redis.hgetall(key) if self.redis is not None else self.memory.get(key, {})

    async def _for_call(self, session_id: str, call_id: str) -> tuple[str, dict[str, str]]:
        key = self.call_key(call_id)
        row = await self._read_key(key)
        if row.get("sessionId") == session_id and row.get("callId") == call_id:
            return key, row
        key = self.key(session_id)
        row = await self._read_key(key)
        return key, row if row.get("callId") == call_id else {}

    async def offer(self, session_id: str, call_id: str, username: str = "",
                    character: str = "speaky") -> dict[str, str]:
        key = self.call_key(call_id) if character == "kora_h" else self.key(session_id)
        username = " ".join(username.removeprefix("@").split())[:64]
        if self.redis is not None:
            created = await self.redis.hsetnx(key, "callId", call_id)
            if created:
                await self.redis.hset(key, mapping={"offeredAt": str(time.time()), "username": username,
                                                    "sessionId": session_id, "character": character})
            return {"status": "offered", "character": character} if created else {"status": "ignored", "reason": "already_offered"}
        if key in self.memory:
            return {"status": "ignored", "reason": "already_offered"}
        self.memory[key] = {"callId": call_id, "offeredAt": str(time.time()), "username": username,
                            "sessionId": session_id, "character": character}
        return {"status": "offered", "character": character}

    async def rate(self, session_id: str, call_id: str, choice: str) -> dict[str, str]:
        if choice not in CHOICES:
            raise ValueError("invalid feedback choice")
        key, row = await self._for_call(session_id, call_id)
        if not row:
            return {"status": "ignored"}
        if self.redis is not None:
            created = await self.redis.hsetnx(key, "rating", choice)
            if created:
                rated_at = time.time()
                await self.redis.hset(key, "ratedAt", str(rated_at))
                member = f"{KORA_INDEX_PREFIX}{call_id}" if key.startswith(KORA_KEY_PREFIX) else session_id
                await self.redis.zadd(RATED_INDEX, {member: rated_at})
                await self.redis.set(f"{PENDING_KEY_PREFIX}{session_id}", key, ex=ANSWER_WINDOW_SECONDS)
        else:
            created = "rating" not in row
            if created:
                row.update(rating=choice, ratedAt=str(time.time()))
                self.pending[session_id] = key
        return {"status": "rated" if created else "ignored"}

    async def list_rated(self, offset: int = 0, limit: int = 25,
                         include_rated_at: bool = False) -> dict[str, Any]:
        if self.redis is not None:
            total = await self.redis.zcard(RATED_INDEX)
            members = await self.redis.zrevrange(RATED_INDEX, offset, offset + limit - 1)
            rows = [await self._read_key(self.call_key(member.removeprefix(KORA_INDEX_PREFIX))
                                         if member.startswith(KORA_INDEX_PREFIX) else self.key(member))
                    for member in members]
        else:
            ranked = sorted(
                ((key, row) for key, row in self.memory.items() if row.get("rating")),
                key=lambda entry: float(entry[1].get("ratedAt") or 0), reverse=True,
            )
            total = len(ranked)
            members = [key.removeprefix("first-call-feedback:") if key.startswith("first-call-feedback:")
                       else f"{KORA_INDEX_PREFIX}{row['callId']}" for key, row in ranked[offset:offset + limit]]
            rows = [row for _, row in ranked[offset:offset + limit]]
        return {
            "total": total,
            "items": [
                {"sessionId": row.get("sessionId") or member, "username": row.get("username", ""),
                 "character": row.get("character", "speaky"), "callId": row.get("callId", ""),
                 "choice": row.get("rating", ""),
                 "message": row.get("answer", "")[5:] if row.get("answer", "").startswith("text:") else None,
                 **({"ratedAt": float(row.get("ratedAt") or 0)} if include_rated_at else {})}
                for member, row in zip(members, rows, strict=True)
            ],
        }

    async def answer(self, session_id: str, text: str | None = None, call_id: str | None = None) -> dict[str, str]:
        if call_id is not None:
            key, row = await self._for_call(session_id, call_id)
        else:
            pointer = (await self.redis.get(f"{PENDING_KEY_PREFIX}{session_id}")
                       if self.redis is not None else self.pending.get(session_id))
            key = pointer or self.key(session_id)
            row = await self._read_key(key)
            if row.get("sessionId", session_id) != session_id:
                return {"status": "ignored"}
        if not row.get("rating") or row.get("answer"):
            return {"status": "ignored"}
        if call_id is not None and row.get("callId") != call_id:
            return {"status": "ignored"}
        if time.time() - float(row.get("ratedAt") or 0) > ANSWER_WINDOW_SECONDS:
            return {"status": "ignored"}
        if text is not None:
            text = text.strip()
            if not text:
                return {"status": "ignored"}
            if len(text) > 500:
                return {"status": "too_long"}
        value = "skip" if text is None else f"text:{text}"
        if self.redis is not None:
            created = await self.redis.hsetnx(key, "answer", value)
        else:
            created = "answer" not in row
            if created:
                row["answer"] = value
        return {"status": "saved" if created else "ignored"}

    async def _read(self, session_id: str) -> dict[str, Any]:
        key = self.key(session_id)
        return await self.redis.hgetall(key) if self.redis is not None else self.memory.get(key, {})

    async def aclose(self) -> None:
        if self.redis is not None:
            await self.redis.aclose()


MODES = frozenset({"onboarding", "free", "situation"})
MODE_RATED_INDEX = "mode-feedback:rated"
MODE_MIRRORED_SET = "mode-feedback:mirrored"


class ModeFeedback:
    """One accepted rating per user and mode; each completed instance may offer."""

    def __init__(self, legacy: FirstCallFeedback) -> None:
        self.legacy = legacy
        self.redis = legacy.redis
        self.memory: dict[str, dict[str, str]] = {}
        self.pending: dict[str, str] = {}
        self.migrated: set[str] = set()
        self.mirrored: set[str] = set()

    @staticmethod
    def entry_key(instance_id: str) -> str:
        return f"mode-feedback:entry:{instance_id}"

    @staticmethod
    def rated_key(session_id: str, mode: str) -> str:
        return f"mode-feedback:rated:{session_id}:{mode}"

    async def _read(self, key: str) -> dict[str, str]:
        return await self.redis.hgetall(key) if self.redis is not None else self.memory.get(key, {})

    async def migrate_legacy(self, session_id: str, get_call, recent_call_ids,
                             *, force: bool = False) -> None:
        """Infer old rating modes only from linked calls, leaving ambiguous data alone."""
        migration_key = f"mode-feedback:migrated:{session_id}"
        if not force and (await self.redis.get(migration_key) if self.redis is not None
                          else session_id in self.migrated):
            return
        first = await self.legacy._read(session_id)
        kora_ids = await recent_call_ids(session_id)
        candidates = [first] + [await self.legacy._read_key(self.legacy.call_key(call_id)) for call_id in kora_ids]
        for row in candidates:
            if row.get("sessionId", session_id) != session_id or not row.get("rating") or not row.get("callId"):
                continue
            call = await get_call(row["callId"])
            if (call is None or call.get("sessionId") != session_id or
                    float(call.get("startedUnix") or 0) <= time.time() - 7 * 86400):
                continue
            mode = "situation" if call.get("scenario") else "free"
            marker = self.rated_key(session_id, mode)
            if self.redis is not None:
                await self.redis.set(marker, f"legacy:{row['callId']}", nx=True)
            else:
                self.memory.setdefault(marker, {"instanceId": f"legacy:{row['callId']}"})
        if self.redis is not None:
            await self.redis.set(migration_key, "1")
        else:
            self.migrated.add(session_id)

    async def offer(self, session_id: str, instance_id: str, mode: str,
                    username: str = "", character: str = "speaky") -> dict[str, str]:
        if mode not in MODES:
            raise ValueError("invalid feedback mode")
        if await self._rated(session_id, mode):
            return {"status": "ignored", "reason": "already_rated"}
        key = self.entry_key(instance_id)
        username = " ".join(username.removeprefix("@").split())[:64]
        row = {"sessionId": session_id, "callId": instance_id, "mode": mode,
               "character": character, "username": username}
        if self.redis is not None:
            created = await self.redis.hsetnx(key, "callId", instance_id)
            if created:
                await self.redis.hset(key, mapping=row)
        else:
            created = key not in self.memory
            if created:
                self.memory[key] = row
        return {"status": "offered", "character": character, "mode": mode} if created else {
            "status": "ignored", "reason": "already_offered"}

    async def _rated(self, session_id: str, mode: str) -> bool:
        key = self.rated_key(session_id, mode)
        return bool(await self.redis.get(key)) if self.redis is not None else key in self.memory

    async def confirm(self, session_id: str, instance_id: str) -> dict[str, str]:
        key = self.entry_key(instance_id)
        row = await self._read(key)
        if row.get("sessionId") != session_id:
            return {"status": "ignored"}
        if self.redis is not None:
            await self.redis.hsetnx(key, "offeredAt", str(time.time()))
        else:
            row.setdefault("offeredAt", str(time.time()))
        return {"status": "saved"}

    async def release(self, session_id: str, instance_id: str) -> dict[str, str]:
        key = self.entry_key(instance_id)
        if self.redis is not None:
            for _ in range(4):
                async with self.redis.pipeline(transaction=True) as pipe:
                    try:
                        await pipe.watch(key)
                        row = await pipe.hgetall(key)
                        if row.get("sessionId") != session_id or row.get("rating"):
                            return {"status": "ignored"}
                        pipe.multi()
                        pipe.delete(key)
                        await pipe.execute()
                        return {"status": "saved"}
                    except WatchError:
                        continue
            raise RuntimeError("feedback release contention")
        row = self.memory.get(key, {})
        if row.get("sessionId") != session_id or row.get("rating"):
            return {"status": "ignored"}
        self.memory.pop(key, None)
        return {"status": "saved"}

    async def rate(self, session_id: str, instance_id: str, choice: str) -> dict[str, str]:
        if choice not in CHOICES:
            raise ValueError("invalid feedback choice")
        key = self.entry_key(instance_id)
        row = await self._read(key)
        if row.get("sessionId") != session_id:
            return {**await self.legacy.rate(session_id, instance_id, choice), "_legacy": True}
        marker = self.rated_key(session_id, row["mode"])
        if self.redis is None:
            if marker in self.memory or row.get("rating"):
                return {"status": "ignored"}
            rated_at = str(time.time())
            row.update(rating=choice, ratedAt=rated_at)
            self.memory[marker] = {"instanceId": instance_id}
            self.pending[session_id] = key
            await self._mirror_legacy(session_id, instance_id, row)
            return {"status": "rated"}
        for _ in range(4):
            async with self.redis.pipeline(transaction=True) as pipe:
                try:
                    await pipe.watch(marker, key)
                    current = await pipe.hgetall(key)
                    if await pipe.get(marker) or current.get("rating") or current.get("sessionId") != session_id:
                        return {"status": "ignored"}
                    rated_at = time.time()
                    pipe.multi()
                    pipe.set(marker, instance_id)
                    pipe.hset(key, mapping={"rating": choice, "ratedAt": str(rated_at)})
                    pipe.zadd(MODE_RATED_INDEX, {instance_id: rated_at})
                    pipe.set(f"{PENDING_KEY_PREFIX}{session_id}", key, ex=ANSWER_WINDOW_SECONDS)
                    await pipe.execute()
                    await self._mirror_legacy(session_id, instance_id, {**current, "rating": choice})
                    return {"status": "rated"}
                except WatchError:
                    continue
        raise RuntimeError("feedback rating contention")

    async def _mirror_legacy(self, session_id: str, instance_id: str, row: dict[str, str]) -> None:
        if row.get("mode") == "onboarding":
            return
        try:
            await self.legacy.offer(session_id, instance_id, row.get("username", ""),
                                    row.get("character", "speaky"))
            await self.legacy.rate(session_id, instance_id, row["rating"])
            _, old_row = await self.legacy._for_call(session_id, instance_id)
            if old_row.get("rating"):
                if self.redis is not None:
                    await self.redis.sadd(MODE_MIRRORED_SET, instance_id)
                else:
                    self.mirrored.add(instance_id)
            key = self.entry_key(instance_id)
            if self.redis is not None:
                await self.redis.set(f"{PENDING_KEY_PREFIX}{session_id}", key, ex=ANSWER_WINDOW_SECONDS)
            else:
                self.pending[session_id] = key
        except Exception:
            logger.exception("legacy feedback mirror failed")

    async def answer(self, session_id: str, text: str | None = None,
                     instance_id: str | None = None) -> dict[str, str]:
        pointer = (await self.redis.get(f"{PENDING_KEY_PREFIX}{session_id}")
                   if self.redis is not None else self.pending.get(session_id))
        key = self.entry_key(instance_id) if instance_id else pointer
        row = await self._read(key) if key else {}
        if not row:
            return await self.legacy.answer(session_id, text=text, call_id=instance_id)
        if (row.get("sessionId") != session_id or not row.get("rating") or row.get("answer") or
                time.time() - float(row.get("ratedAt") or 0) > ANSWER_WINDOW_SECONDS):
            return {"status": "ignored"}
        if text is not None:
            text = text.strip()
            if not text:
                return {"status": "ignored"}
            if len(text) > 500:
                return {"status": "too_long"}
        value = "skip" if text is None else f"text:{text}"
        if self.redis is not None:
            created = await self.redis.hsetnx(key, "answer", value)
        else:
            row["answer"] = value
            created = True
        return {"status": "saved" if created else "ignored"}

    async def list_rated(self, offset: int = 0, limit: int = 25) -> dict[str, Any]:
        old_count = (await self.redis.zcard(RATED_INDEX) if self.redis is not None else
                     sum(bool(row.get("rating")) for row in self.legacy.memory.values()))
        if self.redis is not None:
            fresh_count = await self.redis.zcard(MODE_RATED_INDEX)
            mirrored_count = await self.redis.scard(MODE_MIRRORED_SET)
        else:
            fresh_count = sum(bool(row.get("rating")) for key, row in self.memory.items()
                              if key.startswith("mode-feedback:entry:"))
            mirrored_count = len(self.mirrored)
        total = old_count + fresh_count - mirrored_count
        required = offset + limit
        fetch = min(max(required, 25), max(old_count, fresh_count, 1))
        while True:
            old = await self.legacy.list_rated(0, min(fetch, old_count), include_rated_at=True)
            if self.redis is not None:
                ids = await self.redis.zrevrange(MODE_RATED_INDEX, 0, min(fetch, fresh_count) - 1)
                rows = [await self._read(self.entry_key(instance_id)) for instance_id in ids]
            else:
                rows = sorted((row for key, row in self.memory.items()
                               if key.startswith("mode-feedback:entry:") and row.get("rating")),
                              key=lambda row: float(row.get("ratedAt") or 0), reverse=True)[:fetch]
            fresh = [{"sessionId": row.get("sessionId", ""), "username": row.get("username", ""),
                      "character": row.get("character", "speaky"), "mode": row.get("mode"),
                      "callId": row.get("callId", ""), "choice": row.get("rating", ""),
                      "message": row.get("answer", "")[5:] if row.get("answer", "").startswith("text:") else None,
                      "ratedAt": float(row.get("ratedAt") or 0)} for row in rows if row.get("rating")]
            fresh_ids = {row["callId"] for row in fresh}
            old_rows = [row for row in old["items"] if row.get("callId") not in fresh_ids]
            merged = sorted([*old_rows, *fresh], key=lambda row: row.get("ratedAt", 0), reverse=True)
            if len(merged) >= required or fetch >= max(old_count, fresh_count):
                return {"total": total,
                        "items": [{key: value for key, value in row.items() if key != "ratedAt"}
                                  for row in merged[offset:required]]}
            fetch = min(fetch * 2, max(old_count, fresh_count))
