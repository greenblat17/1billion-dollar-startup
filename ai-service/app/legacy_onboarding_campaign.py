"""One-time Telegram announcement for chats known when the audience is frozen.

The deployment script runs `snapshot` once against ai-service's persistent Redis.
The authenticated internal API then lets the CMP admin page claim and report sends.
"""

from __future__ import annotations

import argparse
import asyncio
import os
import re
from collections import Counter

from redis.asyncio import Redis

CAMPAIGN = "campaign:2026-10-01-legacy-onboarding"
AUDIENCE_KEY = f"{CAMPAIGN}:audience"
READY_KEY = f"{CAMPAIGN}:ready"
STATUS_KEY = f"{CAMPAIGN}:status"
TELEGRAM_SESSION = re.compile(r"tg-(?:ChatId\(chatId=([1-9]\d*)\)|([1-9]\d*))\Z")


def chat_id(session_id: str) -> int | None:
    match = TELEGRAM_SESSION.fullmatch(session_id)
    return int(match.group(1) or match.group(2)) if match else None


async def known_chat_ids(redis: Redis) -> set[int]:
    """Use the same two durable sources as MetricsStore.is_known, without its 200-chat UI limit."""
    sessions = set(await redis.zrange("metrics:chats", 0, -1))
    prefix = "metrics:funnel:user:"
    async for key in redis.scan_iter(match=f"{prefix}tg-*"):
        sessions.add(key.removeprefix(prefix))
    return {value for session in sessions if (value := chat_id(session)) is not None}


async def snapshot(redis: Redis) -> None:
    if await redis.exists(READY_KEY):
        print(f"Audience already frozen: {await redis.scard(AUDIENCE_KEY)} chats")
        return
    ids = await known_chat_ids(redis)
    await redis.delete(AUDIENCE_KEY)
    if ids:
        await redis.sadd(AUDIENCE_KEY, *(str(value) for value in ids))
    await redis.set(READY_KEY, "1")
    print(f"Audience frozen: {len(ids)} existing private chats")


async def campaign_status(redis: Redis) -> dict[str, int | bool]:
    ready = bool(await redis.exists(READY_KEY))
    audience = await redis.scard(AUDIENCE_KEY) if ready else 0
    statuses = Counter((await redis.hgetall(STATUS_KEY)).values())
    return {
        "ready": ready,
        "audience": audience,
        "remaining": max(0, audience - sum(statuses.values())),
        "sent": statuses["sent"],
        "blocked": statuses["blocked"],
        "failed": sum(
            count for state, count in statuses.items()
            if state == "failed" or state.startswith("failed:") or state == "rate_limited"
        ),
        "uncertain": statuses["uncertain"] + statuses["pending"],
    }


async def claim_batch(redis: Redis, limit: int = 50) -> list[int]:
    if not await redis.exists(READY_KEY):
        return []
    claimed = []
    for raw in sorted(await redis.smembers(AUDIENCE_KEY), key=int):
        if await redis.hsetnx(STATUS_KEY, raw, "pending"):
            claimed.append(int(raw))
            if len(claimed) == limit:
                break
    return claimed


async def report_delivery(redis: Redis, recipient: int, status: str) -> bool:
    if status not in {"sent", "blocked", "failed", "uncertain"}:
        return False
    if not await redis.sismember(AUDIENCE_KEY, str(recipient)):
        return False
    if await redis.hget(STATUS_KEY, str(recipient)) != "pending":
        return False
    await redis.hset(STATUS_KEY, str(recipient), status)
    return True


async def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("snapshot",))
    parser.parse_args()
    redis_url = os.environ.get("REDIS_URL")
    if not redis_url:
        raise SystemExit("REDIS_URL is required")
    redis = Redis.from_url(redis_url, decode_responses=True)
    try:
        await snapshot(redis)
    finally:
        await redis.aclose()


if __name__ == "__main__":
    asyncio.run(main())
