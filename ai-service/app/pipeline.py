from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass

from typing import Any

from app.dialogue import DialogueStore
from app.llm import ChatModel, Correction
from app.metrics import DEFAULT_RATES, MemoryMetricsStore, MetricsStore
from app.streaks import StreakStore, StreakUpdate, build_streak_store
from app.stt import SpeechToText, SttResult
from app.tts import TextToSpeech

logger = logging.getLogger(__name__)

CLARIFY_TEXT = "I didn't catch that. Could you say it again?"


@dataclass
class PipelineResult:
    audio: bytes | None
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
    ) -> None:
        self.personalization = None
        self._stt = stt
        self._llm = llm
        self._tts = tts
        self._dialogue = dialogue
        self._metrics = metrics if metrics is not None else MemoryMetricsStore(DEFAULT_RATES)
        self._streaks = streaks if streaks is not None else build_streak_store(self._metrics)
        self.calls = calls

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
    ) -> PipelineResult:
        started = time.perf_counter()

        stt_started = time.perf_counter()
        stt_result = await self._stt.transcribe(audio, content_type, filename)
        stt_ms = _elapsed_ms(stt_started)

        if _should_clarify(stt_result):
            tts_started = time.perf_counter()
            reply_audio = await self._tts.synthesize(CLARIFY_TEXT)
            timings = {"stt": stt_ms, "llm": 0, "tts": _elapsed_ms(tts_started)}
            finalize_started = time.perf_counter()
            await self._metrics.record_turn(session_id, stt_result.duration_seconds, len(CLARIFY_TEXT))
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
        history = await self._dialogue.history(session_id)
        if self.personalization is not None:
            profile_note = await self.personalization.prepare(session_id)
        timings = {"stt": stt_ms, "context": _elapsed_ms(context_started)}

        async def measured(name: str, operation):
            step_started = time.perf_counter()
            try:
                return await operation
            finally:
                timings[name] = _elapsed_ms(step_started)

        tasks = [
            asyncio.create_task(measured("notes", self._llm.complete_notes(stt_result.text))),
        ]
        if self.personalization is not None:
            tasks.append(asyncio.create_task(measured(
                "memoryExtract", self.personalization.extract_person(session_id, stt_result.text),
            )))
        try:
            reply_text = await measured("reply", self._llm.complete_reply(history, stt_result.text, profile_note))
            tts_task = asyncio.create_task(measured("tts", self._tts.synthesize(reply_text)))
            tasks.append(tts_task)
            dialogue_started = time.perf_counter()
            await self._dialogue.record_turn(session_id, stt_result.text, reply_text)
            timings["dialogue"] = _elapsed_ms(dialogue_started)
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
        await self._metrics.record_turn(session_id, stt_result.duration_seconds, len(reply_text))
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
        return await self._record_streak(session_id)

    async def _record_call(self, session_id: str, stt_result: SttResult, reply_text: str, corrections: list[Correction]) -> dict | None:
        if self.calls is None:
            return None
        try:
            return await self.calls.append_turn(
                session_id,
                stt_result.text,
                reply_text,
                [item.to_json() for item in corrections],
                stt_result.duration_seconds,
                stt_result.words,
            )
        except Exception:
            logger.exception("call turn failed session=%s", session_id)
            return None

    async def _call_summary(self, session_id: str) -> dict | None:
        if self.calls is None:
            return None
        try:
            return await self.calls.summary(session_id)
        except Exception:
            logger.exception("call summary failed session=%s", session_id)
            return None

    async def _record_streak(self, session_id: str) -> StreakUpdate | None:
        try:
            return await self._streaks.record_activity(session_id)
        except Exception:
            logger.exception("streak update failed session=%s", session_id)
            return None


def _should_clarify(result: SttResult) -> bool:
    return result.no_speech or not result.text.strip()


def _elapsed_ms(started: float) -> int:
    return int((time.perf_counter() - started) * 1000)
