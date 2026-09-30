from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Any, Protocol

from openai import AsyncOpenAI, BadRequestError

from app.audio import to_wav_mono_16k
from app.retry import once_on_retryable


@dataclass
class SttResult:
    text: str
    raw: dict[str, Any] = field(default_factory=dict)
    no_speech: bool = False
    duration_seconds: float = 0.0
    words: list[dict[str, Any]] = field(default_factory=list)


class SpeechToText(Protocol):
    async def transcribe(self, audio: bytes, content_type: str, filename: str, language: str | None = "en") -> SttResult:
        ...


class GroqSpeechToText:
    def __init__(self, client: AsyncOpenAI, model: str, ffmpeg_bin: str) -> None:
        self._client = client
        self._model = model
        self._ffmpeg_bin = ffmpeg_bin

    async def transcribe(self, audio: bytes, content_type: str, filename: str, language: str | None = "en") -> SttResult:
        try:
            return await self._transcribe_file(audio, filename, content_type, language)
        except BadRequestError:
            wav = await to_wav_mono_16k(self._ffmpeg_bin, audio, suffix=_suffix(filename))
            return await self._transcribe_file(wav, "voice.wav", "audio/wav", language)

    async def _transcribe_file(self, audio: bytes, filename: str, content_type: str, language: str | None) -> SttResult:
        async def call() -> Any:
            return await self._client.audio.transcriptions.create(
                model=self._model,
                file=(filename, audio, content_type),
                **({"language": language} if language else {}),
                response_format="verbose_json",
                timestamp_granularities=["word"],
            )

        response = await once_on_retryable(call)
        payload = _as_dict(response)
        text = str(payload.get("text") or "").strip()
        no_speech_prob = _no_speech_prob(payload)
        return SttResult(
            text=text,
            raw=payload,
            no_speech=not text or no_speech_prob >= 0.8,
            duration_seconds=duration_seconds(payload),
            words=speech_words(payload),
        )


def _suffix(filename: str) -> str:
    if "." in filename:
        return "." + filename.rsplit(".", 1)[-1]
    return ".ogg"


def _as_dict(response: Any) -> dict[str, Any]:
    if isinstance(response, dict):
        return response
    dump = getattr(response, "model_dump", None)
    if callable(dump):
        return dump()
    return {"text": getattr(response, "text", "")}


def speech_words(payload: dict[str, Any]) -> list[dict[str, Any]]:
    """Compact word timings already present on a verbose transcription."""
    raw = payload.get("words") or []
    if not isinstance(raw, list):
        return []
    words: list[dict[str, Any]] = []
    for item in raw:
        if not isinstance(item, dict):
            continue
        text = str(item.get("word") or item.get("text") or "").strip()
        start = _timestamp(item.get("start"))
        end = _timestamp(item.get("end"))
        if not text or start is None or end is None or end < start:
            continue
        words.append({"w": text, "s": start, "e": end})
    return words


def _timestamp(value: Any) -> float | None:
    if isinstance(value, bool) or value is None:
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    if not math.isfinite(number):
        return None
    return number


def duration_seconds(payload: dict[str, Any]) -> float:
    raw = payload.get("duration")
    if isinstance(raw, bool) or raw is None:
        return 0.0
    try:
        value = float(raw)
    except (TypeError, ValueError):
        return 0.0
    if value <= 0:
        return 0.0
    return value


def _no_speech_prob(payload: dict[str, Any]) -> float:
    segments = payload.get("segments") or []
    if not segments:
        return float(payload.get("no_speech_prob") or 0)
    values = [float(segment.get("no_speech_prob") or 0) for segment in segments if isinstance(segment, dict)]
    return max(values) if values else 0.0
