"""Count voiced audio without changing the STT provider's billed duration."""
from __future__ import annotations

import asyncio
import io
import logging
import math
import subprocess
import threading
import time
import wave
from concurrent.futures import Future, ThreadPoolExecutor
from dataclasses import dataclass
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

from redis.asyncio import Redis

from app.audio import to_wav_mono_16k
from app.stt import GroqSpeechToText, SpeechToText, SttResult

logger = logging.getLogger(__name__)
_MOSCOW = ZoneInfo("Europe/Moscow")
_ROLLOUT_KEY = "speech-time:activation-day:v1"
_SAMPLE_RATE = 16_000
_FRAME_MS = 20
_FRAME_BYTES = _SAMPLE_RATE * _FRAME_MS // 1000 * 2


@dataclass(frozen=True)
class SpeechTime:
    recording_seconds: float
    speech_seconds: float
    source: str
    fallback_reason: str | None = None
    vad_ms: int = 0


def recording_time(seconds: float, *, source: str = "recording_legacy") -> SpeechTime:
    safe = seconds if math.isfinite(seconds) and seconds > 0 else 0.0
    return SpeechTime(safe, safe, source)


def voiced_seconds(pcm: bytes) -> float:
    """Merge adjacent voiced frames and include only a small boundary pad."""
    import webrtcvad

    detector = webrtcvad.Vad(1)
    flags = []
    for offset in range(0, len(pcm), _FRAME_BYTES):
        frame = pcm[offset:offset + _FRAME_BYTES]
        if len(frame) < _FRAME_BYTES:
            frame = frame.ljust(_FRAME_BYTES, b"\x00")
        flags.append(detector.is_speech(frame, _SAMPLE_RATE))
    intervals: list[list[int]] = []
    start: int | None = None
    for index, voiced in enumerate(flags + [False]):
        if voiced and start is None:
            start = index
        elif not voiced and start is not None:
            if index - start >= 5:  # Reject bursts shorter than 100 ms.
                left, right = max(0, start - 3), min(len(flags), index + 3)
                if intervals and left - intervals[-1][1] <= 5:
                    intervals[-1][1] = right
                else:
                    intervals.append([left, right])
            start = None
    duration = len(pcm) / (_SAMPLE_RATE * 2)
    return round(sum(min(end * _FRAME_MS / 1000, duration) - start * _FRAME_MS / 1000
                     for start, end in intervals), 3)


def _pcm16k(ffmpeg_bin: str, audio: bytes, filename: str) -> bytes:
    if filename.lower().endswith(".wav") and audio[:4] == b"RIFF":
        with wave.open(io.BytesIO(audio), "rb") as wav:
            if wav.getnchannels() == 1 and wav.getframerate() == _SAMPLE_RATE and wav.getsampwidth() == 2:
                return wav.readframes(wav.getnframes())
    result = subprocess.run(
        [ffmpeg_bin, "-nostdin", "-loglevel", "error", "-i", "pipe:0", "-ac", "1",
         "-ar", str(_SAMPLE_RATE), "-f", "s16le", "pipe:1"],
        input=audio, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30, check=False,
    )
    if result.returncode != 0 or not result.stdout:
        raise ValueError("audio_decode_failed")
    return result.stdout


