from __future__ import annotations

import asyncio
import json
import math
import logging
import time
from copy import deepcopy
from typing import Any
from uuid import uuid4

from redis.asyncio import Redis

from app.llm import Correction
from app.audit_artifacts import record_artifact, recorded_at
from app.onboarding_review import closing_lines, correction_candidates, explained_examples, fluency_metrics, grounded_callback, select_examples
from app.onboarding_score import apply_skill, normalize_shade, overall_progress
from app.vocabulary_suggestions import select_vocabulary_suggestions
from app.personalization import Personalization
from app.pipeline import CLARIFY_TEXT, ClipPipeline, PipelineResult, current_job_timings
from app.metrics_v2 import bind_metrics, reset_metrics
from app.tts import TtsAudio

logger = logging.getLogger(__name__)

FIRST_QUESTION = (
    "Alright, now you can actually hear me. And yep, this is actually my voice. "
    "I’d really love to get to know you a little. So, tell me about yourself — "
    "whatever you’d like me to know."
)
RETRY_TEXT = "I couldn't prepare your result. Please try again."
RESULT_READY_TEXT = "Your results are ready."
SPEECH_LIMIT_SECONDS = 120
SPEECH_MILESTONES = (30, 60, 90, 120)
PROFILE_FIELDS = ("work", "leisure", "goal")
RAW_CONTENT_SECONDS = 7 * 24 * 60 * 60


def _expire_raw_turns(state: dict, now: float | None = None) -> dict:
    """Keep assessment/progress but remove raw turns whose age cannot meet retention."""
    cutoff = (now if now is not None else time.time()) - RAW_CONTENT_SECONDS
    original = state.get("turns") or []
    current = [turn for turn in original if isinstance(turn, dict)
               and isinstance(turn.get("receivedAt"), (int, float)) and turn["receivedAt"] > cutoff]
    state["turns"] = current
    # Questions, review examples and pending assessment can quote a user's answer.
    # Legacy states have no timestamp, so discard these when no dated turn remains.
    if not current:
        for key in ("continueQuestion", "review", "assessment", "resultText"):
            state.pop(key, None)
        state["question"] = FIRST_QUESTION
    if len(current) != len(original) and state.get("status") in {"active", "pending"}:
        state["status"] = "waiting"
        state["seconds"] = 0.0
        state["question"] = FIRST_QUESTION
    return state


class OnboardingSttError(Exception):
    """The onboarding transcription stage failed before any speech was accepted."""


