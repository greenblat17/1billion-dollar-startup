"""Read-only metadata export for matching retained call turns to audit audio.

Run inside ai-service. Output contains identifiers and timestamps; keep it in a
private temporary file, never in the repository or user-facing logs.
"""

import asyncio
import hashlib
import json
import os

from redis.asyncio import Redis


async def main():
    redis = Redis.from_url(os.environ["REDIS_URL"], decode_responses=True)
    try:
        rows = []
        async for key in redis.scan_iter(match="call:*", count=100):
            if key.startswith(("call:open:", "call:day:")):
                continue
            raw = await redis.get(key)
            if not raw:
                continue
            try:
                call = json.loads(raw)
            except ValueError:
                continue
            turns = call.get("turns") or []
            session = str(call.get("sessionId") or "")
            if len(turns) < 3 or not session.startswith("tg-"):
                continue
            rows.append({
                "case": hashlib.sha256(key.encode()).hexdigest()[:12],
                "session": session,
                "reviewed": bool(call.get("review")),
                "turns": [turn.get("receivedAt") for turn in turns],
            })
        print(json.dumps(rows))
    finally:
        await redis.aclose()


if __name__ == "__main__":
    asyncio.run(main())
