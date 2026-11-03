from __future__ import annotations

import time
from typing import Any

from redis.asyncio import Redis


CHOICES = {"liked", "neutral", "disliked"}
ANSWER_WINDOW_SECONDS = 24 * 60 * 60
RATED_INDEX = "first-call-feedback:rated"
KORA_KEY_PREFIX = "kora-call-feedback:"
PENDING_KEY_PREFIX = "call-feedback:pending:"
KORA_INDEX_PREFIX = "kora:"


def offer_eligibility(call: dict[str, Any] | None, onboarding: dict[str, Any] | None,
                      session_id: str) -> str | None:
    if call is None or call.get("sessionId") != session_id:
        return "session_mismatch"
    if call.get("character") != "kora_h" and (onboarding is None or onboarding.get("status") != "completed"):
        return "onboarding_incomplete"
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

    async def list_rated(self, offset: int = 0, limit: int = 25) -> dict[str, Any]:
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
                 "message": row.get("answer", "")[5:] if row.get("answer", "").startswith("text:") else None}
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
