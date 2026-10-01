"""One-time Telegram announcement for chats known when the audience is frozen.

Run `snapshot` before `send`. Both commands use the same persistent Redis database
as ai-service. The Telegram token is needed only for `test` and `send`.
"""

from __future__ import annotations

import argparse
import asyncio
import os
import re
from collections import Counter

import httpx
from redis.asyncio import Redis

CAMPAIGN = "campaign:2026-10-01-legacy-onboarding"
AUDIENCE_KEY = f"{CAMPAIGN}:audience"
READY_KEY = f"{CAMPAIGN}:ready"
STATUS_KEY = f"{CAMPAIGN}:status"
CALLBACK_DATA = "campaign:onboarding"
TELEGRAM_SESSION = re.compile(r"tg-(?:ChatId\(chatId=([1-9]\d*)\)|([1-9]\d*))\Z")

# HTML mode retains the exact paragraph spacing and bold emphasis in Telegram.
MESSAGE = """Привет! Это Саша, создатель Speaky 👋

За последние дни мы сильно обновили Speaky.

Теперь твой English Buddy лучше понимает твой уровень, точнее подбирает сложность разговора и даёт более полезный разбор речи.

А ещё мы полностью переделали то, как Speaky знакомится с тобой и понимает, как лучше подстраиваться под твой английский.

Чтобы всё это работало корректно <b>именно для тебя, очень важно пройти новый onboarding и ещё раз познакомиться со Speaky</b>.

Он займёт около 2 минут: ты немного поговоришь со Speaky, а он определит твой текущий уровень и поймёт, как лучше вести дальнейшие разговоры.

Если пропустить onboarding, Speaky просто будет знать о твоём английском меньше, поэтому персонализация будет хуже.

И если после него что-то покажется странным, неудобным или, наоборот, понравится — напиши мне: @alexgusev93. Я читаю каждое сообщение и отвечаю сам."""

MARKUP = {"inline_keyboard": [[{"text": "🎙 Пройти onboarding", "callback_data": CALLBACK_DATA}]]}


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
    if not ids:
        raise SystemExit("No known private chats found; check REDIS_URL before freezing the audience")
    await redis.delete(AUDIENCE_KEY)
    if ids:
        await redis.sadd(AUDIENCE_KEY, *(str(value) for value in ids))
    await redis.set(READY_KEY, "1")
    print(f"Audience frozen: {len(ids)} existing private chats")


async def telegram_send(client: httpx.AsyncClient, token: str, recipient: int) -> str:
    url = f"https://api.telegram.org/bot{token}/sendMessage"
    payload = {"chat_id": recipient, "text": MESSAGE, "parse_mode": "HTML", "reply_markup": MARKUP}
    for _ in range(3):
        try:
            response = await client.post(url, json=payload)
        except httpx.RequestError:
            return "uncertain"
        if response.status_code == 429:
            try:
                seconds = min(float(response.json().get("parameters", {}).get("retry_after", 1)), 60)
            except (ValueError, TypeError):
                seconds = 1
            await asyncio.sleep(max(seconds, 1))
            continue
        if response.status_code == 403:
            return "blocked"
        if response.status_code >= 500:
            return "uncertain"
        if response.status_code != 200:
            return f"failed:{response.status_code}"
        return "sent" if response.json().get("ok") is True else "uncertain"
    return "rate_limited"


async def send_campaign(redis: Redis, token: str, limit: int | None = None) -> None:
    if not await redis.exists(READY_KEY):
        raise SystemExit("First run `snapshot` to freeze the existing-user audience")
    ids = sorted(int(value) for value in await redis.smembers(AUDIENCE_KEY))
    counts: Counter[str] = Counter()
    async with httpx.AsyncClient(timeout=15) as client:
        for recipient in ids:
            if limit is not None and sum(counts.values()) >= limit:
                break
            # A pending result is deliberately not retried after a crash: Telegram
            # may have delivered the message without returning its response.
            claimed = await redis.hsetnx(STATUS_KEY, str(recipient), "pending")
            if not claimed:
                continue
            result = await telegram_send(client, token, recipient)
            await redis.hset(STATUS_KEY, str(recipient), result)
            counts[result] += 1
            await asyncio.sleep(0.12)  # comfortably below Telegram's free broadcast rate
    print(f"Audience: {len(ids)}; this run: {dict(counts)}")
    print(f"Persistent delivery status: {STATUS_KEY}")


async def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("snapshot", "preview", "test", "send"))
    parser.add_argument("--chat-id", type=int, help="private chat for a test message")
    parser.add_argument("--limit", type=int, help="maximum sends in this run")
    args = parser.parse_args()
    redis_url = os.environ.get("REDIS_URL")
    if not redis_url:
        raise SystemExit("REDIS_URL is required")
    redis = Redis.from_url(redis_url, decode_responses=True)
    try:
        if args.command == "snapshot":
            await snapshot(redis)
        elif args.command == "preview":
            print(MESSAGE)
            print(MARKUP)
            print(f"Frozen audience: {await redis.scard(AUDIENCE_KEY)}")
        else:
            token = os.environ.get("TELEGRAM_BOT_TOKEN")
            if not token:
                raise SystemExit("TELEGRAM_BOT_TOKEN is required")
            if args.command == "test":
                if not args.chat_id or args.chat_id <= 0:
                    raise SystemExit("--chat-id must be a positive private chat ID")
                async with httpx.AsyncClient(timeout=15) as client:
                    print(await telegram_send(client, token, args.chat_id))
            else:
                if args.limit is not None and args.limit <= 0:
                    raise SystemExit("--limit must be positive")
                await send_campaign(redis, token, args.limit)
    finally:
        await redis.aclose()


if __name__ == "__main__":
    asyncio.run(main())
