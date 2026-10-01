from dataclasses import replace
import json

import httpx
import pytest

from app.main import _build_pipeline
from app.config import Settings
from app import tts as tts_module
from app.tts import DeepgramTextToSpeech, OpenAiTextToSpeech, TtsAudio
from tests.conftest import test_settings as make_settings


@pytest.mark.asyncio
async def test_deepgram_sends_text_and_returns_mp3() -> None:
    requests = []

    def respond(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(200, headers={"Content-Type": "audio/mpeg"}, content=b"ID3audio")

    async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
        tts = DeepgramTextToSpeech("test-key", client=client)
        assert await tts.synthesize("Hello") == TtsAudio(b"ID3audio", "audio/mpeg")

    assert len(requests) == 1
    assert requests[0].method == "POST"
    assert str(requests[0].url) == "https://api.deepgram.com/v1/speak?model=aura-2-thalia-en&encoding=mp3&speed=0.9"
    assert requests[0].headers["authorization"] == "Token test-key"
    assert requests[0].headers["content-type"] == "application/json"
    assert json.loads(requests[0].content) == {"text": "Hello"}


@pytest.mark.asyncio
async def test_deepgram_can_keep_ogg_contract_during_first_rollout(monkeypatch) -> None:
    async def convert(_ffmpeg: str, payload: bytes, suffix: str) -> bytes:
        assert payload == b"ID3audio"
        assert suffix == ".mp3"
        return b"OggSconverted"

    monkeypatch.setattr(tts_module, "to_ogg_opus", convert)
    async with httpx.AsyncClient(transport=httpx.MockTransport(lambda _: httpx.Response(200, headers={"Content-Type": "audio/mpeg"}, content=b"ID3audio"))) as client:
        tts = DeepgramTextToSpeech("test-key", client=client, output_format="ogg")
        assert await tts.synthesize("Hello") == TtsAudio(b"OggSconverted", "audio/ogg")


@pytest.mark.asyncio
async def test_deepgram_retries_server_error_once() -> None:
    calls = 0

    def respond(_request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(503 if calls == 1 else 200, headers={"Content-Type": "audio/mpeg"}, content=b"ID3audio")

    async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
        audio = await DeepgramTextToSpeech("test-key", client=client).synthesize("Hello")
    assert audio.content_type == "audio/mpeg"
    assert calls == 2


@pytest.mark.asyncio
async def test_deepgram_does_not_retry_auth_error_or_timeout() -> None:
    calls = 0

    def unauthorized(_request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(401)

    async with httpx.AsyncClient(transport=httpx.MockTransport(unauthorized)) as client:
        with pytest.raises(httpx.HTTPStatusError):
            await DeepgramTextToSpeech("test-key", client=client).synthesize("Hello")
    assert calls == 1

    def timeout(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("timeout", request=request)

    async with httpx.AsyncClient(transport=httpx.MockTransport(timeout)) as client:
        with pytest.raises(httpx.ReadTimeout):
            await DeepgramTextToSpeech("test-key", client=client).synthesize("Hello")


@pytest.mark.asyncio
async def test_deepgram_rejects_non_mp3_response() -> None:
    async with httpx.AsyncClient(transport=httpx.MockTransport(
        lambda _: httpx.Response(200, headers={"Content-Type": "audio/wav"}, content=b"RIFFaudio"),
    )) as client:
        with pytest.raises(RuntimeError, match="unsupported audio type"):
            await DeepgramTextToSpeech("test-key", client=client).synthesize("Hello")


@pytest.mark.asyncio
async def test_provider_selection_requires_key_and_supports_rollback() -> None:
    settings = replace(make_settings(), groq_api_key="test", openai_api_key="test")
    with pytest.raises(RuntimeError, match="DEEPGRAM_API_KEY"):
        _build_pipeline(replace(settings, tts_provider="deepgram"))
    direct = _build_pipeline(replace(settings, tts_provider="deepgram", deepgram_api_key="test"))
    assert isinstance(direct.tts, DeepgramTextToSpeech)
    assert direct.tts._speed == 0.9
    await direct.tts.aclose()
    with pytest.raises(RuntimeError, match="TTS_SPEED"):
        _build_pipeline(replace(settings, tts_provider="deepgram", deepgram_api_key="test", tts_speed=0.93))
    router = _build_pipeline(replace(settings, tts_provider="openrouter"))
    assert isinstance(router.tts, OpenAiTextToSpeech)


def test_tts_speed_can_be_set_from_env(monkeypatch) -> None:
    monkeypatch.setenv("TTS_SPEED", "0.95")
    assert Settings.from_env().tts_speed == 0.95
