"""Print private Telegram chat IDs with a completed production onboarding."""

import asyncio
import os

from redis.asyncio import Redis

from app.legacy_onboarding_campaign import (
    _completed_onboarding,
    _completion_keys,
    chat_id,
    known_chat_ids,
)


async def main() -> None:
    redis = Redis.from_url(os.environ["REDIS_URL"], decode_responses=True)
    try:
        candidates = await known_chat_ids(redis)
        for prefix in ("assessment:", "onboarding:"):
            async for key in redis.scan_iter(match=f"{prefix}tg-*", count=200):
                candidate = chat_id(key.removeprefix(prefix))
                if candidate is not None:
                    candidates.add(candidate)

        ordered = sorted(candidates)
        for start in range(0, len(ordered), 200):
            batch = ordered[start:start + 200]
            values = await redis.mget(*(key for recipient in batch
                                        for key in _completion_keys(str(recipient))))
            for index, recipient in enumerate(batch):
                if _completed_onboarding(values[index * 4:(index + 1) * 4]):
                    print(recipient)
    finally:
        await redis.aclose()


if __name__ == "__main__":
    asyncio.run(main())
