from datetime import datetime
from zoneinfo import ZoneInfo

import pytest
from fakeredis import FakeAsyncRedis
from fastapi.testclient import TestClient

from app.metrics_v2 import MemoryMetricsV2, RedisMetricsV2, client_for, cost_micro
from app.main import create_app
from tests.conftest import FakeLlm, FakeStt, FakeTts, test_settings as make_settings
from app.dialogue import MemoryDialogueStore
from app.pipeline import ClipPipeline


def test_client_for_prefixes() -> None:
    assert client_for("tg-ChatId(chatId=1)") == "telegram"
    assert client_for("app-u-1", "android") == "android"
    assert client_for("app-u-1", "ios") == "ios"
    assert client_for("app-u-1", "desktop") == "desktop"
    assert client_for("app-u-1") == "unknown"
    assert client_for("app-u-1", "browser") == "unknown"
    assert client_for("other") == "unknown"


def test_cost_micro_ignores_missing_and_negative() -> None:
    assert cost_micro(None) is None
    assert cost_micro(-1) is None
    assert cost_micro(0.000001) == 1
    assert cost_micro("0.5") == 500_000


@pytest.mark.asyncio
async def test_v2_keeps_money_tokens_and_clients_apart() -> None:
    store = MemoryMetricsV2()
    await store.record_llm("tg-1", "reply", "openai/gpt", 10, 4, 1500)
    await store.record_llm("tg-1", "notes", "openai/gpt", 3, 1, None)
    await store.record_tts("tg-1", "hexgrad/kokoro", 20, 800)
    await store.record_stt("tg-1", "whisper-large-v3", 1.5)
    await store.record_turn("tg-1")
    await store.record_realtime("app-1", "gpt-realtime", {
        "in_text": 1, "in_audio": 2, "out_text": 3, "out_audio": 4, "cached_text": 5, "cached_audio": 6,
    }, platform="ios")
    await store.record_action("tg-1", "text")
    await store.record_error("tg-1", "corrections", "deadline")
    snap = await store.snapshot()
    by_client = {item["client"]: item for item in snap["clients"]}
    telegram = by_client["telegram"]
    assert telegram["costMicro"] == 2300
    assert telegram["costCurrency"] == "USD"
    assert telegram["promptTokens"] == 13
    assert telegram["completionTokens"] == 5
    assert telegram["calls"] == 3
    assert telegram["llmCalls"] == 2
    assert telegram["ttsChars"] == 20
    assert telegram["sttSeconds"] == 1.5
    assert telegram["dau"] == 1
    assert telegram["actions"]["text"] == 1
    assert telegram["errors"]["corrections:deadline"] == 1
    assert telegram["chats"] == [{"session": "tg-1", "turns": 1}]
    assert telegram["realtimeInAudio"] == 0
    ios = by_client["ios"]
    assert ios["costMicro"] == 0
    assert ios["costCurrency"] == ""
    assert ios["realtimeOutAudio"] == 4
    assert ios["dau"] == 1
    assert "android" in by_client
    text = store.prometheus({**snap, "day": "2026-10-02"})
    assert 'client="telegram"' in text
    assert "speaking_cost_micro" in text
    assert 'client="ios"' in text
    assert "speaking_cost_micro{client=\"ios\"" not in text
    assert 'speaking_llm_requests{client="telegram",day="2026-10-02"} 2' in text


@pytest.mark.asyncio
@pytest.mark.parametrize("redis_backed", [False, True])
async def test_admin_overview_counts_telegram_models_and_known_voice_llm_cost(redis_backed: bool) -> None:
    store = RedisMetricsV2(FakeAsyncRedis(decode_responses=True)) if redis_backed else MemoryMetricsV2()
    moment = datetime(2026, 10, 13, 12, tzinfo=ZoneInfo("Europe/Moscow")).timestamp()
    await store.record_telegram_message("tg-1", "voice", "message:1", now=moment)
    await store.record_llm("tg-1", "reply", "model", 100, 10, 4_000, now=moment)
    await store.record_stt("tg-1", "stt", 5, now=moment)
    await store.record_tts("tg-1", "tts", 20, None, now=moment)
    await store.record_llm("app-1", "reply", "model", 100, 10, 9_000, platform="ios", now=moment)
    await store.record_voice_llm_usage("tg-1", 2, 1, 4_000, now=moment)
    report = await store.overview("2026-10-13", "2026-10-13")
    assert report["modelSince"] == "2026-10-13"
    assert report["voiceLlmSince"] == "2026-10-13"
    assert report["days"][0]["llmCalls"] == 1
    assert report["days"][0]["sttCalls"] == 1
    assert report["days"][0]["ttsCalls"] == 1
    assert report["days"][0]["llmCostMicro"] == 4_000
    assert report["days"][0]["voiceLlmCalls"] == 2
    assert report["days"][0]["voiceLlmPricedCalls"] == 1
    assert report["days"][0]["voiceLlmCostMicro"] == 4_000
    assert report["days"][0]["tracked"] is True


