import json

import fakeredis.aioredis
import httpx
import pytest

from app.legacy_onboarding_campaign import (
    AUDIENCE_KEY,
    CALLBACK_DATA,
    MARKUP,
    MESSAGE,
    READY_KEY,
    STATUS_KEY,
    chat_id,
    known_chat_ids,
    snapshot,
    send_campaign,
    telegram_send,
)


def test_telegram_message_preserves_copy_and_formatting():
    assert MESSAGE.startswith("Привет! Это Саша, создатель Speaky 👋\n\n")
    assert "<b>именно для тебя, очень важно пройти новый onboarding и ещё раз познакомиться со Speaky</b>" in MESSAGE
    assert MESSAGE.endswith("Я читаю каждое сообщение и отвечаю сам.")
    assert MARKUP == {"inline_keyboard": [[{"text": "🎙 Пройти onboarding", "callback_data": CALLBACK_DATA}]]}


def test_only_private_telegram_session_ids_are_accepted():
    assert chat_id("tg-ChatId(chatId=123)") == 123
    assert chat_id("tg-123") == 123
    assert chat_id("tg-ChatId(chatId=-123)") is None
    assert chat_id("mobile-123") is None
    assert chat_id("tg-123-extra") is None


@pytest.mark.asyncio
async def test_snapshot_freezes_all_known_chats_and_does_not_add_later_users():
    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    await redis.hset("metrics:funnel:user:tg-ChatId(chatId=101)", "first_day", "2026-09-25")
    await redis.hset("metrics:funnel:user:mobile-4", "first_day", "2026-09-25")
    await redis.zadd("metrics:chats", {"tg-202": 1, "tg-ChatId(chatId=101)": 2, "tg--303": 3})
    assert await known_chat_ids(redis) == {101, 202}
    await snapshot(redis)
    assert await redis.exists(READY_KEY)
    assert await redis.smembers(AUDIENCE_KEY) == {"101", "202"}
    await redis.hset("metrics:funnel:user:tg-404", "first_day", "2026-10-01")
    await snapshot(redis)
    assert await redis.smembers(AUDIENCE_KEY) == {"101", "202"}
    assert await redis.hlen(STATUS_KEY) == 0


@pytest.mark.asyncio
async def test_telegram_request_uses_html_and_callback_button():
    requests = []

    def handle(request):
        requests.append(request)
        return httpx.Response(200, json={"ok": True, "result": {"message_id": 1}})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
        assert await telegram_send(client, "test-token", 123) == "sent"
    payload = json.loads(requests[0].content)
    assert payload["parse_mode"] == "HTML"
    assert payload["text"] == MESSAGE
    assert payload["reply_markup"] == MARKUP
    assert payload["chat_id"] == 123


@pytest.mark.asyncio
async def test_send_resumes_without_repeating_attempted_recipients(monkeypatch):
    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    await redis.set(READY_KEY, "1")
    await redis.sadd(AUDIENCE_KEY, "101", "202")
    sent = []

    async def fake_send(client, token, recipient):
        sent.append(recipient)
        return "sent"

    monkeypatch.setattr("app.legacy_onboarding_campaign.telegram_send", fake_send)
    await send_campaign(redis, "test-token", limit=1)
    await send_campaign(redis, "test-token")
    await send_campaign(redis, "test-token")
    assert sent == [101, 202]
    assert await redis.hgetall(STATUS_KEY) == {"101": "sent", "202": "sent"}
