from __future__ import annotations

import json
from datetime import datetime, timezone
from zoneinfo import ZoneInfo
from types import SimpleNamespace

import pytest
from fakeredis import FakeAsyncRedis
from fastapi.testclient import TestClient

from app.dialogue import MemoryDialogueStore
from app.llm import OpenAiChatModel, read_usage
from app.main import create_app
from app.metrics import (
    DEFAULT_RATES,
    LLM_COMPLETION_USD_PER_MILLION,
    LLM_PROMPT_USD_PER_MILLION,
    RUB_PER_USD,
    STT_USD_PER_HOUR,
    TTS_USD_PER_MILLION_CHARS,
    MemoryMetricsStore,
    MetricRates,
    RedisMetricsStore,
    build_metrics_store,
    metrics_day,
)
from app.pipeline import CLARIFY_TEXT, ClipPipeline
from app.stt import SttResult, duration_seconds
from tests.conftest import FakeLlm, FakeRealtime, FakeReviewer, FakeStt, FakeTts
from tests.conftest import test_settings as make_settings

AUTH = {"X-Internal-Token": "test-internal-token"}
FULL_RATES = MetricRates(
    prompt_rub_per_million=2,
    completion_rub_per_million=4,
    stt_rub_per_minute=3,
    tts_rub_per_million_chars=5,
)


def test_duration_seconds_uses_positive_payload_value() -> None:
    assert duration_seconds({}) == 0
    assert duration_seconds({"duration": 1.25}) == 1.25
    assert duration_seconds({"duration": "nope"}) == 0
    assert duration_seconds({"duration": -2}) == 0


def test_read_usage_reads_object_or_dict() -> None:
    response = SimpleNamespace(usage=SimpleNamespace(prompt_tokens=10, completion_tokens=4))
    assert read_usage(response) == (10, 4)
    assert read_usage(SimpleNamespace(usage={"prompt_tokens": 3, "completion_tokens": 1})) == (3, 1)
    assert read_usage(SimpleNamespace(usage=None)) == (0, 0)


def test_build_store_without_redis_url_is_memory() -> None:
    assert isinstance(build_metrics_store(make_settings()), MemoryMetricsStore)


def test_default_rates_are_list_price_times_ruble_rate() -> None:
    assert DEFAULT_RATES.configured is True
    assert DEFAULT_RATES.prompt_rub_per_million == pytest.approx(LLM_PROMPT_USD_PER_MILLION * RUB_PER_USD)
    assert DEFAULT_RATES.completion_rub_per_million == pytest.approx(LLM_COMPLETION_USD_PER_MILLION * RUB_PER_USD)
    assert DEFAULT_RATES.stt_rub_per_minute == pytest.approx(STT_USD_PER_HOUR / 60 * RUB_PER_USD)
    assert DEFAULT_RATES.tts_rub_per_million_chars == pytest.approx(TTS_USD_PER_MILLION_CHARS * RUB_PER_USD)


@pytest.mark.asyncio
async def test_built_store_prices_a_turn_from_those_constants() -> None:
    store = build_metrics_store(make_settings())
    moment = 1_800_000_000.0
    await store.record_llm(1_000_000, 0, 100, now=moment)
    await store.record_turn("tg-1", 0, 0, now=moment)
    snap = await store.snapshot(now=moment)
    assert snap["ratesConfigured"] is True
    assert snap["rubPerTurn"] == pytest.approx(LLM_PROMPT_USD_PER_MILLION * RUB_PER_USD)


class _Stores:
    def __init__(self, rates: MetricRates) -> None:
        self.redis = FakeAsyncRedis(decode_responses=True)
        self.stores = [MemoryMetricsStore(rates), RedisMetricsStore(self.redis, rates)]

    async def aclose(self) -> None:
        for store in self.stores:
            await store.aclose()