class OnboardingStore:
    """One current attempt per chat, independent of the expiring dialogue history.

    The service owns the per-chat lock; memory and Redis use the same JSON shape.
    Production runs one AI worker, as do the existing dialogue stores.
    """

    def __init__(self, redis: Redis | None = None) -> None:
        self._redis = redis
        self._memory: dict[str, dict] = {}
        self._goals: dict[str, int] = {}
        self._assessments: dict[str, dict] = {}
        self._learners: dict[str, dict] = {}
        self._legacy_invited: set[str] = set()
        self._locks: dict[str, asyncio.Lock] = {}

    def lock(self, session_id: str) -> asyncio.Lock:
        return self._locks.setdefault(session_id, asyncio.Lock())

    async def get(self, session_id: str) -> dict | None:
        if self._redis is not None:
            raw = await self._redis.get(f"onboarding:{session_id}")
            if not raw:
                return None
            loaded = json.loads(raw)
            before = json.dumps(loaded, sort_keys=True)
            cleaned = _expire_raw_turns(loaded)
            if json.dumps(cleaned, sort_keys=True) != before:
                await self._redis.set(f"onboarding:{session_id}", json.dumps(cleaned))
            return cleaned
        state = deepcopy(self._memory.get(session_id))
        return _expire_raw_turns(state) if state else None

    async def save(self, session_id: str, state: dict) -> None:
        state = _expire_raw_turns(state)
        fresh = assessment_summary(state)
        if self._redis is not None:
            stored = _kept_assessment(await self._read_assessment(session_id), fresh)
            async with self._redis.pipeline(transaction=True) as pipe:
                pipe.set(f"onboarding:{session_id}", json.dumps(state))
                if stored is not None:
                    pipe.set(f"assessment:{session_id}", json.dumps(stored))
                await pipe.execute()
        else:
            self._memory[session_id] = deepcopy(state)
            stored = _kept_assessment(self._assessments.get(session_id), fresh)
            if stored is not None:
                self._assessments[session_id] = deepcopy(stored)

    async def prune_all(self) -> int:
        if self._redis is None:
            for session_id, state in self._memory.items():
                self._memory[session_id] = _expire_raw_turns(state)
            return len(self._memory)
        changed = 0
        async for key in self._redis.scan_iter(match="onboarding:*", count=100):
            async with self.lock(key.removeprefix("onboarding:")):
                raw = await self._redis.get(key)
                if not raw:
                    continue
                loaded = json.loads(raw)
                before = json.dumps(loaded, sort_keys=True)
                cleaned = _expire_raw_turns(loaded)
                if json.dumps(cleaned, sort_keys=True) != before:
                    await self._redis.set(key, json.dumps(cleaned))
                    changed += 1
        return changed

    async def save_assessment(self, session_id: str, assessment: dict) -> None:
        if self._redis is not None:
            await self._redis.set(f"assessment:{session_id}", json.dumps(assessment))
        else:
            self._assessments[session_id] = deepcopy(assessment)

    async def _read_assessment(self, session_id: str) -> dict | None:
        if self._redis is None:
            return deepcopy(self._assessments.get(session_id))
        raw = await self._redis.get(f"assessment:{session_id}")
        return json.loads(raw) if raw else None

    async def get_assessment(self, session_id: str) -> dict | None:
        if self._redis is not None:
            raw = await self._redis.get(f"assessment:{session_id}")
            saved = json.loads(raw) if raw else None
        else:
            saved = deepcopy(self._assessments.get(session_id))
        # Existing completed attempts are readable before the first snapshot write.
        return saved if saved is not None else assessment_summary(await self.get(session_id))

    async def get_learner(self, session_id: str) -> dict | None:
        if self._redis is not None:
            raw = await self._redis.get(f"learner:{session_id}")
            return json.loads(raw) if raw else None
        return deepcopy(self._learners.get(session_id))

    async def save_learner(self, session_id: str, memory: dict) -> None:
        if self._redis is not None:
            await self._redis.set(f"learner:{session_id}", json.dumps(memory))
        else:
            self._learners[session_id] = deepcopy(memory)

    async def claim_legacy_invitation(self, session_id: str) -> bool:
        if self._redis is not None:
            return bool(await self._redis.set(f"legacy-invitation:{session_id}", "1", nx=True))
        if session_id in self._legacy_invited:
            return False
        self._legacy_invited.add(session_id)
        return True

    async def release_legacy_invitation(self, session_id: str) -> None:
        if self._redis is not None:
            await self._redis.delete(f"legacy-invitation:{session_id}")
        else:
            self._legacy_invited.discard(session_id)

    async def aclose(self) -> None:
        if self._redis is not None:
            await self._redis.aclose()

    async def set_goal(self, session_id: str, minutes: int) -> None:
        payload = json.dumps({"minutes": minutes})
        if self._redis is not None:
            await self._redis.set(f"practice-goal:{session_id}", payload)
        else:
            self._goals[session_id] = minutes

    async def get_goal(self, session_id: str) -> int | None:
        if self._redis is not None:
            raw = await self._redis.get(f"practice-goal:{session_id}")
            if not raw:
                return None
            minutes = json.loads(raw).get("minutes")
            return minutes if isinstance(minutes, int) else None
        return self._goals.get(session_id)


def _shown_corrections(turn: dict | None) -> list[Correction]:
    notes = (turn or {}).get("corrections") or []
    shown = []
    for note in notes[:1]:
        if isinstance(note, dict) and note.get("wrong") and note.get("better"):
            shown.append(Correction(
                str(note["wrong"]), str(note["better"]), str(note.get("kind") or "grammar"),
                note.get("explanation") if isinstance(note.get("explanation"), str) else None,
            ))
    return shown


def assessment_summary(state: dict | None) -> dict | None:
    if not state or state.get("status") != "completed" or not isinstance(state.get("review"), dict):
        return None
    return {
        "runId": state.get("runId"),
        "cefr": state.get("cefr"),
        "position": state.get("position"),
        "shade": _stored_shade(state),
        **overall_progress(state.get("cefr"), state.get("position"), _stored_shade(state)),
        **{skill: (state["review"].get(skill) or {}).get("score")
           for skill in ("grammar", "vocabulary", "fluency")},
    }