class SpeechTimeMeter:
    def __init__(self, ffmpeg_bin: str, *, workers: int = 4, queued: int = 16,
                 extra_wait_seconds: float = 0.5) -> None:
        self.ffmpeg_bin = ffmpeg_bin
        self.extra_wait_seconds = extra_wait_seconds
        # Admit a short burst while keeping the executor queue bounded.
        self._capacity = threading.BoundedSemaphore(workers + queued)
        self._pool = ThreadPoolExecutor(max_workers=workers, thread_name_prefix="speech-vad")

    def _start(self, audio: bytes, filename: str) -> Future[tuple[float, int]] | None:
        if not self._capacity.acquire(blocking=False):
            return None
        started = time.perf_counter()

        def work() -> tuple[float, int]:
            pcm = _pcm16k(self.ffmpeg_bin, audio, filename)
            return voiced_seconds(pcm), round((time.perf_counter() - started) * 1000)

        try:
            future = self._pool.submit(work)
        except Exception:
            self._capacity.release()
            raise
        future.add_done_callback(lambda _: self._capacity.release())
        return future

    async def transcribe(
        self, stt: SpeechToText, audio: bytes, content_type: str, filename: str,
        *, language: str | None = "en", method: str = "vad", telegram_seconds: float = 0.0,
    ) -> tuple[SttResult, SpeechTime]:
        if method != "vad":
            result = await stt.transcribe(audio, content_type, filename, language=language)
            return result, recording_time(result.duration_seconds or telegram_seconds)

        # AssemblyAI already needs 16 kHz WAV. Decode once, then run VAD beside its provider call.
        if isinstance(stt, GroqSpeechToText) and stt._requires_wav and audio[:4] != b"RIFF":
            wav = await to_wav_mono_16k(stt._ffmpeg_bin, audio, suffix="." + filename.rsplit(".", 1)[-1])
            stt_audio, stt_type, stt_name = wav, "audio/wav", "voice.wav"
        else:
            stt_audio, stt_type, stt_name = audio, content_type, filename
        try:
            future = self._start(stt_audio, stt_name)
        except Exception:
            future = None
        try:
            result = await stt.transcribe(stt_audio, stt_type, stt_name, language=language)
        except BaseException:
            if future is not None:
                future.cancel()
            raise
        recorded = result.duration_seconds if result.duration_seconds > 0 else telegram_seconds
        if result.no_speech or not result.text.strip():
            if future is not None:
                future.cancel()
            return result, SpeechTime(recording_time(recorded).recording_seconds, 0.0, "none")
        fallback = recording_time(recorded, source="recording_fallback")
        if future is None:
            return result, SpeechTime(fallback.recording_seconds, fallback.speech_seconds,
                                      fallback.source, "busy")
        try:
            speech, elapsed_ms = await asyncio.wait_for(
                asyncio.wrap_future(future), timeout=self.extra_wait_seconds,
            )
            if not math.isfinite(speech) or speech <= 0:
                raise ValueError("vad_stt_disagreement")
            if fallback.recording_seconds > 0 and speech > fallback.recording_seconds + 0.1:
                raise ValueError("vad_exceeds_recording")
            return result, SpeechTime(fallback.recording_seconds, speech, "vad", vad_ms=elapsed_ms)
        except asyncio.TimeoutError:
            reason = "timeout"
        except ValueError as error:
            reason = str(error)
        except Exception:
            reason = "error"
            logger.exception("Speech VAD failed")
        return result, SpeechTime(fallback.recording_seconds, fallback.speech_seconds,
                                  fallback.source, reason)

    def close(self) -> None:
        self._pool.shutdown(wait=False, cancel_futures=True)


class SpeechTimeRollout:
    """Persist the first active practice day, so restarts cannot mix daily methods."""

    def __init__(self, redis: Redis | None = None, clock=time.time) -> None:
        self.redis = redis
        self.clock = clock
        self._activation_day: str | None = None

    async def activation_day(self) -> str:
        if self._activation_day is None:
            tomorrow = (datetime.fromtimestamp(self.clock(), _MOSCOW).date() + timedelta(days=1)).isoformat()
            if self.redis is None:
                self._activation_day = tomorrow
            else:
                await self.redis.set(_ROLLOUT_KEY, tomorrow, nx=True)
                self._activation_day = str(await self.redis.get(_ROLLOUT_KEY))
        return self._activation_day

    async def method_for_day(self, day: str) -> str:
        return "vad" if day >= await self.activation_day() else "recording"

    async def aclose(self) -> None:
        if self.redis is not None:
            await self.redis.aclose()