@pytest.mark.asyncio
async def test_day_counters_window_and_moscow_date() -> None:
    opened = _Stores(MetricRates())
    moment = datetime(2026, 9, 24, 21, 30, tzinfo=timezone.utc).timestamp()
    try:
        for store in opened.stores:
            await store.record_llm(7, 0, 100, now=moment - 121)
            await store.record_llm(1000, 0, 100, now=moment - 90)
            await store.record_llm(100, 50, 500, now=moment - 10)
            await store.record_llm(20, 30, 500, now=moment - 5)
            snap = await store.snapshot(now=moment)
            assert snap["day"] == "2026-09-25"
            assert snap["timezone"] == "Europe/Moscow"
            assert snap["promptTokens"] == 1127
            assert snap["completionTokens"] == 80
            assert snap["tpm"] == 200
            assert snap["tps"] == pytest.approx(80)
            assert metrics_day(moment) == "2026-09-25"
    finally:
        await opened.aclose()


@pytest.mark.asyncio
async def test_rubles_need_every_rate() -> None:
    moment = 1_800_000_000.0
    full = _Stores(FULL_RATES)
    partial = _Stores(
        MetricRates(
            prompt_rub_per_million=2,
            completion_rub_per_million=4,
            stt_rub_per_minute=3,
        ),
    )
    try:
        for store in full.stores:
            await store.record_llm(1_000_000, 1_000_000, 100, now=moment)
            await store.record_turn("tg-1", 60, 0, now=moment)
            await store.record_turn("tg-1", 0, 1_000_000, now=moment)
            snap = await store.snapshot(now=moment)
            assert snap["ratesConfigured"] is True
            assert snap["rubPerTurn"] == pytest.approx(7)
            assert snap["rubPerDau"] == pytest.approx(14)
            assert snap["turns"] == 2
            assert snap["dau"] == 1
            assert snap["sttSeconds"] == pytest.approx(60)
            assert snap["ttsChars"] == 1_000_000
        for store in partial.stores:
            await store.record_llm(1_000_000, 1_000_000, 100, now=moment)
            await store.record_turn("tg-1", 60, 1_000_000, now=moment)
            snap = await store.snapshot(now=moment)
            assert snap["ratesConfigured"] is False
            assert snap["rubPerTurn"] is None
            assert snap["rubPerDau"] is None
            assert snap["promptTokens"] == 1_000_000
    finally:
        await full.aclose()
        await partial.aclose()


@pytest.mark.asyncio
async def test_chats_are_newest_first_and_keys_do_not_expire() -> None:
    opened = _Stores(MetricRates())
    moment = 1_800_000_000.0
    try:
        redis_store = opened.stores[1]
        await redis_store.record_turn("tg-a", 1, 2, now=moment)
        await redis_store.record_turn("tg-b", 1, 2, now=moment + 10)
        await redis_store.record_turn("tg-a", 1, 2, now=moment + 15)
        await redis_store.record_turn("  ", 1, 2, now=moment + 20)
        snap = await redis_store.snapshot(now=moment + 15)
        assert [chat["sessionId"] for chat in snap["chats"]] == ["tg-a", "tg-b"]
        assert snap["chats"][0]["turns"] == 2
        assert snap["chats"][1]["turns"] == 1
        assert snap["turns"] == 3
        assert snap["dau"] == 2
        day_key = f"metrics:day:{snap['day']}"
        assert await opened.redis.ttl(day_key) == -1
        assert await opened.redis.ttl("metrics:chats") == -1
        assert "transcript" not in json.dumps(snap)
    finally:
        await opened.aclose()


@pytest.mark.asyncio
async def test_memory_chats_follow_the_same_order() -> None:
    store = MemoryMetricsStore(MetricRates())
    moment = 1_800_000_000.0
    await store.record_turn("tg-a", 1, 1, now=moment)
    await store.record_turn("tg-b", 1, 1, now=moment + 10)
    await store.record_turn("tg-a", 1, 1, now=moment + 15)
    snap = await store.snapshot(now=moment + 15)
    assert [(chat["sessionId"], chat["turns"]) for chat in snap["chats"]] == [("tg-a", 2), ("tg-b", 1)]


