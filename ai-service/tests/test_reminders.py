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
from app.reminders import (
    MemoryReminderLedger,
    RedisReminderLedger,
    ReminderReport,
    ReminderResult,
    parse_report,
)
from tests.conftest import FakeLlm, FakeRealtime, FakeReviewer, FakeStt, FakeTts
from tests.conftest import test_settings as make_settings

AUTH = {"X-Internal-Token": "test-internal-token"}
HOUR = 3600


def _moscow(day: int, hour: int = 19) -> float:
    return datetime(2026, 9, day, hour, tzinfo=ZoneInfo("Europe/Moscow")).timestamp()


def _report(*results: tuple[str, str, str], mode: str = "auto", claimed: int | None = None) -> ReminderReport:
    return ReminderReport(
        mode=mode,
        started_at="2026-09-24T19:00:00+03:00",
        finished_at="2026-09-24T19:00:05+03:00",
        claimed=len(results) if claimed is None else claimed,
        results=[ReminderResult(session, template, status) for session, template, status in results],
    )


class _Pairs:
    def __init__(self) -> None:
        self.redis = FakeAsyncRedis(decode_responses=True)
        memory = MemoryMetricsStore(MetricRates())
        redis_metrics = RedisMetricsStore(self.redis, MetricRates())
        self.pairs = [
            (memory, MemoryReminderLedger(memory)),
            (redis_metrics, RedisReminderLedger(self.redis, redis_metrics)),
        ]

    async def aclose(self) -> None:
        await self.redis.aclose()


@pytest.mark.asyncio
async def test_report_counts_days_templates_segments_and_runs() -> None:
    opened = _Pairs()
    moment = _moscow(24)
    try:
        for metrics, ledger in opened.pairs:
            await metrics.record_start("tg-1", None, now=moment - 86400)
            await metrics.record_voice("tg-1", now=moment - 86400)
            await metrics.record_start("tg-2", None, now=moment - 86400)
            await ledger.record_report(
                _report(
                    ("tg-1", "day_went", "sent"),
                    ("tg-2", "day_went", "sent"),
                    ("tg-3", "weekend_plan", "blocked"),
                    ("tg-4", "weekend_plan", "failed"),
                    claimed=5,
                ),
                now=moment,
            )
            snap = await ledger.snapshot(now=moment)
            assert snap["today"] == {"sent": 2, "blocked": 1, "failed": 1, "returned": 0}
            assert snap["days"][0]["day"] == "2026-09-24"
            assert len(snap["days"]) == 14
            assert {row["segment"]: row["sent"] for row in snap["segments"]} == {"new": 1, "active": 1}
            templates = {row["templateId"]: row for row in snap["templates"]}
            assert templates["day_went"]["sent"] == 2
            assert templates["weekend_plan"]["blocked"] == 1
            assert snap["runs"][0]["claimed"] == 5
            assert snap["runs"][0]["sent"] == 2
            assert snap["autoToday"]["failed"] == 1
            marks = await ledger.chat_marks(["tg-1", "tg-3"])
            assert marks["tg-1"]["lastReminderAt"] == "2026-09-24T19:00:00+03:00"
            assert marks["tg-3"]["lastReminderAt"] is None
    finally:
        await opened.aclose()


@pytest.mark.asyncio
async def test_reply_within_a_day_counts_once_and_late_reply_does_not() -> None:
    opened = _Pairs()
    day1 = _moscow(24)
    try:
        for _metrics, ledger in opened.pairs:
            await ledger.record_report(
                _report(("tg-1", "day_went", "sent"), ("tg-2", "day_went", "sent")),
                now=day1,
            )
            await ledger.record_reply("tg-1", now=day1 + 2 * HOUR)
            await ledger.record_reply("tg-1", now=day1 + 3 * HOUR)
            await ledger.record_reply("tg-2", now=day1 + 25 * HOUR)
            await ledger.record_reply("tg-9", now=day1 + HOUR)
            snap = await ledger.snapshot(now=day1 + 25 * HOUR)
            first_day = next(row for row in snap["days"] if row["day"] == "2026-09-24")
            assert first_day["returned"] == 1
            assert snap["templates"][0]["returned"] == 1
            assert snap["replyMedianSeconds"] == 2 * HOUR
            assert {row["segment"]: row["returned"] for row in snap["segments"]} == {"new": 1, "active": 0}
    finally:
        await opened.aclose()


