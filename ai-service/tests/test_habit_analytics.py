from datetime import date, datetime
from zoneinfo import ZoneInfo

import pytest
from fakeredis.aioredis import FakeRedis

from app.habit_analytics import HabitAnalytics, calculate
from app.metrics import DEFAULT_RATES, MemoryMetricsStore, RedisMetricsStore
from app.load_test import bind_load_test, reset_load_test


def test_windows_and_exact_day_retention():
    active = {"2026-10-01": {99}, "2026-10-05": {1, 2, 3, 4},
              "2026-10-06": {2, 3, 4}, "2026-10-07": {3, 4}, "2026-10-08": {4},
              "2026-10-12": {1}}
    report = calculate(active, "2026-10-01", date(2026, 10, 5), date(2026, 10, 11), date(2026, 10, 14))
    last = report["days"][-1]
    assert last["wau"] == 4
    assert [r["users"] for r in last["distribution"]] == [1, 1, 1, 1]
    assert last["dau"] == 0
    assert last["ratio"] == 0
    cohort = report["cohorts"][0]
    assert cohort["size"] == 4
    assert cohort["rates"][:3] == [{"day": 1, "eligible": 4, "returned": 3},
                                   {"day": 3, "eligible": 4, "returned": 1},
                                   {"day": 7, "eligible": 4, "returned": 1}]
    assert cohort["rates"][3]["eligible"] == 0


def test_partial_history_empty_and_current_target_are_not_zero_retention():
    report = calculate({"2026-10-10": {1}, "2026-10-11": {2}}, "2026-10-10",
                       date(2026, 10, 10), date(2026, 10, 12), date(2026, 10, 12))
    assert report["days"][0]["dau"] is None
    assert report["days"][-1]["wau"] is None
    assert report["days"][-1]["provisional"]
    assert report["periodRetention"][0] == {"day": 1, "eligible": 1, "returned": 0}
    assert report["cohorts"][1]["partialHistory"]
    empty = calculate({}, "", date(2026, 10, 1), date(2026, 10, 1), date(2026, 10, 2))
    assert empty["days"][0]["dau"] is None


@pytest.mark.asyncio
async def test_redis_normalizes_identity_and_caches_failure_without_false_zero():
    redis = FakeRedis(decode_responses=True)
    await redis.sadd("metrics:dau:2026-10-01", "tg-123", "tg-ChatId(chatId=123)", "app-123")
    service = HabitAnalytics(RedisMetricsStore(redis, DEFAULT_RATES))
    await service.refresh()
    assert service.active == {"2026-10-01": {123}}
    await redis.sadd("metrics:dau:2026-10-01", "tg-456")
    await service.refresh()
    assert service.active == {"2026-10-01": {123}}
    service._attempted = 0
    async def broken(*args, **kwargs):
        raise RuntimeError("source offline")
        yield
    redis.scan_iter = broken
    await service.refresh()
    assert not service.healthy
    assert service.active == {"2026-10-01": {123}}
    assert "speaking_habit_source_healthy 0" in service.prometheus()
    assert "speaking_habit_wau{" not in service.prometheus()


@pytest.mark.asyncio
async def test_moscow_boundary_and_load_exclusion():
    metrics = MemoryMetricsStore(DEFAULT_RATES)
    now = datetime(2026, 10, 10, 23, 59, tzinfo=ZoneInfo("Europe/Moscow")).timestamp()
    await metrics.record_turn("tg-1", 5, 5, now=now)
    await metrics.record_turn("tg-1", 5, 5, now=now)
    await metrics.record_turn("tg-1", 5, 5, now=now + 120)
    token = bind_load_test(True)
    try:
        await metrics.record_turn("tg-2", 5, 5, now=now)
    finally:
        reset_load_test(token)
    service = HabitAnalytics(metrics)
    await service.refresh()
    assert service.active == {"2026-10-10": {1}, "2026-10-11": {1}}
    assert 'chatId' not in service.prometheus()


def test_http_reports_share_cohorts_and_scrape_uses_cache():
    import asyncio
    import time
    from datetime import timedelta
    from fastapi.testclient import TestClient
    from app.dialogue import MemoryDialogueStore
    from app.main import create_app
    from app.metrics_v2 import MemoryMetricsV2
    from app.pipeline import ClipPipeline
    from tests.conftest import FakeLlm, FakeStt, FakeTts, FakeRealtime, FakeReviewer, test_settings

    metrics = MemoryMetricsStore(DEFAULT_RATES)
    today = datetime.now(ZoneInfo("Europe/Moscow")).date()
    for offset in (12, 8, 7, 6, 4, 1):
        when = datetime.combine(today - timedelta(days=offset), datetime.min.time(),
                                ZoneInfo("Europe/Moscow")).timestamp()
        asyncio.run(metrics.record_turn("tg-1", 10, 5, now=when))
    pipeline = ClipPipeline(stt=FakeStt(["hello"]), llm=FakeLlm(), tts=FakeTts(),
                            dialogue=MemoryDialogueStore(max_messages=40, ttl_seconds=86400),
                            metrics=metrics, v2=MemoryMetricsV2())
    app = create_app(settings=test_settings(), pipeline=pipeline, realtime=FakeRealtime(), reviewer=FakeReviewer())
    with TestClient(app, headers={"X-Internal-Token": "test-internal-token"}) as client:
        params = {"from": str(today - timedelta(days=7)), "to": str(today - timedelta(days=1))}
        overview = client.get("/internal/metrics/overview", params=params)
        assert overview.status_code == 200
        habit = overview.json()["habits"]
        streak = client.get("/internal/metrics").json()["streaks"]["habits"]
        assert habit["cohorts"] == streak["cohorts"]
        assert habit["days"] == streak["days"]
        scrape = client.get("/internal/metrics/prometheus").text
        assert "speaking_habit_current_wau 1" in scrape
        assert 'offset="3"' in scrape
        assert habit["healthy"] and time.time() - habit["updatedAt"] < 60
