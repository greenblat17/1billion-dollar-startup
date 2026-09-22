from __future__ import annotations

import logging

import httpx
import pytest

from app.realtime import OpenAiRealtimeGateway, sdp_for_openai

API_KEY = "sk-test-do-not-log"
EPHEMERAL = "ek_test-do-not-log"


def _gateway(handler) -> OpenAiRealtimeGateway:
    return OpenAiRealtimeGateway(
        API_KEY,
        base_url="https://api.openai.com/v1",
        transport=httpx.MockTransport(handler),
    )


@pytest.mark.asyncio
async def test_start_call_returns_sdp_answer() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/realtime/client_secrets"):
            return httpx.Response(200, json={"value": EPHEMERAL})
        return httpx.Response(
            201,
            text="v=0 answer",
            headers={"Location": "/v1/realtime/calls/rtc_1"},
        )

    answer, call_id = await _gateway(handler).start_call("v=0 offer", "Travel", "marin")
    assert answer == "v=0 answer"
    assert call_id == "rtc_1"


@pytest.mark.asyncio
async def test_calls_error_logs_body_without_secrets(caplog: pytest.LogCaptureFixture) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/realtime/client_secrets"):
            return httpx.Response(200, json={"value": EPHEMERAL})
        return httpx.Response(
            400,
            text=f'{{"error":{{"message":"invalid sdp leaked {API_KEY} {EPHEMERAL}"}}}}',
        )

    caplog.set_level(logging.WARNING, logger="app.realtime")
    with pytest.raises(httpx.HTTPStatusError):
        await _gateway(handler).start_call("v=0 offer", "Travel", "marin")
    text = caplog.text
    assert "openai realtime HTTP 400 /v1/realtime/calls" in text
    assert "sdp_bytes=11" in text
    assert "invalid sdp leaked" in text
    assert API_KEY not in text
    assert EPHEMERAL not in text
    assert "[redacted]" in text


@pytest.mark.asyncio
async def test_mint_error_logs_body_without_key(caplog: pytest.LogCaptureFixture) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(401, text=f'{{"error":"bad key {API_KEY}"}}')

    caplog.set_level(logging.WARNING, logger="app.realtime")
    with pytest.raises(httpx.HTTPStatusError):
        await _gateway(handler).start_call("v=0 offer", "Work", "cedar")
    text = caplog.text
    assert "openai realtime HTTP 401 /v1/realtime/client_secrets" in text
    assert "sdp_bytes" not in text
    assert API_KEY not in text
    assert "[redacted]" in text


def test_sdp_for_openai_keeps_or_adds_crlf() -> None:
    assert sdp_for_openai("v=0") == "v=0\r\n"
    assert sdp_for_openai("v=0\n") == "v=0\r\n"
    assert sdp_for_openai("v=0\r\n") == "v=0\r\n"
    assert sdp_for_openai("v=0\r\n\r\n") == "v=0\r\n\r\n"


@pytest.mark.asyncio
async def test_start_call_posts_sdp_with_trailing_crlf() -> None:
    posted: list[bytes] = []

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/realtime/client_secrets"):
            return httpx.Response(200, json={"value": EPHEMERAL})
        posted.append(request.content)
        return httpx.Response(
            201,
            text="v=0 answer",
            headers={"Location": "/v1/realtime/calls/rtc_1"},
        )

    await _gateway(handler).start_call("v=0 offer", "Travel", "marin")
    assert posted == [b"v=0 offer\r\n"]
