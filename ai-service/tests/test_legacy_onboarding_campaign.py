import fakeredis.aioredis
import pytest

from app.legacy_onboarding_campaign import (
    AUDIENCE_KEY,
    READY_KEY,
    STATUS_KEY,
    chat_id,
    campaign_status,
    claim_batch,
    known_chat_ids,
    report_delivery,
    snapshot,
)


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
async def test_admin_claim_and_report_use_only_frozen_audience():
    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    await redis.sadd(AUDIENCE_KEY, "101", "202")
    assert await claim_batch(redis, limit=1) == []
    await redis.set(READY_KEY, "1")
    assert await claim_batch(redis, limit=1) == [101]
    assert await claim_batch(redis, limit=1) == [202]
    assert await claim_batch(redis, limit=1) == []
    assert not await report_delivery(redis, 303, "sent")
    assert not await report_delivery(redis, 101, "unexpected")
    assert await report_delivery(redis, 101, "sent")
    assert not await report_delivery(redis, 101, "sent")
    assert await report_delivery(redis, 202, "failed")
    assert await campaign_status(redis) == {
        "ready": True,
        "audience": 2,
        "remaining": 0,
        "sent": 1,
        "excluded": 0,
        "blocked": 0,
        "failed": 1,
        "uncertain": 0,
    }


@pytest.mark.asyncio
async def test_completed_users_are_excluded_from_frozen_audience_before_send():
    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    await redis.sadd(AUDIENCE_KEY, "101", "202", "303")
    await redis.set(READY_KEY, "1")
    await redis.set("onboarding:tg-101", '{"status":"completed"}')
    await redis.set("assessment:tg-ChatId(chatId=202)", '{"cefr":"B1"}')

    status = await campaign_status(redis)
    assert status["audience"] == 3
    assert status["excluded"] == 2
    assert status["remaining"] == 1
    assert await claim_batch(redis, limit=3) == [303]
    assert await redis.hget(STATUS_KEY, "101") == "skipped_completed"
    assert await redis.hget(STATUS_KEY, "202") == "skipped_completed"


@pytest.mark.asyncio
async def test_user_completing_after_snapshot_is_excluded_at_claim():
    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    await redis.sadd(AUDIENCE_KEY, "101", "202")
    await redis.set(READY_KEY, "1")
    assert (await campaign_status(redis))["remaining"] == 2
    await redis.set("assessment:tg-101", '{"cefr":"B1"}')

    assert await claim_batch(redis, limit=2) == [202]
    assert (await campaign_status(redis))["excluded"] == 1