def public_assessment(assessment: dict | None) -> dict | None:
    if not isinstance(assessment, dict):
        return None
    return {
        key: assessment.get(key)
        for key in ("cefr", "overallScore", "nextBand", "pointsToNext", "grammar", "vocabulary", "fluency")
    }


def _kept_assessment(existing: dict | None, fresh: dict | None) -> dict | None:
    """A later save of the same attempt must not wipe a call that already moved the snapshot."""
    if fresh is None:
        return None
    if existing is None:
        return fresh
    existing_run = existing.get("runId")
    if not existing_run:
        if existing.get("overallScore") is None:
            return fresh
        patched = dict(existing)
        patched["runId"] = fresh.get("runId")
        return patched
    if existing_run != fresh.get("runId"):
        return fresh
    return None


def public_state(state: dict) -> dict:
    payload = {
        **{key: state.get(key) for key in ("runId", "status", "seconds", "cefr", "resultText")},
        "legacyUser": bool(state.get("legacyUser") or state.get("status") == "exempt"),
        **overall_progress(state.get("cefr"), state.get("position"), _stored_shade(state)),
        "retryAvailable": state["status"] == "pending" or (state["status"] == "active" and bool(state["turns"])),
        "react": state["status"] == "active" and not state.get("voiceArrived") and not state["turns"],
    }
    review = state.get("review")
    if isinstance(review, dict):
        payload["review"] = _public_review(review)
    return payload


def _stored_shade(state: dict) -> str:
    review = state.get("review")
    if not isinstance(review, dict):
        return "0"
    return normalize_shade(review.get("shade"))


def _public_review(review: dict) -> dict:
    return {
        "levelText": review.get("levelText") or "",
        "grammar": _public_skill(review.get("grammar") or {}),
        "vocabulary": _public_skill(review.get("vocabulary") or {}),
        "fluency": _public_fluency(review.get("fluency") or {}),
    }


def _public_skill(skill: dict) -> dict:
    return {
        "score": skill.get("score"),
        "text": skill.get("text") or "",
        "examples": skill.get("examples") or [],
        "suggestions": skill.get("suggestions") or [],
    }


def _public_fluency(skill: dict) -> dict:
    return {
        "score": skill.get("score"),
        "text": skill.get("text") or "",
        "paceWpm": skill.get("paceWpm"),
        "longPauses": skill.get("longPauses"),
        "fillers": skill.get("fillers"),
        "longestStretchSec": skill.get("longestStretchSec"),
    }


def _attempt(receipts: list[str] | None = None, legacy_user: bool = False) -> dict:
    return {
        "runId": uuid4().hex,
        "status": "waiting",
        "legacyUser": legacy_user,
        "seconds": 0.0,
        "turns": [],
        "profile": {},
        "cefr": None,
        "position": None,
        "resultText": None,
        "question": FIRST_QUESTION,
        "receipts": receipts or [],
        "continued": False,
    }


def _apply_level(state: dict, assessment: dict) -> None:
    state["cefr"] = assessment.get("cefr")
    state["position"] = assessment.get("position")


def _filled(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())


def next_ask(profile: dict) -> str:
    for key in PROFILE_FIELDS:
        if not _filled(profile.get(key)):
            return key
    return "followup"


def should_close(state: dict) -> bool:
    """Close at two minutes of recognized speech, even with an incomplete profile."""
    return state["seconds"] >= SPEECH_LIMIT_SECONDS


