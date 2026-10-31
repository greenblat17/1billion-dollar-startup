from __future__ import annotations

import asyncio
import threading
from concurrent.futures import Future
from datetime import datetime
from zoneinfo import ZoneInfo

import pytest
from fakeredis import FakeAsyncRedis

from app import speech_time
from app.speech_time import SpeechTimeMeter, SpeechTimeRollout, voiced_seconds
from app.stt import SttResult


class _FramesVad:
    def __init__(self, _mode: int) -> None:
        pass

    def is_speech(self, frame: bytes, _rate: int) -> bool:
        return frame[0] == 1


def test_voiced_seconds_excludes_long_pauses_and_merges_short_gaps(monkeypatch):
    import webrtcvad
    monkeypatch.setattr(webrtcvad, "Vad", _FramesVad)
    frame_bytes = 640
    # 0.20 s speech, 0.10 s pause, 0.20 s speech, 0.60 s pause, 0.20 s speech.
    frames = [1] * 10 + [0] * 5 + [1] * 10 + [0] * 30 + [1] * 10
    pcm = b"".join(bytes([value]) + bytes(frame_bytes - 1) for value in frames)
    assert voiced_seconds(pcm) == pytest.approx(0.82)
    assert voiced_seconds(bytes(frame_bytes * 20)) == 0


class _Stt:
    def __init__(self, text: str = "Hello", seconds: float = 9.0) -> None:
        self.text = text
        self.seconds = seconds

    async def transcribe(self, _audio, _content_type, _filename, language="en"):
        return SttResult(text=self.text, no_speech=not self.text, duration_seconds=self.seconds)


@pytest.mark.asyncio
async def test_speech_time_prefers_vad_and_falls_back_on_disagreement(monkeypatch):
    meter = SpeechTimeMeter("ffmpeg")
    try:
        completed: Future[tuple[float, int]] = Future()
        completed.set_result((4.0, 12))
        monkeypatch.setattr(meter, "_start", lambda *_: completed)
        _, measured = await meter.transcribe(_Stt(), b"audio", "audio/ogg", "x.ogg")
        assert (measured.speech_seconds, measured.recording_seconds, measured.source) == (4.0, 9.0, "vad")

        empty: Future[tuple[float, int]] = Future()
        empty.set_result((0.0, 12))
        monkeypatch.setattr(meter, "_start", lambda *_: empty)
        _, fallback = await meter.transcribe(_Stt(), b"audio", "audio/ogg", "x.ogg")
        assert (fallback.speech_seconds, fallback.source, fallback.fallback_reason) == (
            9.0, "recording_fallback", "vad_stt_disagreement",
        )
        _, silent = await meter.transcribe(_Stt(text=""), b"audio", "audio/ogg", "x.ogg")
        assert silent.speech_seconds == 0
    finally:
        meter.close()


@pytest.mark.asyncio
async def test_speech_time_timeout_uses_recording_without_blocking_reply(monkeypatch):
    meter = SpeechTimeMeter("ffmpeg", extra_wait_seconds=0.01)
    try:
        pending: Future[tuple[float, int]] = Future()
        monkeypatch.setattr(meter, "_start", lambda *_: pending)
        _, measured = await asyncio.wait_for(
            meter.transcribe(_Stt(), b"audio", "audio/ogg", "x.ogg"), 0.2,
        )
        assert (measured.speech_seconds, measured.source, measured.fallback_reason) == (
            9.0, "recording_fallback", "timeout",
        )
    finally:
        meter.close()


@pytest.mark.asyncio
async def test_bounded_queue_runs_burst_and_cancels_waiting_job_after_timeout(monkeypatch):
    release = threading.Event()

    def blocked_decode(*_args):
        release.wait(2)
        return b"pcm"

    monkeypatch.setattr(speech_time, "_pcm16k", blocked_decode)
    monkeypatch.setattr(speech_time, "voiced_seconds", lambda _: 2.0)
    meter = SpeechTimeMeter("ffmpeg", workers=1, queued=1, extra_wait_seconds=0.01)
    try:
        running = meter._start(b"audio", "a.ogg")
        assert running is not None
        _, timed_out = await meter.transcribe(_Stt(), b"audio", "audio/ogg", "b.ogg")
        assert (timed_out.source, timed_out.fallback_reason) == ("recording_fallback", "timeout")
        # The timed-out queued future freed its slot; a new request can enter the queue.
        queued = meter._start(b"audio", "c.ogg")
        assert queued is not None
        assert meter._start(b"audio", "d.ogg") is None
        release.set()
        assert running.result(timeout=2)[0] == 2.0
        assert queued.result(timeout=2)[0] == 2.0
    finally:
        release.set()
        meter.close()


@pytest.mark.asyncio
async def test_rollout_day_survives_restart():
    redis = FakeAsyncRedis(decode_responses=True)
    moscow = ZoneInfo("Europe/Moscow")
    first_time = datetime(2026, 10, 24, 15, tzinfo=moscow).timestamp()
    first = SpeechTimeRollout(redis, clock=lambda: first_time)
    assert await first.method_for_day("2026-10-24") == "recording"
    assert await first.method_for_day("2026-10-25") == "vad"
    restarted = SpeechTimeRollout(redis, clock=lambda: first_time + 86_400)
    assert await restarted.activation_day() == "2026-10-25"
    await redis.aclose()
