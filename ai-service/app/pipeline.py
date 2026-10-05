from __future__ import annotations

import asyncio
import logging
import time
from contextvars import ContextVar, Token
from dataclasses import dataclass

from typing import Any, Callable

from app.dialogue import DialogueStore
from app.llm import ChatModel, Correction, CorrectionRun
from app.metrics import DEFAULT_RATES, MemoryMetricsStore, MetricsStore
from app.metrics_v2 import MetricsV2, bind_metrics, reset_metrics
from app.speech import SessionSpeech
from app.streaks import StreakStore, StreakUpdate, build_streak_store
from app.stt import SpeechToText, SttResult
from app.tts import TextToSpeech, TtsAudio

logger = logging.getLogger(__name__)
_job_timings: ContextVar[dict[str, int] | None] = ContextVar("job_timings", default=None)


def bind_job_timings(timings: dict[str, int]) -> Token:
    return _job_timings.set(timings)


def reset_job_timings(token: Token) -> None:
    _job_timings.reset(token)


def current_job_timings() -> dict[str, int] | None:
    return _job_timings.get()

CLARIFY_TEXT = "I didn't catch that. Could you say it again?"
NOTES_TIMEOUT_SECONDS = 10.0
CORRECTION_METRICS_TIMEOUT_SECONDS = 0.2
PARTIAL_METRICS_TIMEOUT_SECONDS = 0.02


@dataclass
class PipelineResult:
    audio: TtsAudio | None
    transcript: str
    reply_text: str
    timings_ms: dict[str, int]
    corrections: list[Correction]
    streak: StreakUpdate | None = None
    onboarding: dict | None = None
    call: dict | None = None

    @property
    def notes(self) -> list[str]:
        return [item.note for item in self.corrections]


