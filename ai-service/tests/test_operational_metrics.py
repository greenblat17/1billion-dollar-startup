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


def test_provider_calls_count_each_result_and_type():
    metrics = operational_metrics.ProviderCallMetrics()
    metrics.record("stt", "error")
    metrics.record("stt", "success")
    metrics.record("llm", "success")
    metrics.record("tts", "cancelled")
    result = metrics.prometheus()
    assert 'type="stt",result="error"} 1' in result
    assert 'type="stt",result="success"} 1' in result
    assert 'type="llm",result="success"} 1' in result
    assert 'type="tts",result="cancelled"} 1' in result
    assert 'type="llm",result="error"} 0' in result


def test_llm_generation_metrics_count_only_valid_output_generation():
    metrics = operational_metrics.LlmGenerationMetrics()
    metrics.record(120, 2000)
    metrics.record(0, 1000)
    metrics.record(100, 0)
    result = metrics.prometheus()
    assert 'speaking_llm_generation_tokens_total{client="telegram"} 120' in result
    assert 'speaking_llm_generation_seconds_total{client="telegram"} 2.0' in result


def test_voice_cost_keeps_missing_prices_out_of_spend_and_in_coverage():
    usage = operational_metrics.VoiceModelUsage()
    usage.record("stt", "success", None)
    usage.record("llm", "success", 125)
    usage.record("tts", "error", None)
    usage.record("tts", "success", None)
    usage.settle_tts(400)
    metrics = operational_metrics.VoiceModelMetrics()
    metrics.record(usage)
    result = metrics.prometheus()
    assert 'speaking_voice_model_cost_micro_total{component="llm"} 125' in result
    assert 'speaking_voice_model_cost_micro_total{component="tts"} 400' in result
    assert 'speaking_voice_model_calls_total{component="stt",price="missing"} 1' in result
    assert 'speaking_voice_model_calls_total{component="tts",price="known"} 1' in result
    assert 'speaking_voice_model_calls_total{component="tts",price="missing"} 1' in result