@pytest.mark.asyncio
async def test_admin_overview_reads_legacy_days_without_inventing_model_calls() -> None:
    redis = FakeAsyncRedis(decode_responses=True)
    await redis.hset("metrics:day:2026-09-24", mapping={
        "turns": 2, "prompt_tokens": 1346, "completion_tokens": 76,
        "stt_ms": 17114, "tts_chars": 235,
    })
    await redis.sadd("metrics:dau:2026-09-24", "tg-1", "tg-2")
    await redis.hset("metrics:funnel:2026-09-24", mapping={"activated": 2, "start": 1})
    report = await RedisMetricsV2(redis).overview("2026-09-24", "2026-09-25")
    assert report["legacyActiveChatsInPeriod"] == 2
    assert report["legacyDays"][0] == {
        "day": "2026-09-24", "tracked": True, "activeChatsWithTurn": 2,
        "completedTurns": 2, "promptTokens": 1346, "completionTokens": 76,
        "sttMs": 17114, "ttsChars": 235, "firstVoiceChats": 2,
    }
    assert report["legacyDays"][1]["tracked"] is False
    assert report["days"][0]["llmCalls"] == 0
    assert report["days"][0]["tracked"] is False


@pytest.mark.asyncio
async def test_admin_overview_builds_chat_growth_and_redis_dau_across_october_sixth() -> None:
    redis = FakeAsyncRedis(decode_responses=True)
    await redis.hset("metrics:funnel:user:tg-1", mapping={"first_day": "2026-09-24"})
    await redis.hset("metrics:funnel:user:tg-2", mapping={"first_day": "2026-09-25"})
    await redis.hset("metrics:funnel:user:tg-3", mapping={"first_day": "2026-10-06"})
    await redis.sadd("metrics:dau:2026-09-24", "tg-1")
    await redis.sadd("metrics:dau:2026-10-06", "tg-1", "tg-3", "app-1")

    report = await RedisMetricsV2(redis).overview("2026-09-24", "2026-10-07")
    growth = {row["day"]: row for row in report["chatGrowthDays"]}
    dau = {row["day"]: row for row in report["redisDauDays"]}
    assert report["totalChats"] == 3
    assert report["chatGrowthUnattributed"] == 0
    assert growth["2026-09-24"] == {"day": "2026-09-24", "tracked": True,
                                      "newChats": 1, "totalChatsToDate": 1}
    assert growth["2026-10-05"]["totalChatsToDate"] == 2
    assert growth["2026-10-06"]["totalChatsToDate"] == 3
    assert dau["2026-09-24"]["activeChatsWithTurn"] == 1
    assert dau["2026-09-25"] == {"day": "2026-09-25", "tracked": True,
                                   "activeChatsWithTurn": 0}
    assert dau["2026-10-06"]["activeChatsWithTurn"] == 2
    assert dau["2026-10-07"]["activeChatsWithTurn"] == 0


