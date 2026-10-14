import asyncio
import json
import logging
from types import SimpleNamespace
from unittest.mock import AsyncMock

import httpx
import pytest

from app.llm import OpenAiChatModel
from app.load_test import bind_load_test, reset_load_test
from app.metrics_v2 import bind_metrics, reset_metrics
from app.operational_metrics import ProviderCallMetrics, VoiceModelUsage, bind_voice_usage, reset_voice_usage
from app import provider_call_log
from app.provider_call_log import anonymous_user_id, configure_user_id_hash_key, logged_provider_call
from app.stt import GroqSpeechToText
from app.tts import DeepgramTextToSpeech


def _events(caplog):
    return [
        json.loads(record.getMessage().removeprefix("provider_call "))
        for record in caplog.records
        if record.name == "app.provider_call_log" and record.getMessage().startswith("provider_call ")
    ]


def test_anonymous_id_is_stable_across_sessions_and_formats():
    configure_user_id_hash_key("test-secret")
    try:
        first = bind_metrics("tg-ChatId(chatId=12345)")
        try:
            telegram = anonymous_user_id()
        finally:
            reset_metrics(first)
        second = bind_metrics("tg-12345")
        try:
            assert anonymous_user_id() == telegram
        finally:
            reset_metrics(second)
        first = bind_metrics("app-11111111-1111-1111-1111-111111111111-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "ios")
        try:
            mobile = anonymous_user_id()
        finally:
            reset_metrics(first)
        second = bind_metrics("app-11111111-1111-1111-1111-111111111111-bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", "ios")
        try:
            assert anonymous_user_id() == mobile
        finally:
            reset_metrics(second)
        assert telegram != mobile and "12345" not in telegram
    finally:
        configure_user_id_hash_key("")


@pytest.mark.asyncio
async def test_cancelled_provider_attempt_is_recorded(caplog):
    async def cancelled():
        raise asyncio.CancelledError()

    with caplog.at_level(logging.INFO, logger="app.provider_call_log"):
        with pytest.raises(asyncio.CancelledError):
            await logged_provider_call(cancelled, kind="llm", provider="openrouter", model="test")
    assert _events(caplog)[0]["result"] == "cancelled"


@pytest.mark.asyncio
async def test_llm_log_has_source_anonymous_user_cost_and_no_content(caplog):
    response = SimpleNamespace(
        choices=[SimpleNamespace(finish_reason="stop", message=SimpleNamespace(content='{"reply":"OK"}'))],
        usage=SimpleNamespace(prompt_tokens=10, completion_tokens=2, cost=0.000123),
    )
    client = SimpleNamespace(
        base_url="https://openrouter.ai/api/v1",
        chat=SimpleNamespace(completions=SimpleNamespace(create=AsyncMock(return_value=response))),
    )
    configure_user_id_hash_key("test-secret")
    bound = bind_metrics("tg-ChatId(chatId=12345)")
    try:
        with caplog.at_level(logging.INFO, logger="app.provider_call_log"):
            assert await OpenAiChatModel(client, "test-model").complete_reply([], "private spoken words") == "OK"
    finally:
        reset_metrics(bound)
        configure_user_id_hash_key("")

    events = _events(caplog)
    assert len(events) == 1
    event = events[0]
    assert event["source"] == "telegram"
    assert event["type"] == "llm" and event["purpose"] == "reply"
    assert event["result"] == "success" and event["http_status"] is None
    assert event["cost_micro_usd"] == 123
    assert len(event["user_id"]) == 20
    assert event["timestamp"].endswith("Z") and "T" in event["timestamp"]
    assert "12345" not in caplog.text and "private spoken words" not in caplog.text


@pytest.mark.asyncio
async def test_stt_log_has_model_and_unknown_cost(caplog):
    client = SimpleNamespace(
        base_url="https://openrouter.ai/api/v1",
        audio=SimpleNamespace(transcriptions=SimpleNamespace(create=AsyncMock(return_value={"text": "Hi"}))),
    )
    with caplog.at_level(logging.INFO, logger="app.provider_call_log"):
        result = await GroqSpeechToText(client, "assemblyai/universal-3-5-pro", "ffmpeg").transcribe(
            b"audio", "audio/ogg", "clip.ogg",
        )
    assert result.text == "Hi"
    assert _events(caplog)[0]["type"] == "stt"
    assert _events(caplog)[0]["model"] == "assemblyai/universal-3-5-pro"
    assert _events(caplog)[0]["cost_micro_usd"] is None


@pytest.mark.asyncio
async def test_tts_retry_logs_and_counts_both_http_attempts(caplog, monkeypatch):
    attempts = 0
    metrics = ProviderCallMetrics()
    monkeypatch.setattr(provider_call_log, "PROVIDER_CALL_METRICS", metrics)

    def respond(_request):
        nonlocal attempts
        attempts += 1
        if attempts == 1:
            return httpx.Response(502, json={"error": "provider unavailable"})
        return httpx.Response(200, headers={"Content-Type": "audio/mpeg"}, content=b"ID3audio")

    async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
        with caplog.at_level(logging.INFO, logger="app.provider_call_log"):
            await DeepgramTextToSpeech("test-key", client=client).synthesize("private text")

    events = _events(caplog)
    assert [(event["type"], event["result"], event["http_status"]) for event in events] == [
        ("tts", "error", 502), ("tts", "success", 200),
    ]
    assert events[0]["error_code"] == "HTTPStatusError"
    assert "private text" not in caplog.text and "test-key" not in caplog.text
    assert 'type="tts",result="error"} 1' in metrics.prometheus()
    assert 'type="tts",result="success"} 1' in metrics.prometheus()


@pytest.mark.asyncio
async def test_load_test_call_is_logged_but_not_counted(caplog, monkeypatch):
    metrics = ProviderCallMetrics()
    monkeypatch.setattr(provider_call_log, "PROVIDER_CALL_METRICS", metrics)
    bound = bind_load_test(True)
    try:
        with caplog.at_level(logging.INFO, logger="app.provider_call_log"):
            await logged_provider_call(
                lambda: asyncio.sleep(0, result="ok"),
                kind="llm", provider="openrouter", model="test",
            )
    finally:
        reset_load_test(bound)
    assert _events(caplog)[0]["source"] == "load_test"
    assert 'type="llm",result="success"} 0' in metrics.prometheus()


@pytest.mark.asyncio
async def test_provider_attempt_is_attributed_only_to_bound_voice_job():
    usage = VoiceModelUsage()
    token = bind_voice_usage(usage)
    try:
        await logged_provider_call(
            lambda: asyncio.sleep(0, result="ok"), kind="llm", provider="openrouter", model="test",
            cost_of=lambda _response: 73,
        )
        await logged_provider_call(
            lambda: asyncio.sleep(0, result="audio"), kind="tts", provider="openrouter", model="test",
        )
        usage.settle_tts(None)
    finally:
        reset_voice_usage(token)
    await logged_provider_call(
        lambda: asyncio.sleep(0, result="outside"), kind="llm", provider="openrouter", model="test",
    )
    assert usage.attempts == {"stt": 0, "llm": 1, "tts": 1}
    assert usage.priced == {"stt": 0, "llm": 1, "tts": 0}
    assert usage.cost_micro["llm"] == 73