class OnboardingService:
    def __init__(self, store: OnboardingStore, pipeline: ClipPipeline, model: Any) -> None:
        self.store = store
        self.pipeline = pipeline
        self.model = model
        self.personalization = Personalization(store, model, pipeline.streaks)
        pipeline.personalization = self.personalization
        self._intro_audio: dict[float, TtsAudio] = {}
        self._intro_lock = asyncio.Lock()

    async def intro_audio(self, session_id: str | None = None) -> TtsAudio:
        speed = await self.pipeline.speech.speed(session_id) if session_id else self.pipeline.speech.default_speed
        async with self._intro_lock:
            if speed not in self._intro_audio:
                audio = await self.pipeline.speech.synthesize(session_id or "", FIRST_QUESTION, speed=speed)
                if not audio.data:
                    raise ValueError("empty onboarding intro audio")
                self._intro_audio[speed] = audio
            return self._intro_audio[speed]

    async def warm_intro(self, timeout: float) -> None:
        try:
            await asyncio.wait_for(self.intro_audio(), timeout=timeout)
        except Exception:
            logger.exception("Onboarding intro warmup failed; next request will retry")

    async def resolve(self, session_id: str, request_id: str, reset: str = "") -> dict:
        async with self.store.lock(session_id):
            state = await self.store.get(session_id)
            if state is None:
                known = await self.pipeline.metrics.is_known(session_id)
                state = _attempt(legacy_user=known)
                if known:
                    state["status"] = "exempt"
            if request_id not in state["receipts"]:
                if reset == "force" or (reset == "start" and state["status"] in {"waiting", "active", "pending"}):
                    if state["status"] == "completed":
                        await self.personalization.prepare(session_id)
                        await self.store.save(session_id, state)
                    state = _attempt(state["receipts"], legacy_user=bool(
                        state.get("legacyUser") or state["status"] == "exempt"
                    ))
                state["receipts"] = (state["receipts"] + [request_id])[-256:]
                await self.store.save(session_id, state)
            return public_state(state)

    async def legacy_invitation(self, session_id: str, action: str) -> bool:
        async with self.store.lock(session_id):
            if action == "release":
                await self.store.release_legacy_invitation(session_id)
                return True
            state = await self.store.get(session_id)
            if not state or state["status"] != "exempt":
                return False
            return await self.store.claim_legacy_invitation(session_id)

    async def action(self, session_id: str, run_id: str, action: str, request_id: str = "") -> PipelineResult:
        bound = bind_metrics(session_id)
        try:
            return await self._action_locked(session_id, run_id, action, request_id)
        finally:
            reset_metrics(bound)

    async def _action_locked(self, session_id: str, run_id: str, action: str, request_id: str = "") -> PipelineResult:
        async with self.store.lock(session_id):
            state = await self._current(session_id, run_id)
            if state is None:
                return self._result(None)
            if request_id and request_id in state.get("actions", []):
                return self._result(None)
            result = await self._action(session_id, state, action)
            if request_id:
                state["actions"] = (state.get("actions", []) + [request_id])[-256:]
                await self.store.save(session_id, state)
            return result

    async def _action(self, session_id: str, state: dict, action: str) -> PipelineResult:
        if action == "begin" and (state["status"] == "waiting" or (state["status"] == "active" and not state["turns"])):
            audio = await self.intro_audio(session_id)
            state["status"] = "active"
            await self.store.save(session_id, state)
            return self._result(state, FIRST_QUESTION, audio)
        if action == "retry":
            if state["status"] == "completed":
                return self._result(None)
            if state["status"] == "pending":
                return await self._finish(session_id, state)
            if state["status"] == "active" and state["turns"]:
                turn = state["turns"][-1]
                if turn["delivered"]:
                    question = turn["reply"]
                    return self._result(state, question, await self.pipeline.speech.synthesize(session_id, question), turn)
                return await self._advance_turn(session_id, state, turn)
        if action == "continue" and state["status"] == "completed":
            if not state.get("continueQuestion"):
                state["continueQuestion"] = await self.model.continue_question({
                    "context": await self.personalization.prepare(session_id, continuation=True),
                    "recentConversation": [
                        {"role": item.role, "content": item.content}
                        for item in (await self.pipeline.dialogue.history(session_id))[-8:]
                    ],
                })
                await self.store.save(session_id, state)
            question = state["continueQuestion"]
            audio = await self.pipeline.speech.synthesize(session_id, question)
            # Old chats can still tap this button. New attempts never send it.
            if not state["continued"]:
                await self.pipeline.dialogue.record_turn(session_id, "Let's continue our conversation.", question)
            await self.personalization.observe(session_id, "")
            state["continued"] = True
            await self.store.save(session_id, state)
            return self._result(state, question, audio)
        return self._result(None)

    async def turn(
        self, session_id: str, run_id: str, request_id: str,
        audio: bytes, content_type: str, filename: str, duration: float = 0.0,
    ) -> PipelineResult:
        bound = bind_metrics(session_id)
        try:
            return await self._turn_locked(session_id, run_id, request_id, audio, content_type, filename, duration)
        finally:
            reset_metrics(bound)

    async def _turn_locked(
        self, session_id: str, run_id: str, request_id: str,
        audio: bytes, content_type: str, filename: str, duration: float = 0.0,
    ) -> PipelineResult:
        async with self.store.lock(session_id):
            state = await self._current(session_id, run_id)
            if state is None or state["status"] in {"waiting", "exempt"}:
                return self._result(None)
            # A technical retry reuses the checkpoint rather than counting the recording again.
            turn = next((item for item in state["turns"] if item["requestId"] == request_id), None)
            if turn is not None and turn.get("delivered"):
                return self._result(None)
            if state["status"] == "completed":
                return await self.pipeline.run(
                    session_id, audio, content_type, filename,
                )
            if state["status"] == "pending":
                return await self._finish(session_id, state)
            if turn is None:
                if not state.get("voiceArrived"):
                    state["voiceArrived"] = True
                    await self.store.save(session_id, state)
                try:
                    stt_started = time.perf_counter()
                    stt = await self.pipeline.stt.transcribe(audio, content_type, filename, language=None)
                except Exception as error:
                    raise OnboardingSttError() from error
                finally:
                    timings = current_job_timings()
                    if timings is not None:
                        timings["stt"] = int((time.perf_counter() - stt_started) * 1000)
                if stt.no_speech or not stt.text.strip():
                    return self._result(
                        state, CLARIFY_TEXT, await self.pipeline.speech.synthesize(session_id, CLARIFY_TEXT),
                        analytics=_voice_analytics(
                            voice_index=len(state["turns"]) + 1,
                            telegram_duration=duration,
                            recognized_duration=0.0,
                            recognized=False,
                            failure_reason="no_speech",
                            speech_before=float(state["seconds"]),
                            speech_after=float(state["seconds"]),
                        ),
                    )
                await record_artifact("transcript", stt.text)
                seconds = stt.duration_seconds if stt.duration_seconds > 0 else duration
                if not math.isfinite(seconds) or seconds <= 0:
                    raise ValueError("recording duration unavailable")
                before = float(state["seconds"])
                turn = {
                    "requestId": request_id, "question": state["question"], "transcript": stt.text,
                    "receivedAt": recorded_at(),
                    "seconds": seconds, "words": [dict(word) for word in stt.words],
                    "corrections": None, "analysis": None, "delivered": False,
                    "_analytics": _voice_analytics(
                        voice_index=len(state["turns"]) + 1,
                        telegram_duration=duration,
                        recognized_duration=seconds,
                        recognized=True,
                        milestones=[mark for mark in SPEECH_MILESTONES if before < mark <= before + seconds],
                        speech_before=before,
                        speech_after=before + seconds,
                    ),
                }
                state["turns"].append(turn)
                state["seconds"] += seconds
                await self.store.save(session_id, state)
            return await self._advance_turn(session_id, state, turn)

    async def _advance_turn(self, session_id: str, state: dict, turn: dict) -> PipelineResult:
        closing = should_close(state)
        closing_task = asyncio.create_task(self._closing_voice(session_id, state)) if closing else None
        notes_task = (
            asyncio.create_task(self.pipeline.complete_live_notes(turn["transcript"]))
            if turn["corrections"] is None else None
        )
        audio_task = None
        try:
            if turn["analysis"] is None:
                state["ask"] = next_ask(state.get("profile") or {})
                await self.pipeline.record_partial("assessment", "attempted")
                assessment_started = time.perf_counter()
                try:
                    turn["analysis"] = await self.model.assess(state)
                    await self.pipeline.record_partial("assessment", "succeeded")
                except Exception as error:
                    await self.pipeline.record_partial("assessment", "failed", "timeout" if isinstance(error, TimeoutError) else "internal")
                    logger.exception("onboarding assessment failed session=%s", session_id)
                    # A long answer may already be ready to close. Retry reuses it instead of asking again.
                    if state["seconds"] >= SPEECH_LIMIT_SECONDS:
                        if notes_task is not None:
                            notes = await notes_task
                            turn["corrections"] = [note.to_json() for note in notes]
                        state["status"] = "pending"
                        await self.store.save(session_id, state)
                        return await self._closing_result(
                            session_id, state, turn, closing_task, RETRY_TEXT,
                            analytics={"assessmentFailed": True},
                        )
                    raise
                finally:
                    timings = current_job_timings()
                    if timings is not None:
                        timings["reply"] = int((time.perf_counter() - assessment_started) * 1000)
                state["profile"] = turn["analysis"]["profile"]
                _apply_level(state, turn["analysis"])
                await self.store.save(session_id, state)
            assessment = turn["analysis"]
            state["profile"] = assessment["profile"]
            _apply_level(state, assessment)
            if not closing:
                audio_task = asyncio.create_task(
                    self.pipeline.speech.synthesize(session_id, assessment["question"])
                )
            if notes_task is not None:
                notes = await notes_task
                turn["corrections"] = [note.to_json() for note in notes]
                await self.store.save(session_id, state)
            if closing:
                state["status"] = "pending"
                state["assessment"] = assessment
                await self.store.save(session_id, state)
                return await self._finish(session_id, state, turn, closing_task)
            question = assessment["question"]
            reply_audio = await audio_task
            state["question"] = question
            turn["reply"] = question
            turn["delivered"] = True
            await self.store.save(session_id, state)
            result = self._result(state, question, reply_audio, turn)
            result.streak = await self.pipeline.record_completed_turn(session_id, turn["seconds"], len(question))
            return result
        finally:
            for task in (notes_task, audio_task, closing_task):
                if task is not None and not task.done():
                    task.cancel()
            await asyncio.gather(
                *(task for task in (notes_task, audio_task, closing_task) if task is not None),
                return_exceptions=True,
            )

    async def _closing_voice(self, session_id: str, state: dict) -> tuple[str, str, TtsAudio]:
        transcripts = [str(item.get("transcript") or "").strip() for item in state["turns"]]
        transcripts = [text for text in transcripts if text]
        try:
            await self.pipeline.record_partial("closing_callback", "attempted")
            callback = grounded_callback(
                await self.model.closing_callback(transcripts), transcripts[-1] if transcripts else "",
            )
            await self.pipeline.record_partial("closing_callback", "succeeded")
        except Exception as error:
            await self.pipeline.record_partial("closing_callback", "failed", "timeout" if isinstance(error, TimeoutError) else "internal")
            logger.exception("onboarding closing callback failed; using standard closing")
            callback = None
        subtitle, spoken = closing_lines(callback)
        tts_started = time.perf_counter()
        try:
            audio = await self.pipeline.speech.synthesize(session_id, spoken)
        finally:
            timings = current_job_timings()
            if timings is not None:
                timings["tts"] = int((time.perf_counter() - tts_started) * 1000)
        return subtitle, spoken, audio

    async def _closing_result(
        self, session_id: str, state: dict, turn: dict,
        closing_task: asyncio.Task | None, fallback_text: str,
        analytics: dict | None = None,
    ) -> PipelineResult:
        if closing_task is None:
            await self.pipeline.record_partial("closing_voice", "skipped")
            result = self._result(state, fallback_text, turn=turn, analytics=analytics)
            result.corrections = []
            return result
        await self.pipeline.record_partial("closing_voice", "attempted")
        try:
            subtitle, spoken, audio = await closing_task
            await self.pipeline.record_partial("closing_voice", "succeeded")
        except Exception as error:
            await self.pipeline.record_partial("closing_voice", "failed", "timeout" if isinstance(error, TimeoutError) else "internal")
            logger.exception("onboarding closing voice failed; sending text")
            subtitle, spoken = closing_lines(None)
            audio = None
        state["closing"] = {"subtitle": subtitle, "spoken": spoken}
        if isinstance(state.get("review"), dict):
            state["review"].update(closingText=subtitle, spokenText=spoken)
        state["question"] = spoken
        turn["reply"] = spoken
        turn["delivered"] = True
        await self._remember(session_id, state)
        streak = None
        if not turn.get("counted"):
            streak = await self.pipeline.record_completed_turn(session_id, turn["seconds"], len(spoken))
            turn["counted"] = True
        await self.store.save(session_id, state)
        result = self._result(state, subtitle if audio is not None else fallback_text, audio, turn, analytics)
        result.streak = streak
        return result

    async def _finish(
        self, session_id: str, state: dict, turn: dict | None = None,
        closing_task: asyncio.Task | None = None,
    ) -> PipelineResult:
        turn = turn or state["turns"][-1]
        try:
            assessment = state.get("assessment")
            if assessment is None:
                state["ask"] = next_ask(state.get("profile") or {})
                assessment = await self.model.assess(state)
                state["assessment"] = assessment
            state["profile"] = assessment["profile"]
            _apply_level(state, assessment)
            if not should_close(state):
                question = assessment["question"]
                audio = await self.pipeline.speech.synthesize(session_id, question)
                state["question"] = question
                turn["reply"] = question
                turn["delivered"] = True
                # The saved answer was not ready to close: keep the introduction going until the speech limit.
                state["status"] = "active"
                await self.store.save(session_id, state)
                result = self._result(state, question, audio, turn)
                result.streak = await self.pipeline.record_completed_turn(session_id, turn["seconds"], len(question))
                return result
            if state.get("review") is None:
                await self.pipeline.record_partial("review", "attempted")
                try:
                    state["review"] = await self._compose_review(state)
                    await self.pipeline.record_partial("review", "succeeded")
                except Exception as error:
                    await self.pipeline.record_partial("review", "failed", "timeout" if isinstance(error, TimeoutError) else "internal")
                    raise
                await self.store.save(session_id, state)
            if state.get("closing"):
                state["review"].update(
                    closingText=state["closing"]["subtitle"],
                    spokenText=state["closing"]["spoken"],
                )
            state["resultText"] = None
            state["status"] = "completed"
            await self._remember(session_id, state)
            await self.store.save(session_id, state)
        except Exception:
            logger.exception("onboarding result failed session=%s", session_id)
            state["status"] = "pending"
            # Keep the accepted turns and pending status for an explicit retry without more speech.
            await self.store.save(session_id, state)
            return await self._closing_result(
                session_id, state, turn, closing_task, RETRY_TEXT,
                analytics={"assessmentFailed": True},
            )
        await self.personalization.seed(session_id, state)
        progress = overall_progress(state.get("cefr"), state.get("position"), _stored_shade(state))
        return await self._closing_result(
            session_id, state, turn, closing_task, RESULT_READY_TEXT,
            analytics={
                "completedNow": True,
                "cefr": state.get("cefr"),
                "overallScore": progress["overallScore"],
                "scoreAvailable": progress["overallScore"] is not None,
            },
        )

    async def progress_profile(self, session_id: str) -> dict:
        async with self.store.lock(session_id):
            return {
                "assessment": public_assessment(await self.store.get_assessment(session_id)),
                "dailyMinutes": await self.store.get_goal(session_id),
                "currentStreak": await self.pipeline.streaks.shown(session_id),
            }

    async def set_goal(self, session_id: str, minutes: int) -> dict:
        if type(minutes) is not int or minutes not in {0, 5, 10, 15}:
            raise ValueError("invalid practice goal")
        async with self.store.lock(session_id):
            await self.store.set_goal(session_id, minutes)
        return {"minutes": minutes}

    async def _compose_review(self, state: dict) -> dict:
        candidates = correction_candidates(state["turns"])
        accepted: set[str] = set()
        if candidates:
            await self.pipeline.record_partial("review_verification", "attempted")
            try:
                accepted = await asyncio.wait_for(self.model.verify_corrections(candidates), timeout=10)
                await self.pipeline.record_partial("review_verification", "succeeded")
            except Exception as error:
                await self.pipeline.record_partial("review_verification", "failed", "timeout" if isinstance(error, TimeoutError) else "internal")
                logger.exception("onboarding correction verification failed; omitting examples")
        examples = select_examples(candidates, accepted)
        metrics = fluency_metrics(state["turns"])
        transcripts = [str(turn.get("transcript") or "").strip() for turn in state["turns"]]
        transcripts = [text for text in transcripts if text]
        raw = await self.model.compose_review({
            "transcripts": transcripts,
            "profile": state.get("profile") or {},
            "cefr": state.get("cefr"),
            "position": state.get("position"),
            "grammarExamples": examples["grammar"],
            "vocabularyExamples": examples["vocabulary"],
            "fluency": {
                key: metrics.get(key) for key in ("paceWpm", "longPauses", "fillers", "longestStretchSec")
            },
        })
        seconds = float(state.get("seconds") or 0)
        timings = bool(metrics.get("hasWords"))
        grammar = apply_skill(raw["grammar"], seconds, "grammar")
        vocabulary = apply_skill(raw["vocabulary"], seconds, "vocabulary")
        fluency = apply_skill(raw["fluency"], seconds, "fluency", timings=timings)
        grammar["examples"] = explained_examples(examples["grammar"], raw["grammarExplanations"])
        vocabulary["examples"] = explained_examples(examples["vocabulary"], raw["vocabularyExplanations"])
        vocabulary["suggestions"] = select_vocabulary_suggestions(
            raw.get("vocabularySuggestions"), transcripts, candidates, examples["vocabulary"],
        )
        fluency.update({
            "paceWpm": metrics["paceWpm"],
            "longPauses": metrics["longPauses"],
            "fillers": metrics["fillers"],
            "longestStretchSec": metrics["longestStretchSec"],
        })
        return {
            "levelText": raw["levelText"],
            "shade": normalize_shade(raw.get("shade")),
            "grammar": grammar,
            "vocabulary": vocabulary,
            "fluency": fluency,
        }

    async def _remember(self, session_id: str, state: dict) -> None:
        for item in state["turns"]:
            reply = item.get("reply")
            if item.get("remembered") or not reply or not str(item.get("transcript") or "").strip():
                continue
            await self.pipeline.dialogue.record_turn(session_id, item["transcript"], reply)
            item["remembered"] = True

    async def _current(self, session_id: str, run_id: str) -> dict | None:
        state = await self.store.get(session_id)
        return state if state and state["runId"] == run_id else None

    @staticmethod
    def _result(
        state: dict | None, text: str = "", audio: TtsAudio | None = None,
        turn: dict | None = None, analytics: dict | None = None,
    ) -> PipelineResult:
        payload = public_state(state) if state else {"status": "ignored"}
        facts = _public_analytics(turn, analytics)
        if facts:
            payload = {**payload, "analytics": facts}
        return PipelineResult(
            audio=audio, transcript=turn["transcript"] if turn else "", reply_text=text,
            timings_ms=(current_job_timings() or {}).copy(), corrections=_shown_corrections(turn),
            onboarding=payload,
        )


