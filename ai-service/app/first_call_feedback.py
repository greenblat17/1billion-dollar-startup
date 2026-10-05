from __future__ import annotations

import time
from typing import Any

from redis.asyncio import Redis


CHOICES = {"liked", "neutral", "disliked"}
ANSWER_WINDOW_SECONDS = 24 * 60 * 60
RATED_INDEX = "first-call-feedback:rated"


class FirstCallFeedback:
    """One survey per Telegram session; Redis hash operations keep claims atomic."""

    def __init__(self, redis: Redis | None = None) -> None:
        self.redis = redis
        self.memory: dict[str, dict[str, str]] = {}

    def key(self, session_id: str) -> str:
        return f"first-call-feedback:{session_id}"

    async def offer(self, session_id: str, call_id: str, username: str = "") -> dict[str, str]:
        key = self.key(session_id)
        username = " ".join(username.removeprefix("@").split())[:64]
        if self.redis is not None:
            created = await self.redis.hsetnx(key, "callId", call_id)
            if created:
                await self.redis.hset(key, mapping={"offeredAt": str(time.time()), "username": username})
            return {"status": "offered" if created else "ignored"}
        if key in self.memory:
            return {"status": "ignored"}
        self.memory[key] = {"callId": call_id, "offeredAt": str(time.time()), "username": username}
        return {"status": "offered"}

    async def rate(self, session_id: str, call_id: str, choice: str) -> dict[str, str]:
        if choice not in CHOICES:
            raise ValueError("invalid feedback choice")
        row = await self._read(session_id)
        if row.get("callId") != call_id:
            return {"status": "ignored"}
        if self.redis is not None:
            created = await self.redis.hsetnx(self.key(session_id), "rating", choice)
            if created:
                rated_at = time.time()
                await self.redis.hset(self.key(session_id), "ratedAt", str(rated_at))
                await self.redis.zadd(RATED_INDEX, {session_id: rated_at})
        else:
            created = "rating" not in row
            if created:
                row.update(rating=choice, ratedAt=str(time.time()))
        return {"status": "rated" if created else "ignored"}

    async def list_rated(self, offset: int = 0, limit: int = 25) -> dict[str, Any]:
        if self.redis is not None:
            total = await self.redis.zcard(RATED_INDEX)
            sessions = await self.redis.zrevrange(RATED_INDEX, offset, offset + limit - 1)
            rows = [await self._read(session_id) for session_id in sessions]
        else:
            ranked = sorted(
                ((key.removeprefix("first-call-feedback:"), row) for key, row in self.memory.items() if row.get("rating")),
                key=lambda entry: float(entry[1].get("ratedAt") or 0), reverse=True,
            )
            total = len(ranked)
            sessions = [session_id for session_id, _ in ranked[offset:offset + limit]]
            rows = [row for _, row in ranked[offset:offset + limit]]
        return {
            "total": total,
            "items": [
                {"sessionId": session_id, "username": row.get("username", ""),
                 "choice": row.get("rating", ""),
                 "message": row.get("answer", "")[5:] if row.get("answer", "").startswith("text:") else None}
                for session_id, row in zip(sessions, rows, strict=True)
            ],
        }

    async def answer(self, session_id: str, text: str | None = None, call_id: str | None = None) -> dict[str, str]:
        row = await self._read(session_id)
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
            created = await self.redis.hsetnx(self.key(session_id), "answer", value)
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
