"""Durable, per-provider-attempt costs for incoming Telegram voice jobs."""
from __future__ import annotations

import asyncio
import json
import logging
import time
from contextvars import ContextVar, Token
from datetime import datetime, timezone
from typing import Any
from uuid import uuid4

import httpx
from redis.asyncio import Redis
from redis.exceptions import WatchError

from app.metrics_v2 import cost_micro

logger = logging.getLogger(__name__)
_current: ContextVar[tuple["VoiceCostLedger", str] | None] = ContextVar("voice_cost", default=None)
_OUTBOX = "voice:cost:outbox"
_PENDING = "voice:cost:pending"
_EVENT = "voice:cost:event:"
_RETAIN_SECONDS = 200 * 86400
_LOOKUP_AFTER = (15, 60, 120, 600, 3600)


def bind_voice_cost(ledger: VoiceCostLedger | None, attempt_id: str | None) -> Token:
    return _current.set((ledger, attempt_id) if ledger is not None and attempt_id else None)


def reset_voice_cost(token: Token) -> None:
    _current.reset(token)


async def record_voice_cost(
    *, component: str, provider: str, model: str, result: str,
    cost: int | None, generation_id: str | None = None,
) -> None:
    bound = _current.get()
    if bound is None:
        return
    ledger, attempt_id = bound
    try:
        await ledger.record(attempt_id, component, provider, model, result, cost, generation_id)
    except Exception:
        # Accounting failures must not prevent a voice response, but must be visible.
        logger.exception("voice cost record failed attempt_id=%s component=%s", attempt_id, component)


class VoiceCostLedger:
    def __init__(self, redis: Redis, base_url: str, api_key: str) -> None:
        self.redis = redis
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key

    async def record(
        self, attempt_id: str, component: str, provider: str, model: str,
        result: str, cost: int | None, generation_id: str | None,
    ) -> str:
        call_id = str(uuid4())
        now = time.time()
        pending = (component == "tts" and result == "success" and provider == "openrouter"
                   and bool(generation_id) and cost is None)
        event = {
            "callId": call_id, "attemptId": attempt_id, "component": component,
            "provider": provider, "model": model, "result": result,
            "occurredAt": datetime.fromtimestamp(now, timezone.utc).isoformat(),
            "costMicroUsd": cost, "priceStatus": "pending" if pending else ("known" if cost is not None else "unavailable"),
            "generationId": generation_id if pending else None,
            "revision": 1, "lookupStep": 0, "createdEpoch": now,
        }
        async with self.redis.pipeline(transaction=True) as pipe:
            pipe.set(_EVENT + call_id, json.dumps(event), ex=_RETAIN_SECONDS)
            pipe.zadd(_OUTBOX, {call_id: now})
            if pending:
                pipe.zadd(_PENDING, {call_id: now + _LOOKUP_AFTER[0]})
            await pipe.execute()
        return call_id

    async def batch(self, limit: int = 100) -> list[dict[str, Any]]:
        ids = await self.redis.zrange(_OUTBOX, 0, max(1, min(limit, 100)) - 1)
        if not ids:
            return []
        payloads = await self.redis.mget([_EVENT + item for item in ids])
        missing = [item for item, payload in zip(ids, payloads) if payload is None]
        if missing:
            await self.redis.zrem(_OUTBOX, *missing)
        return [json.loads(payload) for payload in payloads if payload]

    async def acknowledge(self, receipts: list[dict[str, Any]]) -> None:
        for receipt in receipts[:100]:
            call_id = str(receipt.get("callId") or "")
            revision = receipt.get("revision")
            if call_id and isinstance(revision, int):
                try:
                    async with self.redis.pipeline(transaction=True) as pipe:
                        await pipe.watch(_EVENT + call_id)
                        payload = await pipe.get(_EVENT + call_id)
                        if payload and json.loads(payload)["revision"] == revision:
                            pipe.multi()
                            pipe.zrem(_OUTBOX, call_id)
                            await pipe.execute()
                except WatchError:
                    # A newer price update must stay in the outbox.
                    pass

    async def settle_due(self, now: float | None = None) -> int:
        now = time.time() if now is None else now
        ids = await self.redis.zrangebyscore(_PENDING, "-inf", now, start=0, num=20)
        if not ids:
            return 0
        async with httpx.AsyncClient(timeout=5) as client:
            for call_id in ids:
                payload = await self.redis.get(_EVENT + call_id)
                if not payload:
                    await self.redis.zrem(_PENDING, call_id)
                    continue
                event = json.loads(payload)
                if event["priceStatus"] != "pending":
                    await self.redis.zrem(_PENDING, call_id)
                    continue
                cost: int | None = None
                retryable = True
                try:
                    response = await client.get(
                        self.base_url + "/generation",
                        params={"id": event["generationId"]},
                        headers={"Authorization": f"Bearer {self.api_key}"},
                    )
                    if response.status_code == 200:
                        cost = cost_micro((response.json().get("data") or {}).get("total_cost"))
                    elif response.status_code not in (404, 429) and response.status_code < 500:
                        retryable = False
                except (httpx.HTTPError, ValueError):
                    pass
                step = int(event["lookupStep"]) + 1
                event["lookupStep"] = step
                if cost is not None:
                    event["costMicroUsd"] = cost
                    event["priceStatus"] = "known"
                elif not retryable or step >= len(_LOOKUP_AFTER):
                    event["priceStatus"] = "unavailable"
                event["revision"] += 1
                async with self.redis.pipeline(transaction=True) as pipe:
                    pipe.set(_EVENT + call_id, json.dumps(event), ex=_RETAIN_SECONDS)
                    pipe.zadd(_OUTBOX, {call_id: now})
                    if event["priceStatus"] == "pending":
                        pipe.zadd(_PENDING, {call_id: float(event["createdEpoch"]) + _LOOKUP_AFTER[step]})
                    else:
                        pipe.zrem(_PENDING, call_id)
                    await pipe.execute()
                if event["priceStatus"] == "unavailable":
                    logger.warning("TTS price unavailable attempt_id=%s call_id=%s", event["attemptId"], call_id)
        return len(ids)

    async def run(self) -> None:
        while True:
            try:
                await self.settle_due()
            except asyncio.CancelledError:
                raise
            except Exception:
                logger.exception("voice cost settlement failed")
            await asyncio.sleep(5)

    async def aclose(self) -> None:
        await self.redis.aclose()