@pytest.mark.asyncio
async def test_ignored_streak_grows_and_resets_on_reply() -> None:
    opened = _Pairs()
    try:
        for _metrics, ledger in opened.pairs:
            for day in (22, 23, 24):
                await ledger.record_report(_report(("tg-1", "day_went", "sent")), now=_moscow(day))
            assert (await ledger.chat_marks(["tg-1"]))["tg-1"]["ignored"] == 2
            await ledger.record_reply("tg-1", now=_moscow(24) + 30 * HOUR)
            assert (await ledger.chat_marks(["tg-1"]))["tg-1"]["ignored"] == 0
            await ledger.record_report(_report(("tg-1", "day_went", "sent")), now=_moscow(26))
            assert (await ledger.chat_marks(["tg-1"]))["tg-1"]["ignored"] == 0
    finally:
        await opened.aclose()


@pytest.mark.asyncio
async def test_forecast_drops_after_claim() -> None:
    opened = _Pairs()
    moment = _moscow(24, 12)
    try:
        for metrics, _ledger in opened.pairs:
            await metrics.record_start("tg-1", None, now=moment)
            await metrics.record_start("tg-2", None, now=moment)
            await metrics.record_turn("tg-2", 1, 1, now=moment)
            assert await metrics.reminder_forecast(now=moment) == 1
            await metrics.claim_reminders(now=moment)
            assert await metrics.reminder_forecast(now=moment) == 0
    finally:
        await opened.aclose()


def test_parse_report_normalizes_bad_values() -> None:
    report = parse_report(
        {
            "mode": "weird",
            "claimed": -3,
            "results": [
                {"sessionId": "tg-1", "templateId": "Bad Id!", "status": "exploded"},
                {"sessionId": " ", "templateId": "day_went", "status": "sent"},
                "junk",
            ],
        },
    )
    assert report.mode == "auto"
    assert report.claimed == 0
    assert report.results == [ReminderResult("tg-1", "unknown", "failed")]


@pytest.mark.asyncio
async def test_report_and_metrics_routes() -> None:
    store = MemoryMetricsStore(MetricRates())
    await store.record_start("tg-5", None)
    await store.record_turn("tg-5", 1, 1, now=1_000_000.0)
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
    body = {
        "mode": "manual",
        "startedAt": "2026-09-24T15:00:00+03:00",
        "finishedAt": "2026-09-24T15:00:01+03:00",
        "claimed": 1,
        "results": [{"sessionId": "tg-5", "templateId": "day_went", "status": "sent"}],
    }
    with TestClient(app) as client:
        assert client.post("/internal/reminders/report", json=body).status_code == 401
        assert client.post("/internal/reminders/report", headers=AUTH, json=body).status_code == 200
        assert client.post("/internal/funnel/voice", headers=AUTH, json={"sessionId": "tg-5"}).status_code == 200
        response = client.get("/internal/metrics", headers=AUTH)
    assert response.status_code == 200
    payload = response.json()
    reminders = payload["reminders"]
    assert reminders["today"]["sent"] == 1
    assert reminders["today"]["returned"] == 1
    assert reminders["runs"][0]["mode"] == "manual"
    assert reminders["forecast"] == 1
    chat = payload["chats"][0]
    assert chat["sessionId"] == "tg-5"
    assert chat["lastReminderAt"] is not None
    assert chat["reminderIgnored"] == 0
