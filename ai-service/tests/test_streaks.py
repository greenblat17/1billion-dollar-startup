from __future__ import annotations

from datetime import datetime
from zoneinfo import ZoneInfo

import pytest
from fakeredis import FakeAsyncRedis
from fastapi.testclient import TestClient

from app.dialogue import MemoryDialogueStore
from app.main import create_app
from app.metrics import MemoryMetricsStore, MetricRates, RedisMetricsStore
from app.pipeline import ClipPipeline
from app.streaks import MemoryStreakStore, RedisStreakStore, bucket
from tests.conftest import FakeLlm, FakeRealtime, FakeReviewer, FakeStt, FakeTts
from tests.conftest import test_settings as make_settings

AUTH = {"X-Internal-Token": "test-internal-token"}
MSK = ZoneInfo("Europe/Moscow")


def _at(day: int, hour: int = 12, month: int = 9) -> float:
    return datetime(2026, month, day, hour, tzinfo=MSK).timestamp()


class _Pairs:
    def __init__(self) -> None:
        self.redis = FakeAsyncRedis(decode_responses=True)
        memory_metrics = MemoryMetricsStore(MetricRates())
        redis_metrics = RedisMetricsStore(self.redis, MetricRates())
        self.pairs = [
            (memory_metrics, MemoryStreakStore(memory_metrics)),
            (redis_metrics, RedisStreakStore(self.redis)),
        ]

    async def aclose(self) -> None:
        await self.redis.aclose()


def test_bucket_edges() -> None:
    assert [bucket(value) for value in (0, 1, 2, 6, 7, 13, 14)] == [
        "0",
        "1",
        "2_6",
        "2_6",
        "7_13",
        "7_13",
        "14_plus",
    ]


@pytest.mark.asyncio
async def test_streak_grows_resets_and_marks_a_record() -> None:
    opened = _Pairs()
    try:
        for _metrics, streaks in opened.pairs:
            first = await streaks.record_activity("tg-1", now=_at(1))
            assert (first.current, first.best, first.first_today, first.first_ever, first.new_record) == (
                1,
                1,
                True,
                True,
                False,
            )
            second = await streaks.record_activity("tg-1", now=_at(2))
            assert (second.current, second.new_record, second.first_ever) == (2, False, False)
            again = await streaks.record_activity("tg-1", now=_at(2, 18))
            assert again.first_today is False
            assert again.current == 2
            third = await streaks.record_activity("tg-1", now=_at(3))
            assert (third.current, third.best, third.new_record) == (3, 3, True)
            broken = await streaks.record_activity("tg-1", now=_at(5))
            assert (broken.current, broken.best, broken.new_record, broken.first_ever) == (1, 3, False, False)
            assert await streaks.shown("tg-1", now=_at(7)) == 0
            assert (await streaks.profile("tg-1", now=_at(5)))["best"] == 3
    finally:
        await opened.aclose()


@pytest.mark.asyncio
async def test_moscow_midnight_splits_the_day() -> None:
    opened = _Pairs()
    evening = datetime(2026, 9, 26, 23, 30, tzinfo=MSK).timestamp()
    morning = datetime(2026, 9, 27, 0, 30, tzinfo=MSK).timestamp()
    try:
        for _metrics, streaks in opened.pairs:
            await streaks.record_activity("tg-1", now=evening)
            next_day = await streaks.record_activity("tg-1", now=morning)
            assert next_day.current == 2
            assert next_day.first_today is True
    finally:
        await opened.aclose()


@pytest.mark.asyncio
async def test_backfill_replays_dau_once_and_sets_release_day() -> None:
    opened = _Pairs()
    try:
        for metrics, streaks in opened.pairs:
            await metrics.record_turn("tg-1", 1, 1, now=_at(22))
            await metrics.record_turn("tg-1", 1, 1, now=_at(23))
            await metrics.record_turn("tg-1", 1, 1, now=_at(25))
            await streaks.backfill(now=_at(26))
            await streaks.backfill(now=_at(26, 18))
            profile = await streaks.profile("tg-1", now=_at(26))
            assert profile["current"] == 1
            assert profile["best"] == 2
            assert profile["last7"][-2] is True
            assert profile["last7"][-1] is False
            snapshot = await streaks.snapshot(now=_at(26))
            assert snapshot["retention"]["releasedDay"] == "2026-09-26"
    finally:
        await opened.aclose()


@pytest.mark.asyncio
async def test_retention_uses_activation_day_and_exact_offsets() -> None:
    opened = _Pairs()
    try:
        for metrics, streaks in opened.pairs:
            await metrics.record_voice("tg-1", now=_at(3, month=8))
            await metrics.record_turn("tg-1", 1, 1, now=_at(3, month=8))
            await metrics.record_turn("tg-1", 1, 1, now=_at(4, month=8))
            await metrics.record_voice("tg-2", now=_at(4, month=8))
            await metrics.record_turn("tg-2", 1, 1, now=_at(4, month=8))
            await metrics.record_voice("tg-3", now=_at(25))
            await metrics.record_turn("tg-3", 1, 1, now=_at(25))
            await streaks.backfill(now=_at(26))
            snapshot = await streaks.snapshot(now=_at(26))
            buckets = {row["bucket"]: row["users"] for row in snapshot["buckets"]}
            assert buckets["0"] == 2
            assert buckets["1"] == 1
            cohorts = {row["week"]: row for row in snapshot["retention"]["cohorts"]}
            assert len(snapshot["retention"]["cohorts"]) == 8
            august = cohorts["2026-08-03"]
            assert august["size"] == 2
            assert august["d1"] == 0.5
            assert august["d7"] == 0
            assert august["d30"] == 0
            current = cohorts["2026-09-21"]
            assert current["size"] == 1
            assert current["d1"] == 0
            assert current["d7"] is None
            assert current["d30"] is None
            assert snapshot["retention"]["before"]["size"] == 3
            assert snapshot["retention"]["after"]["size"] == 0
            assert snapshot["retention"]["after"]["d1"] is None
    finally:
        await opened.aclose()


def test_streak_routes_and_clip_result() -> None:
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
        created = client.post(
            "/v1/sessions",
            headers=AUTH,
            json={"sessionId": "tg-1"},
        )
        assert created.status_code == 201
        clip = client.post(
            "/v1/clips",
            headers=AUTH,
            data={"sessionId": "tg-1"},
            files={"audio": ("voice.ogg", b"voice", "audio/ogg")},
        )
        job_id = clip.json()["jobId"]
        for _ in range(20):
            body = client.get(f"/v1/clips/{job_id}", headers=AUTH).json()
            if body["status"] != "pending":
                break
        assert body["result"]["streak"]["current"] == 1
        assert body["result"]["streak"]["firstToday"] is True
        assert body["result"]["streak"]["firstEver"] is True
        profile = client.get("/internal/streak/tg-1", headers=AUTH)
        assert profile.status_code == 200
        assert profile.json()["current"] == 1
        assert profile.json()["last7"][-1] is True
        metrics = client.get("/internal/metrics", headers=AUTH).json()
        assert metrics["streaks"]["buckets"][1]["bucket"] == "1"
        assert metrics["streaks"]["reminderBuckets"][0]["bucket"] == "0"
