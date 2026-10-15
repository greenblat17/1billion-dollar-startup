from __future__ import annotations

from datetime import datetime, timezone

import pytest
from fakeredis import FakeAsyncRedis

from app.dialogue import MemoryDialogueStore
from app.load_test import bind_load_test, reset_load_test
from app.metrics import MemoryMetricsStore, MetricRates, RedisMetricsStore
from app.metrics_v2 import MemoryMetricsV2, RedisMetricsV2
from app.pipeline import ClipPipeline
from tests.conftest import FakeLlm, FakeStt, FakeTts

NOW = datetime(2026, 10, 11, 12, tzinfo=timezone.utc).timestamp()


def _live(snapshot: dict) -> dict:
    return {
        "turns": snapshot["turns"],
        "dau": snapshot["dau"],
        "sttSeconds": snapshot["sttSeconds"],
        "ttsChars": snapshot["ttsChars"],
        "llmRequests": snapshot["llmRequests"],
        "chats": snapshot["chats"],
    }


async def _dump(redis: FakeAsyncRedis) -> tuple:
    rows = []
    for key in sorted(await redis.keys("*")):
        kind = await redis.type(key)
        if kind == "hash":
            payload = tuple(sorted((await redis.hgetall(key)).items()))
        elif kind == "set":
            payload = tuple(sorted(await redis.smembers(key)))
        elif kind == "zset":
            payload = tuple(await redis.zrange(key, 0, -1, withscores=True))
        elif kind == "list":
            payload = tuple(await redis.lrange(key, 0, -1))
        else:
            payload = await redis.get(key)
        rows.append((key, kind, payload))
    return tuple(rows)


@pytest.mark.asyncio
async def test_load_test_does_not_move_live_metric_snapshots() -> None:
    store = MemoryMetricsStore(MetricRates())
    v2 = MemoryMetricsV2()
    await store.record_turn("tg-live", 2.0, 8, now=NOW)
    await v2.record_turn("tg-live", now=NOW)
    before = _live(await store.snapshot(now=NOW))
    before_v2 = await v2.snapshot(now=NOW)
    token = bind_load_test(True)
    try:
        await store.record_turn("k6-session", 9.0, 40, now=NOW)
        await store.record_llm(100, 20, 30, now=NOW)
        await store.record_clip_result("pipeline_failed", session_id="k6-session", stage="tts", reason="rate_limit", now=NOW)
        await store.record_provider("stt", "attempt", "rate_limit", now=NOW)
        await store.record_exchange("k6-session", now=NOW)
        await v2.record_turn("k6-session", now=NOW)
        await v2.record_llm("k6-session", "reply", "openai/gpt-5.6-luna", 10, 5, 1, now=NOW)
        await v2.record_stt("k6-session", "whisper-large-v3", 3.0, now=NOW)
        await v2.record_tts("k6-session", "x-ai/grok-voice-tts-1.0", 40, 1, now=NOW)
        await v2.record_error("k6-session", "stt", "failed", now=NOW)
    finally:
        reset_load_test(token)
    assert _live(await store.snapshot(now=NOW)) == before
    assert await v2.snapshot(now=NOW) == before_v2
    await store.record_turn("tg-live", 1.0, 4, now=NOW)
    assert (await store.snapshot(now=NOW))["turns"] == before["turns"] + 1


@pytest.mark.asyncio
async def test_load_test_does_not_write_redis_metric_keys() -> None:
    redis = FakeAsyncRedis(decode_responses=True)
    store = RedisMetricsStore(redis, MetricRates())
    v2 = RedisMetricsV2(redis)
    await store.record_turn("tg-live", 2.0, 8, now=NOW)
    await v2.record_turn("tg-live", now=NOW)
    before = await _dump(redis)
    token = bind_load_test(True)
    try:
        await store.record_turn("k6-session", 9.0, 40, now=NOW)
        await store.record_llm(100, 20, 30, now=NOW)
        await store.record_clip_result(None, session_id="k6-session", now=NOW)
        await v2.record_turn("k6-session", now=NOW)
        await v2.record_stt("k6-session", "whisper-large-v3", 1.5, now=NOW)
        await v2.record_error("k6-session", "tts", "failed", now=NOW)
    finally:
        reset_load_test(token)
    assert await _dump(redis) == before


@pytest.mark.asyncio
async def test_load_test_pipeline_skips_dialogue_and_live_turns() -> None:
    store = MemoryMetricsStore(MetricRates())
    dialogue = MemoryDialogueStore(max_messages=40, ttl_seconds=86400)
    pipeline = ClipPipeline(
        stt=FakeStt(["hello from the probe", "hello there"]),
        llm=FakeLlm(),
        tts=FakeTts(),
        dialogue=dialogue,
        metrics=store,
    )
    token = bind_load_test(True)
    try:
        result = await pipeline.run("k6-session", b"ogg", "audio/ogg", "voice.ogg")
    finally:
        reset_load_test(token)
    assert result.reply_text
    assert await dialogue.history("k6-session") == []
    assert (await store.snapshot(now=NOW))["turns"] == 0
    await pipeline.run("tg-live", b"ogg", "audio/ogg", "voice.ogg")
    assert (await store.snapshot())["turns"] == 1
    assert await dialogue.history("tg-live")
