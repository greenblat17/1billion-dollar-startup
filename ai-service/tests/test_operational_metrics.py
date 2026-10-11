import asyncio

import pytest

from app.metrics_v2 import bind_metrics, reset_metrics
from app import operational_metrics


@pytest.mark.asyncio
async def test_stage_counts_logical_operation_and_skips_other_clients(monkeypatch):
    metrics = operational_metrics.StageMetrics()
    monkeypatch.setattr(operational_metrics, "STAGE_METRICS", metrics)

    @operational_metrics.telegram_stage("stt")
    async def success_with_internal_retry():
        for _ in range(2):
            pass
        return "ok"

    @operational_metrics.telegram_stage("stt")
    async def failed():
        raise RuntimeError("provider failed")

    bound = bind_metrics("tg-1")
    try:
        assert await success_with_internal_retry() == "ok"
        with pytest.raises(RuntimeError):
            await failed()
    finally:
        reset_metrics(bound)

    bound = bind_metrics("app-1", "ios")
    try:
        await success_with_internal_retry()
    finally:
        reset_metrics(bound)

    result = metrics.prometheus()
    assert 'stage="stt",outcome="success"} 1' in result
    assert 'stage="stt",outcome="failure"} 1' in result


@pytest.mark.asyncio
async def test_cancelled_operation_is_not_a_failure(monkeypatch):
    metrics = operational_metrics.StageMetrics()
    monkeypatch.setattr(operational_metrics, "STAGE_METRICS", metrics)

    @operational_metrics.telegram_stage("tts", session_arg=True)
    async def cancelled(self, session):
        raise asyncio.CancelledError

    with pytest.raises(asyncio.CancelledError):
        await cancelled(None, "tg-1")
    assert 'stage="tts",outcome="failure"} 0' in metrics.prometheus()