def _voice_analytics(
    *,
    voice_index: int,
    telegram_duration: float,
    recognized_duration: float,
    recognized: bool,
    failure_reason: str | None = None,
    milestones: list[int] | None = None,
    speech_before: float | None = None,
    speech_after: float | None = None,
) -> dict:
    telegram = telegram_duration if math.isfinite(telegram_duration) and telegram_duration > 0 else 0.0
    recognized_seconds = recognized_duration if math.isfinite(recognized_duration) and recognized_duration > 0 else 0.0
    return {
        "voiceIndex": voice_index,
        "telegramDurationSec": telegram,
        "recognizedDurationSec": recognized_seconds,
        "recognized": recognized,
        "failureReason": failure_reason,
        "milestones": list(milestones or []),
        "speechBeforeSec": speech_before,
        "speechAfterSec": speech_after,
    }


def _public_analytics(turn: dict | None, update: dict | None) -> dict | None:
    """Voice facts for the product funnel. Transcripts and profile text stay out."""
    facts: dict[str, Any] = {}
    stored = (turn or {}).get("_analytics")
    if isinstance(stored, dict):
        facts.update(stored)
    if update:
        facts.update(update)
    if not facts:
        return None
    milestones = [mark for mark in facts.get("milestones") or [] if mark in SPEECH_MILESTONES]
    score = facts.get("overallScore")
    return {
        "voiceIndex": int(facts.get("voiceIndex") or 0),
        "telegramDurationSec": float(facts.get("telegramDurationSec") or 0),
        "recognizedDurationSec": float(facts.get("recognizedDurationSec") or 0),
        "recognized": bool(facts.get("recognized")),
        "failureReason": facts.get("failureReason") if isinstance(facts.get("failureReason"), str) else None,
        "milestones": milestones,
        "completedNow": bool(facts.get("completedNow")),
        "assessmentFailed": bool(facts.get("assessmentFailed")),
        "cefr": facts.get("cefr") if isinstance(facts.get("cefr"), str) else None,
        "overallScore": score if isinstance(score, int) else None,
        "scoreAvailable": bool(facts.get("scoreAvailable")),
        "speechBeforeSec": facts.get("speechBeforeSec"),
        "speechAfterSec": facts.get("speechAfterSec"),
    }