class ClipPipeline:
    def __init__(
        self,
        stt: SpeechToText,
        llm: ChatModel,
        tts: TextToSpeech,
        dialogue: DialogueStore,
        metrics: MetricsStore | None = None,
        streaks: StreakStore | None = None,
        calls: Any | None = None,
        speech: SessionSpeech | None = None,
        notes_timeout_seconds: float = NOTES_TIMEOUT_SECONDS,
        v2: MetricsV2 | None = None,
    ) -> None:
        if notes_timeout_seconds <= 0:
            raise ValueError("notes timeout must be positive")
        self.personalization = None
        self._stt = stt
        self._llm = llm
        self._tts = tts
        self.speech = speech or SessionSpeech(tts)
        self._dialogue = dialogue
        self._metrics = metrics if metrics is not None else MemoryMetricsStore(DEFAULT_RATES)
        self._v2 = v2
        self._streaks = streaks if streaks is not None else build_streak_store(self._metrics)
        self.calls = calls
        self._notes_timeout_seconds = notes_timeout_seconds

    @property
    def stt(self) -> SpeechToText:
        return self._stt

    @property
    def llm(self) -> ChatModel:
        return self._llm

    @property
    def tts(self) -> TextToSpeech:
        return self._tts

    @property
    def dialogue(self) -> DialogueStore:
        return self._dialogue

    @property
    def metrics(self) -> MetricsStore:
        return self._metrics

    async def complete_live_notes(self, transcript: str) -> list[Correction]:
        started = time.perf_counter()
        deadline_at = time.monotonic() + self._notes_timeout_seconds
        attempts = 0

        def attempt_started() -> None:
            nonlocal attempts
            attempts += 1

        try:
            run = await asyncio.wait_for(
                self._llm.complete_notes_result(
                    transcript, deadline_at=deadline_at, attempt_started=attempt_started,
                ),
                self._notes_timeout_seconds,
            )
        except asyncio.TimeoutError:
            run = CorrectionRun([], "deadline", attempts)
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            logger.warning("live correction failed; outcome=other_error error=%s", type(exc).__name__)
            run = CorrectionRun([], "other_error", attempts)

        attempts = run.attempts
        elapsed_ms = _elapsed_ms(started)
        current = _job_timings.get()
        if current is not None:
            current["notes"] = elapsed_ms
        logger.info("live correction outcome=%s attempts=%s elapsed_ms=%s", run.outcome, attempts, elapsed_ms)
        try:
            await asyncio.wait_for(
                self._metrics.record_correction(run.outcome, elapsed_ms, attempts),
                CORRECTION_METRICS_TIMEOUT_SECONDS,
            )
            if self._v2 is not None and run.outcome not in {"shown", "filtered", "empty"}:
                await self._v2.record_error("", "corrections", run.outcome)
        except Exception:
            logger.exception("failed to record correction outcome")
        return run.corrections

    @property
    def streaks(self) -> StreakStore:
        return self._streaks

    async def run(
        self,
        session_id: str,
        audio: bytes,
        content_type: str,
        filename: str,
        profile_note: str | None = None,
        on_stage: Callable[[str], None] | None = None,
    ) -> PipelineResult:
        started = time.perf_counter()
        bound = bind_metrics(session_id)
        try:
            return await self._run_bound(session_id, audio, content_type, filename, profile_note, started, on_stage)
        finally:
            reset_metrics(bound)

    async def _run_bound(self, session_id, audio, content_type, filename, profile_note, started, on_stage):
        timings = _job_timings.get()
        if timings is None:
            timings = {}
        def stage(name: str) -> None:
            if on_stage is not None:
                on_stage(name)

        stt_started = time.perf_counter()
        stage("stt")
        try:
            stt_result = await self._stt.transcribe(audio, content_type, filename)
        except Exception:
            if self._v2 is not None:
                await self._v2.record_error(session_id, "stt", "failed")
            raise
        finally:
            timings["stt"] = _elapsed_ms(stt_started)
        stt_ms = timings["stt"]
        if self._v2 is not None:
            await self._v2.record_stt(session_id, getattr(self._stt, "_model", "stt"), stt_result.duration_seconds)

        if _should_clarify(stt_result):
            tts_started = time.perf_counter()
            stage("tts")
            reply_audio = await self.speech.synthesize(session_id, CLARIFY_TEXT)
            timings.update({"stt": stt_ms, "llm": 0, "tts": _elapsed_ms(tts_started)})
            finalize_started = time.perf_counter()
            stage("metrics")
            await self._metrics.record_turn(session_id, stt_result.duration_seconds, len(CLARIFY_TEXT))
            if self._v2 is not None:
                await self._v2.record_turn(session_id)
            await self._metrics.record_exchange(session_id)
            streak = await self._record_streak(session_id)
            call = await self._call_summary(session_id)
            timings["finalize"] = _elapsed_ms(finalize_started)
            timings["total"] = _elapsed_ms(started)
            logger.info("clip pipeline clarify session=%s timings_ms=%s", session_id, timings)
            return PipelineResult(
                audio=reply_audio,
                transcript=stt_result.text,
                reply_text=CLARIFY_TEXT,
                timings_ms=timings,
                corrections=[],
                streak=streak,
                call=call,
            )

        context_started = time.perf_counter()
        stage("state")
        history = await self._dialogue.history(session_id)
        if self.personalization is not None:
            profile_note = await self.personalization.prepare(session_id)
        timings.update({"stt": stt_ms, "context": _elapsed_ms(context_started)})

        async def measured(name: str, operation):
            step_started = time.perf_counter()
            try:
                return await operation
            except Exception as error:
                operation_stage = "tts" if name == "tts" else "llm" if name in {"reply", "notes"} else "state"
                error._speaky_stage = operation_stage
                raise
            finally:
                timings[name] = _elapsed_ms(step_started)

        tasks = [asyncio.create_task(measured("notes", self.complete_live_notes(stt_result.text)))]
        if self.personalization is not None:
            tasks.append(asyncio.create_task(measured(
                "memoryExtract", self.personalization.extract_person(session_id, stt_result.text),
            )))
        try:
            stage("llm")
            reply_text = await measured("reply", self._llm.complete_reply(history, stt_result.text, profile_note))
            tts_task = asyncio.create_task(measured("tts", self.speech.synthesize(session_id, reply_text)))
            tasks.append(tts_task)
            dialogue_started = time.perf_counter()
            stage("state")
            await self._dialogue.record_turn(session_id, stt_result.text, reply_text)
            timings["dialogue"] = _elapsed_ms(dialogue_started)
            stage("tts")
            corrections, reply_audio = await asyncio.gather(tasks[0], tts_task)
            if self.personalization is not None:
                observation = await tasks[1]
                save_started = time.perf_counter()
                await self.personalization.save_observation(session_id, observation)
                timings["memorySave"] = _elapsed_ms(save_started)
        finally:
            for task in tasks:
                if not task.done():
                    task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)

        timings["llm"] = max(timings["reply"], timings["notes"])
        finalize_started = time.perf_counter()
        stage("metrics")
        await self._metrics.record_turn(session_id, stt_result.duration_seconds, len(reply_text))
        if self._v2 is not None:
            await self._v2.record_turn(session_id)
        await self._metrics.record_exchange(session_id)
        streak = await self._record_streak(session_id)
        call = await self._record_call(session_id, stt_result, reply_text, corrections)
        timings["finalize"] = _elapsed_ms(finalize_started)
        timings["total"] = _elapsed_ms(started)
        logger.info("clip pipeline ok session=%s timings_ms=%s", session_id, timings)
        return PipelineResult(
            audio=reply_audio,
            transcript=stt_result.text,
            reply_text=reply_text,
            timings_ms=timings,
            corrections=list(corrections),
            streak=streak,
            call=call,
        )

    async def record_completed_turn(self, session_id: str, seconds: float, tts_chars: int) -> StreakUpdate | None:
        await self._metrics.record_turn(session_id, seconds, tts_chars)
        await self._metrics.record_exchange(session_id)
        if self._v2 is not None:
            await self._v2.record_stt(session_id, "onboarding", seconds)
            await self._v2.record_turn(session_id)
        return await self._record_streak(session_id)

    async def _record_call(self, session_id: str, stt_result: SttResult, reply_text: str, corrections: list[Correction]) -> dict | None:
        if self.calls is None:
            return None
        await self.record_partial("call_turn", "attempted")
        try:
            result = await self.calls.append_turn(
                session_id,
                stt_result.text,
                reply_text,
                [item.to_json() for item in corrections],
                stt_result.duration_seconds,
                stt_result.words,
            )
            await self.record_partial("call_turn", "succeeded")
            return {**result, "recognizedSeconds": stt_result.duration_seconds} if result is not None else None
        except Exception as error:
            await self.record_partial("call_turn", "failed", "timeout" if isinstance(error, TimeoutError) else "internal")
            logger.exception("call turn failed session=%s", session_id)
            return None

    async def _call_summary(self, session_id: str) -> dict | None:
        if self.calls is None:
            return None
        await self.record_partial("call_summary", "attempted")
        try:
            result = await self.calls.summary(session_id)
            await self.record_partial("call_summary", "succeeded")
            return result
        except Exception as error:
            await self.record_partial("call_summary", "failed", "timeout" if isinstance(error, TimeoutError) else "internal")
            logger.exception("call summary failed session=%s", session_id)
            return None

    async def _record_streak(self, session_id: str) -> StreakUpdate | None:
        await self.record_partial("streak", "attempted")
        try:
            result = await self._streaks.record_activity(session_id)
            await self.record_partial("streak", "succeeded")
            return result
        except Exception as error:
            await self.record_partial("streak", "failed", "timeout" if isinstance(error, TimeoutError) else "internal")
            logger.exception("streak update failed session=%s", session_id)
            return None

    async def record_partial(self, feature: str, outcome: str, reason: str = "unknown") -> None:
        try:
            await asyncio.wait_for(self._metrics.record_partial(feature, outcome, reason=reason), PARTIAL_METRICS_TIMEOUT_SECONDS)
        except Exception:
            logger.exception("failed to record partial feature result feature=%s outcome=%s", feature, outcome)


def _should_clarify(result: SttResult) -> bool:
    return result.no_speech or not result.text.strip()


def _elapsed_ms(started: float) -> int:
    return int((time.perf_counter() - started) * 1000)
