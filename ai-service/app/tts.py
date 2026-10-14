from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from typing import Any, Protocol

import httpx
from openai import APIStatusError, AsyncOpenAI

from app.audio import to_ogg_opus
from app.metrics_v2 import cost_micro, current_session
from app.load_test import load_test_active
from app.retry import once_on_retryable
from app.provider_call_log import anonymous_user_id, logged_provider_call
from app.operational_metrics import current_voice_usage

logger = logging.getLogger(__name__)

TTS_INSTRUCTIONS = (
    "Warm conversational speaking partner. Natural pace, not a textbook narrator, not overly cheerful."
)


@dataclass(frozen=True)
class TtsAudio:
    data: bytes
    content_type: str


class TextToSpeech(Protocol):
    async def synthesize(self, text: str, speed: float | None = None) -> TtsAudio:
        ...


class OpenAiTextToSpeech:
    def __init__(
        self,
        client: AsyncOpenAI,
        model: str,
        voice: str,
        response_format: str,
        ffmpeg_bin: str,
    ) -> None:
        self._client = client
        self._model = model
        self._voice = voice
        self._response_format = response_format
        self._ffmpeg_bin = ffmpeg_bin
        self._v2 = None

    async def synthesize(self, text: str, speed: float | None = None) -> TtsAudio:
        kwargs: dict[str, Any] = {
            "model": self._model,
            "voice": self._voice,
            "input": text,
            "response_format": self._response_format,
        }
        if self._model.startswith("openai/"):
            kwargs["instructions"] = TTS_INSTRUCTIONS

        async def call() -> tuple[Any, dict[str, str]]:
            started = time.perf_counter()
            try:
                raw_create = getattr(getattr(self._client.audio.speech, "with_raw_response", None), "create", None)
                if raw_create is None:
                    return await self._client.audio.speech.create(**kwargs), {}
                raw = await raw_create(**kwargs)
                headers = {str(key).lower(): value for key, value in dict(raw.headers).items()}
                parsed = raw.parse()
                if hasattr(parsed, "__await__"):
                    parsed = await parsed
                return parsed, headers
            except APIStatusError as error:
                response_headers = error.response.headers
                logger.warning(
                    "TTS HTTP error user_id=%s model=%s voice=%s format=%s input_chars=%d "
                    "status=%d elapsed_ms=%.0f cf_ray=%s generation_id=%s request_id=%s",
                    anonymous_user_id(), self._model, self._voice, self._response_format, len(text),
                    error.status_code, (time.perf_counter() - started) * 1000,
                    response_headers.get("cf-ray"), response_headers.get("x-generation-id"),
                    response_headers.get("x-request-id"),
                )
                raise

        try:
            provider = "openrouter" if "openrouter.ai" in str(getattr(self._client, "base_url", "")) else "openai"
            provider_started = time.perf_counter()
            response, headers = await once_on_retryable(
                lambda: logged_provider_call(call, kind="tts", provider=provider, model=self._model),
                metric_service="tts", metric_provider=provider,
            )
        except Exception:
            if self._v2 is not None:
                await self._v2.record_error("", "tts", "failed")
            raise
        payload = await _audio_bytes(response)
        provider_ms = int((time.perf_counter() - provider_started) * 1000)
        if self._v2 is not None:
            cost_started = time.perf_counter()
            cost = await _openrouter_cost(self._client, headers)
            cost_ms = int((time.perf_counter() - cost_started) * 1000)
            voice_usage = current_voice_usage()
            if voice_usage is not None:
                voice_usage.settle_tts(cost)
            await self._v2.record_tts("", self._model, len(text), cost)
            if load_test_active():
                logger.info(
                    "TTS load timing session=%s provider_ms=%d cost_lookup_ms=%d",
                    current_session(), provider_ms, cost_ms,
                )
        if self._response_format == "mp3":
            return TtsAudio(payload, "audio/mpeg")
        suffix = ".opus" if self._response_format == "opus" else f".{self._response_format}"
        return TtsAudio(await to_ogg_opus(self._ffmpeg_bin, payload, suffix=suffix), "audio/ogg")


class DeepgramTextToSpeech:
    def __init__(
        self,
        api_key: str,
        model: str = "aura-2-thalia-en",
        client: httpx.AsyncClient | None = None,
        output_format: str = "mp3",
        speed: float = 0.9,
        ffmpeg_bin: str = "ffmpeg",
    ) -> None:
        self._api_key = api_key
        self._model = model
        self._client = client or httpx.AsyncClient(timeout=10.0)
        self._owns_client = client is None
        self._output_format = output_format
        self._speed = speed
        self._ffmpeg_bin = ffmpeg_bin

    async def synthesize(self, text: str, speed: float | None = None) -> TtsAudio:
        response = await once_on_retryable(
            lambda: logged_provider_call(
                lambda: self._request(text, speed), kind="tts", provider="deepgram", model=self._model,
            ), metric_service="tts", metric_provider="deepgram",
        )
        if response.headers.get("content-type", "").split(";", 1)[0].strip().lower() != "audio/mpeg":
            raise RuntimeError("deepgram tts returned an unsupported audio type")
        if not response.content:
            raise RuntimeError("deepgram tts returned empty audio")
        if self._output_format == "ogg":
            return TtsAudio(await to_ogg_opus(self._ffmpeg_bin, response.content, suffix=".mp3"), "audio/ogg")
        return TtsAudio(response.content, "audio/mpeg")

    async def _request(self, text: str, speed: float | None) -> httpx.Response:
        response = await self._client.post(
            "https://api.deepgram.com/v1/speak",
            params={"model": self._model, "encoding": "mp3", "speed": self._speed if speed is None else speed},
            headers={"Authorization": f"Token {self._api_key}"},
            json={"text": text},
        )
        response.raise_for_status()
        return response

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()


async def _audio_bytes(response: Any) -> bytes:
    if isinstance(response, (bytes, bytearray)):
        return bytes(response)
    read = getattr(response, "aread", None)
    if callable(read):
        return bytes(await read())
    content = getattr(response, "content", None)
    if callable(content):
        return bytes(content())
    if isinstance(content, (bytes, bytearray)):
        return bytes(content)
    raise RuntimeError("tts response had no audio bytes")


async def _openrouter_cost(client: AsyncOpenAI, headers: dict[str, str]) -> int | None:
    base = str(client.base_url)
    generation_id = headers.get("generation-id") or headers.get("x-generation-id")
    if "openrouter.ai" not in base or not generation_id:
        return None
    token = client.api_key
    url = base.rstrip("/") + "/generation?id=" + generation_id
    try:
        async with httpx.AsyncClient(timeout=5.0) as http:
            response = await http.get(url, headers={"Authorization": f"Bearer {token}"})
        if response.status_code != 200:
            return None
        return cost_micro((response.json().get("data") or {}).get("total_cost"))
    except (httpx.HTTPError, ValueError):
        return None