@pytest.mark.asyncio
async def test_admin_overview_history_uses_same_definition_before_and_after_october_sixth() -> None:
    redis = FakeAsyncRedis(decode_responses=True)
    base = "tg-ChatId(chatId=-7)"
    topic = "tg-ChatIdWithThreadId(chatIdWithThreadId=(-7,12))"
    await redis.hset(f"metrics:funnel:user:{base}", mapping={
        "first_day": "2026-10-03", "start_day": "2026-10-03",
        "activated_day": "2026-10-08", "engaged_day": "2026-10-14", "source": "campaign",
    })
    await redis.hset(f"metrics:funnel:user:{topic}", mapping={
        "first_day": "2026-10-08", "activated_day": "2026-10-08", "source": "campaign",
    })
    for day, turns in (("2026-10-03", 1), ("2026-10-08", 3), ("2026-10-14", 4)):
        await redis.hset(f"metrics:day:{day}", mapping={
            "turns": turns, "prompt_tokens": turns * 10, "completion_tokens": turns * 2,
            "stt_ms": turns * 1000, "tts_chars": turns * 20,
        })
    await redis.sadd("metrics:dau:2026-10-03", base)
    await redis.sadd("metrics:dau:2026-10-08", base, topic)
    await redis.sadd("metrics:dau:2026-10-14", topic)
    report = await RedisMetricsV2(redis).overview("2026-10-03", "2026-10-14")
    rows = {row["day"]: row for row in report["historyDays"]}
    assert report["totalChats"] == 1
    assert report["historyActiveChatsInPeriod"] == 1
    assert report["historyActiveSessionsInPeriod"] == 1
    assert rows["2026-10-03"]["newChats"] == 1
    assert rows["2026-10-08"]["newChats"] == 0
    assert rows["2026-10-08"]["activeChatsWithTurn"] == 1
    assert rows["2026-10-08"]["chatsWithTurnToDate"] == 1
    assert rows["2026-10-08"]["firstVoiceChats"] == 1
    assert rows["2026-10-08"]["returningActiveChats"] == 1
    assert rows["2026-10-14"]["firstVoiceChatsToDate"] == 1
    assert rows["2026-10-14"]["engagedChats"] == 1
    assert rows["2026-10-14"]["completedTurns"] == 4
    assert rows["2026-10-14"]["activeChats7d"] == 1
    assert rows["2026-10-14"]["chatsWithTurnToDate"] == 1
    assert report["sourceBreakdown"] == [{"source": "campaign", "newChats": 1, "firstVoiceChats": 1}]


@pytest.mark.asyncio
async def test_admin_overview_cumulative_turn_users_include_days_before_selected_period() -> None:
    redis = FakeAsyncRedis(decode_responses=True)
    await redis.sadd("metrics:dau:2026-10-03", "tg-1")
    await redis.sadd("metrics:dau:2026-10-08", "tg-1", "tg-2")
    await redis.sadd("metrics:dau:2026-10-14", "tg-2", "tg-3", "app-1")

    report = await RedisMetricsV2(redis).overview("2026-10-14", "2026-10-14")
    row = report["historyDays"][0]
    assert row["activeChatsWithTurn"] == 2
    assert row["chatsWithTurnToDate"] == 3


@pytest.mark.asyncio
async def test_admin_overview_retention_and_full_rolling_windows(monkeypatch) -> None:
    redis = FakeAsyncRedis(decode_responses=True)
    monkeypatch.setattr("app.metrics_v2.time.time", lambda: datetime(
        2026, 10, 25, 12, tzinfo=ZoneInfo("Europe/Moscow")).timestamp())
    for chat in ("tg-1", "tg-2"):
        await redis.hset(f"metrics:funnel:user:{chat}", mapping={
            "first_day": "2026-09-24", "activated_day": "2026-09-24",
        })
    await redis.sadd("metrics:dau:2026-09-24", "tg-1", "tg-2")
    await redis.sadd("metrics:dau:2026-09-25", "tg-1")
    await redis.sadd("metrics:dau:2026-10-01", "tg-1", "tg-2")
    await redis.sadd("metrics:dau:2026-10-24", "tg-1")

    cohort_report = await RedisMetricsV2(redis).overview("2026-09-24", "2026-09-24")
    assert cohort_report["retention"] == [
        {"day": 1, "eligible": 2, "returned": 1},
        {"day": 7, "eligible": 2, "returned": 2},
        {"day": 30, "eligible": 2, "returned": 1},
    ]
    report = await RedisMetricsV2(redis).overview("2026-10-24", "2026-10-24")
    rows = {row["day"]: row for row in report["historyDays"]}
    assert rows["2026-10-24"]["activeChats30d"] == 2
    assert rows["2026-10-24"]["activeChats7d"] == 1
    prior = await RedisMetricsV2(redis).overview("2026-10-22", "2026-10-22")
    assert prior["historyDays"][0]["activeChats30d"] is None

    monkeypatch.setattr("app.metrics_v2.time.time", lambda: datetime(
        2026, 10, 24, 12, tzinfo=ZoneInfo("Europe/Moscow")).timestamp())
    incomplete = await RedisMetricsV2(redis).overview("2026-09-24", "2026-09-24")
    assert incomplete["retention"][2] == {"day": 30, "eligible": 0, "returned": 0}


