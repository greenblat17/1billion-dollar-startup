"""Measure speech-time fallback and wait on the DEV AI host with real STT.

Run only in an isolated container with the DEV AI environment. This script
does not create sessions or call the dialogue/TTS providers.
"""
from __future__ import annotations

import asyncio
import json
import statistics
import time
from pathlib import Path

from openai import AsyncOpenAI

from app.config import Settings
from app.speech_time import SpeechTimeMeter
from app.stt import GroqSpeechToText


class TimedStt(GroqSpeechToText):
    def __init__(self, client: AsyncOpenAI, settings: Settings) -> None:
        super().__init__(
            client, settings.stt_model, settings.ffmpeg_bin,
            requires_wav=settings.stt_model.startswith("assemblyai/"),
        )
        self.finished_at = 0.0
        self.elapsed_ms = 0

    async def transcribe(self, *args, **kwargs):
        started = time.perf_counter()
        try:
            return await super().transcribe(*args, **kwargs)
        finally:
            self.finished_at = time.perf_counter()
            self.elapsed_ms = round((self.finished_at - started) * 1000)


async def main() -> None:
    settings = Settings.from_env()
    if not settings.groq_api_key:
        raise RuntimeError("DEV STT credentials are unavailable")
    client = AsyncOpenAI(api_key=settings.groq_api_key, base_url=settings.groq_base_url, max_retries=0)
    meter = SpeechTimeMeter(settings.ffmpeg_bin)
    clips = sorted(Path("load/fixtures/librispeech-test-clean").glob("*.ogg"))
    if len(clips) != 10:
        raise RuntimeError(f"Expected ten OGG fixtures, found {len(clips)}")

    async def measure(path: Path) -> dict:
        audio = path.read_bytes()
        timed_stt = TimedStt(client, settings)
        started = time.perf_counter()
        try:
            stt, speech = await meter.transcribe(timed_stt, audio, "audio/ogg", path.name)
            ended = time.perf_counter()
            return {
                "clip": path.name,
                "source": speech.source,
                "reason": speech.fallback_reason,
                "stt_ms": timed_stt.elapsed_ms,
                "vad_ms": speech.vad_ms,
                "post_stt_wait_ms": round((ended - timed_stt.finished_at) * 1000),
                "total_ms": round((ended - started) * 1000),
                "recording_seconds": round(speech.recording_seconds, 2),
                "speech_seconds": round(speech.speech_seconds, 2),
                "stt_has_text": bool(stt.text.strip()),
            }
        except Exception as error:
            return {"clip": path.name, "error": type(error).__name__, "stt_ms": timed_stt.elapsed_ms}

    try:
        # One warm-up checks provider credentials without using a result in the burst.
        warmup = await measure(clips[0])
        if "error" in warmup:
            raise RuntimeError(f"STT warm-up failed: {warmup['error']}")
        results = await asyncio.gather(*(measure(path) for path in clips))
        completed = [row for row in results if "error" not in row and row["stt_has_text"]]
        waits = [row["post_stt_wait_ms"] for row in completed]
        print(json.dumps({
            "stt_model": settings.stt_model,
            "warmup": warmup,
            "samples": len(results),
            "stt_errors": [row for row in results if "error" in row],
            "vad": sum(row["source"] == "vad" for row in completed),
            "fallback_reasons": {
                reason: sum(row["reason"] == reason for row in completed)
                for reason in sorted({row["reason"] for row in completed if row["reason"]})
            },
            "post_stt_wait_ms_max": max(waits, default=None),
            "post_stt_wait_ms_median": round(statistics.median(waits)) if waits else None,
            "results": results,
        }, ensure_ascii=False, indent=2))
        if len(completed) != len(clips):
            raise RuntimeError("Some benchmark clips failed STT or returned no text")
    finally:
        meter.close()
        await client.close()


if __name__ == "__main__":
    asyncio.run(main())