@pytest.mark.asyncio
async def test_chats_show_the_latest_telegram_profile() -> None:
    opened = _Stores(MetricRates())
    moment = 1_800_000_000.0
    try:
        for store in opened.stores:
            await store.record_profile("tg-a", "@old_name", "Alex")
            await store.record_turn("tg-a", 1, 1, now=moment)
            await store.record_turn("tg-b", 1, 1, now=moment + 10)
            await store.record_profile("tg-a", "new_name", "  Alex\n Green ")
            snap = await store.snapshot(now=moment + 10)
            rows = {chat["sessionId"]: chat for chat in snap["chats"]}
            assert rows["tg-a"]["username"] == "new_name"
            assert rows["tg-a"]["name"] == "Alex Green"
            assert rows["tg-a"]["turns"] == 1
            assert rows["tg-b"]["username"] is None
            assert rows["tg-b"]["name"] is None
            await store.record_profile("tg-a", None, "Alex")
            cleared = await store.snapshot(now=moment + 10)
            row = next(chat for chat in cleared["chats"] if chat["sessionId"] == "tg-a")
            assert row["username"] is None
            assert row["name"] == "Alex"
    finally:
        await opened.aclose()


class _ScriptedStt:
    def __init__(self, results: list[SttResult]) -> None:
        self._results = list(results)

    async def transcribe(self, audio: bytes, content_type: str, filename: str) -> SttResult:
        return self._results.pop(0)


class _BoomTts:
    async def synthesize(self, text: str) -> bytes:
        raise RuntimeError("tts down")


@pytest.mark.asyncio
async def test_pipeline_counts_finished_turns_only() -> None:
    store = MemoryMetricsStore(MetricRates())
    pipeline = ClipPipeline(
        stt=_ScriptedStt(
            [
                SttResult(text="", no_speech=True, duration_seconds=2.0),
                SttResult(text="hello", duration_seconds=3.5),
            ],
        ),
        llm=FakeLlm(),
        tts=FakeTts(),
        dialogue=MemoryDialogueStore(max_messages=40, ttl_seconds=86400),
        metrics=store,
    )
    await pipeline.run("tg-1", b"a", "audio/ogg", "voice.ogg")
    await pipeline.run("tg-1", b"b", "audio/ogg", "voice.ogg")
    snap = await store.snapshot()
    assert snap["turns"] == 2
    assert snap["sttSeconds"] == pytest.approx(5.5)
    assert snap["ttsChars"] == len(CLARIFY_TEXT) + len("Got it: hello")

    failed = MemoryMetricsStore(MetricRates())
    broken = ClipPipeline(
        stt=_ScriptedStt([SttResult(text="hello", duration_seconds=1)]),
        llm=FakeLlm(),
        tts=_BoomTts(),
        dialogue=MemoryDialogueStore(max_messages=40, ttl_seconds=86400),
        metrics=failed,
    )
    with pytest.raises(RuntimeError, match="tts down"):
        await broken.run("tg-2", b"c", "audio/ogg", "voice.ogg")
    assert (await failed.snapshot())["turns"] == 0


class _Completions:
    async def create(self, **kwargs: object) -> SimpleNamespace:
        return SimpleNamespace(
            choices=[SimpleNamespace(message=SimpleNamespace(content='{"reply":"Hi"}'))],
            usage=SimpleNamespace(prompt_tokens=10, completion_tokens=4),
        )


class _Client:
    def __init__(self) -> None:
        self.chat = SimpleNamespace(completions=_Completions())


@pytest.mark.asyncio
async def test_chat_model_records_both_token_sides() -> None:
    store = MemoryMetricsStore(MetricRates())
    model = OpenAiChatModel(_Client(), "openai/gpt-4o-mini", metrics=store)
    assert await model.complete_reply([], "hello") == "Hi"
    snap = await store.snapshot()
    assert snap["promptTokens"] == 10
    assert snap["completionTokens"] == 4
    assert snap["tpm"] == 14


@pytest.mark.asyncio
async def test_internal_metrics_requires_token() -> None:
    store = MemoryMetricsStore(MetricRates())
    await store.record_turn("tg-9", 1.0, 4)
    pipeline = ClipPipeline(
        stt=FakeStt(["hi"]),
        llm=FakeLlm(),
        tts=FakeTts(),
        dialogue=MemoryDialogueStore(max_messages=40, ttl_seconds=86400),
        metrics=store,
    )
    app = create_app(
        settings=make_settings(),
        pipeline=pipeline,
        realtime=FakeRealtime(),
        reviewer=FakeReviewer(),
    )
    with TestClient(app) as client:
        assert client.get("/internal/metrics").status_code == 401
        response = client.get("/internal/metrics", headers=AUTH)
    assert response.status_code == 200
    body = response.json()
    assert body["turns"] == 1
    assert body["chats"][0]["sessionId"] == "tg-9"
    assert "transcript" not in response.text