@pytest.mark.asyncio
@pytest.mark.parametrize("redis_backed", [False, True])
async def test_prometheus_exports_llm_requests_for_past_days(redis_backed: bool) -> None:
    store = RedisMetricsV2(FakeAsyncRedis(decode_responses=True)) if redis_backed else MemoryMetricsV2()
    moscow = ZoneInfo("Europe/Moscow")
    past = datetime(2025, 10, 5, 12, tzinfo=moscow).timestamp()
    later = datetime(2025, 10, 18, 12, tzinfo=moscow).timestamp()
    await store.record_llm("tg-1", "reply", "openai/gpt", 8, 2, None, now=past)
    await store.record_llm("tg-1", "notes", "openai/gpt", 3, 1, None, now=past)
    snap = await store.snapshot(now=later)
    text = store.prometheus({**snap, "day": "2025-10-18"})
    assert 'speaking_llm_requests{client="telegram",day="2025-10-05"} 2' in text


@pytest.mark.asyncio
@pytest.mark.parametrize("redis_backed", [False, True])
async def test_telegram_journey_counts_distinct_users_at_cumulative_voice_milestones(redis_backed: bool) -> None:
    store = RedisMetricsV2(FakeAsyncRedis(decode_responses=True)) if redis_backed else MemoryMetricsV2()
    await store.record_telegram_message("tg-first", "start", "message:1", now=1_000_000_000)
    await store.record_telegram_message("tg-first", "start", "message:1", now=1_000_000_000)
    await store.record_telegram_message("tg-second", "start", "message:1", now=1_000_000_000)
    await store.record_telegram_message("app-android", "voice", "message:2", now=1_000_000_000)
    assert (await store.snapshot())["telegramJourney"]["onlyStart"] == 2

    for index in range(2, 22):
        await store.record_telegram_message("tg-first", "voice", f"message:{index}", now=1_000_000_000)
    await store.record_telegram_message("tg-first", "voice", "message:21", now=1_000_000_000)
    await store.record_telegram_message("tg-second", "voice", "message:2", now=1_000_000_000)
    await store.record_telegram_message("tg-third", "voice", "message:1", now=1_000_000_000)
    await store.record_telegram_message("tg-third", "start", "message:2", now=1_000_000_000)
    journey = (await store.snapshot())["telegramJourney"]
    assert journey == {
        "since": "2001-09-09",
        "onlyStart": 0,
        "atLeast": {"1": 3, "3": 1, "5": 1, "10": 1, "20": 1},
    }
    assert "speaking_telegram_voice_users 3\n" in store.prometheus(await store.snapshot())


@pytest.mark.asyncio
async def test_first_voice_backfill_uses_legacy_activation_without_inflating_higher_milestones() -> None:
    redis = FakeAsyncRedis(decode_responses=True)
    store = RedisMetricsV2(redis)
    await redis.hset("metrics:funnel:user:tg-old", "activated_day", "2026-09-24")
    await redis.hset("metrics:funnel:user:tg-overlap", "activated_day", "2026-09-25")
    await redis.hset("metrics:funnel:user:tg-too-early", "activated_day", "2026-09-23")
    await store.record_telegram_message("tg-old", "start", "message:1")
    await store.record_telegram_message("tg-overlap", "voice", "message:1")

    journey = (await store.snapshot())["telegramJourney"]
    assert journey["firstVoiceSince"] == "2026-09-24"
    assert journey["atLeast"] == {"1": 2, "3": 0, "5": 0, "10": 0, "20": 0}
    assert journey["onlyStart"] == 0
    assert "speaking_telegram_voice_users 2\n" in store.prometheus(await store.snapshot())

    await store.record_telegram_message("tg-old", "voice", "message:2")
    journey = (await store.snapshot())["telegramJourney"]
    assert journey["atLeast"]["1"] == 2
    assert journey["atLeast"]["3"] == 0
    assert await redis.scard("metrics:v2:telegram:journey:historical-only") == 0


def test_action_endpoint_records_telegram_message_id_once() -> None:
    v2 = MemoryMetricsV2()
    pipeline = ClipPipeline(FakeStt([]), FakeLlm(), FakeTts(), MemoryDialogueStore(40, 86400), v2=v2)
    app = create_app(settings=make_settings(), pipeline=pipeline)
    with TestClient(app, headers={"X-Internal-Token": "test-internal-token"}) as client:
        for _ in range(2):
            assert client.post("/internal/metrics/action", json={
                "sessionId": "tg-1", "action": "voice", "eventId": "message:42",
            }).status_code == 200
        journey = client.get("/internal/metrics").json()["v2"]["telegramJourney"]
    assert journey["atLeast"]["1"] == 1
