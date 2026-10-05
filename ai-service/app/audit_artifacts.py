"""Short-lived AI stage artifacts for recovery after a clip job fails or restarts."""
from __future__ import annotations

import asyncio
import logging
import time
from contextvars import ContextVar, Token
from typing import Awaitable, Callable

from redis.asyncio import Redis

logger = logging.getLogger(__name__)
_writer: ContextVar[Callable[[str, str], Awaitable[None]] | None] = ContextVar("audit_writer", default=None)
_receipt_time: ContextVar[float | None] = ContextVar("receipt_time", default=None)
RETENTION_SECONDS = 7 * 24 * 60 * 60


def bind_writer(writer: Callable[[str, str], Awaitable[None]] | None) -> Token:
    return _writer.set(writer)


def reset_writer(token: Token) -> None:
    _writer.reset(token)


def bind_receipt_time(received_at: float | None) -> Token:
    return _receipt_time.set(received_at)


def reset_receipt_time(token: Token) -> None:
    _receipt_time.reset(token)


def recorded_at(fallback: float | None = None) -> float:
    return _receipt_time.get() or (fallback if fallback is not None else time.time())


async def record_artifact(field: str, value: str) -> None:
    writer = _writer.get()
    if writer is None or not value:
        return
    try:
        await asyncio.wait_for(writer(field, value), timeout=0.2)
    except asyncio.CancelledError:
        raise
    except Exception:
        logger.exception("AI audit artifact write failed field=%s", field)


class AuditArtifacts:
    def __init__(self, redis: Redis | None) -> None:
        self.redis = redis

    async def record(self, attempt_id: str, received_at: float, field: str, value: str) -> None:
        if self.redis is None or field not in {"transcript", "reply"}:
            return
        expiry = int(received_at + RETENTION_SECONDS)
        if expiry <= time.time():
            return
        key = f"audit:attempt:{attempt_id}"
        async with self.redis.pipeline(transaction=True) as pipe:
            pipe.hset(key, field, value[:16_000])
            pipe.expireat(key, expiry)
            await pipe.execute()

    async def get(self, attempt_id: str) -> dict[str, str]:
        if self.redis is None:
            return {}
        return await self.redis.hgetall(f"audit:attempt:{attempt_id}")

    async def aclose(self) -> None:
        if self.redis is not None:
            await self.redis.aclose()
