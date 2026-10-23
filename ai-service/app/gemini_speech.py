"""OpenRouter Gemini TTS for Kora's exact Sonnet-authored speech."""

from __future__ import annotations

import asyncio

from openai import AsyncOpenAI

from app.provider_call_log import logged_provider_call
from app.retry import once_on_retryable
from app.speech_delivery import DELIVERY_TAGS
from app.tts import TtsAudio


class GeminiKoraSpeech:
    def __init__(self, client: AsyncOpenAI, ffmpeg_bin: str = "ffmpeg",
                 model: str = "google/gemini-3.1-flash-tts-preview", voice: str = "Kore",
                 timeout: float = 40) -> None:
        self.client = client
        self.ffmpeg_bin = ffmpeg_bin
        self.model = model
        self.voice = voice
        self.timeout = timeout

    async def synthesize(self, text: str, *, speed: float = 1.0,
                         delivery: str | None = None) -> TtsAudio:
        if isinstance(speed, bool) or speed not in (0.8, 0.9, 1.0):
            raise ValueError("unsupported speech speed")
        if not text.strip():
            raise ValueError("empty speech text")
        if delivery is not None and delivery not in DELIVERY_TAGS:
            raise ValueError("unsupported speech delivery")
        spoken_input = DELIVERY_TAGS.get(delivery, "") + text
        async with asyncio.timeout(self.timeout):
            response = await once_on_retryable(
                lambda: logged_provider_call(
                    lambda: self.client.audio.speech.create(
                        model=self.model, voice=self.voice, input=spoken_input, response_format="pcm",
                    ),
                    kind="tts", provider="openrouter", model=self.model,
                ),
                metric_service="tts", metric_provider="openrouter",
            )
            pcm = await response.aread()
            if not pcm or len(pcm) > 24 * 1024 * 1024 or len(pcm) % 2:
                raise ValueError("gemini_invalid_pcm")
            ogg = await self._ffmpeg(pcm, speed)
            if not ogg.startswith(b"OggS"):
                raise ValueError("gemini_invalid_ogg")
            return TtsAudio(ogg, "audio/ogg")

    async def _ffmpeg(self, pcm: bytes, speed: float) -> bytes:
        process = await asyncio.create_subprocess_exec(
            self.ffmpeg_bin, "-v", "error",
            "-f", "s16le", "-ar", "24000", "-ac", "1", "-i", "pipe:0",
            *([] if speed == 1.0 else ["-af", f"atempo={speed}"]),
            "-c:a", "libopus", "-b:a", "32k", "-application", "voip",
            "-f", "ogg", "pipe:1",
            stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        try:
            audio, _ = await process.communicate(pcm)
            if process.returncode != 0 or not audio:
                raise ValueError("gemini_audio_conversion_failed")
            return audio
        finally:
            if process.returncode is None:
                process.kill()
                await process.wait()