def _moscow(day: int, hour: int = 12) -> float:
    return datetime(2026, 9, day, hour, tzinfo=ZoneInfo("Europe/Moscow")).timestamp()


def _source_row(snap: dict, name: str) -> dict:
    return next(row for row in snap["funnelSources"] if row["source"] == name)


@pytest.mark.asyncio
async def test_funnel_keeps_the_first_source_and_counts_each_stage_once_per_rule() -> None:
    opened = _Stores(MetricRates())
    day1 = _moscow(22)
    day2 = _moscow(23)
    day3 = _moscow(24)
    try:
        for store in opened.stores:
            await store.record_start("tg-1", "clubs", now=day1)
            await store.record_start("tg-1", "friends", now=day1)
            await store.record_voice("tg-1", now=day1)
            await store.record_voice("tg-1", now=day1)
            await store.record_start("tg-1", "friends", now=day2)
            await store.record_voice("tg-1", now=day2)
            await store.record_voice("tg-1", now=day3)
            for _ in range(2):
                await store.record_exchange("tg-1", now=day3)
            before = await store.snapshot(now=day3)
            assert before["funnelDays"][0]["engaged"] == 0
            await store.record_exchange("tg-1", now=day3)
            await store.record_exchange("tg-1", now=day3)
            snap = await store.snapshot(now=day3)
            clubs = _source_row(snap, "clubs")
            assert clubs["start"] == 1
            assert clubs["activated"] == 1
            assert clubs["engaged"] == 1
            assert clubs["returned"] == 2
            assert all(row["source"] != "friends" for row in snap["funnelSources"])
            assert snap["activated7"] == 1
            assert [row["day"] for row in snap["funnelDays"][:3]] == [
                "2026-09-24",
                "2026-09-23",
                "2026-09-22",
            ]
            assert len(snap["funnelDays"]) == 14
            assert "transcript" not in json.dumps(snap)
    finally:
        await opened.aclose()


@pytest.mark.asyncio
async def test_empty_start_does_not_block_a_later_source() -> None:
    opened = _Stores(MetricRates())
    moment = _moscow(24)
    try:
        for store in opened.stores:
            await store.record_start("tg-2", None, now=moment)
            await store.record_start("tg-2", "bad payload", now=moment)
            await store.record_start("tg-2", "clubs", now=moment)
            await store.record_voice("tg-2", now=moment)
            snap = await store.snapshot(now=moment)
            assert _source_row(snap, "direct")["start"] == 1
            assert _source_row(snap, "clubs")["activated"] == 1
            assert _source_row(snap, "clubs")["start"] == 0
    finally:
        await opened.aclose()


@pytest.mark.asyncio
async def test_funnel_routes_record_a_start() -> None:
    store = MemoryMetricsStore(MetricRates())
    pipeline = ClipPipeline(
        stt=FakeStt(["hi"]),
        llm=FakeLlm(),
        tts=FakeTts(),
        dialogue=MemoryDialogueStore(max_messages=40, ttl_seconds=86400),
        metrics=store,
    )
    app = create_app(
        settings=make_settings(),
        pipeline=pipeline,
        realtime=FakeRealtime(),
        reviewer=FakeReviewer(),
    )
    with TestClient(app) as client:
        denied = client.post("/internal/funnel/start", json={"sessionId": "tg-3", "source": "clubs"})
        assert denied.status_code == 401
        recorded = client.post(
            "/internal/funnel/start",
            headers=AUTH,
            json={"sessionId": "tg-3", "source": "clubs"},
        )
        voice = client.post(
            "/internal/funnel/voice",
            headers=AUTH,
            json={"sessionId": "tg-3", "username": "alex", "name": "Alex"},
        )
        bare = client.post("/internal/funnel/voice", headers=AUTH, json={"sessionId": "tg-3"})
    assert recorded.status_code == 200
    assert voice.status_code == 200
    assert bare.status_code == 200
    await store.record_turn("tg-3", 1, 1)
    snap = await store.snapshot()
    assert _source_row(snap, "clubs")["start"] == 1
    assert _source_row(snap, "clubs")["activated"] == 1
    assert snap["chats"][0]["username"] == "alex"
    assert snap["chats"][0]["name"] == "Alex"
