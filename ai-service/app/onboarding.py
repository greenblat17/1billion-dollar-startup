from __future__ import annotations

import asyncio
import json
import math
import logging
from copy import deepcopy
from typing import Any
from uuid import uuid4

from redis.asyncio import Redis

from app.llm import Correction
from app.onboarding_review import closing_lines, correction_candidates, fluency_metrics, grounded_callback, select_examples
from app.onboarding_score import apply_skill, normalize_shade, overall_progress
from app.personalization import Personalization
from app.pipeline import CLARIFY_TEXT, ClipPipeline, PipelineResult
from app.tts import TtsAudio

logger = logging.getLogger(__name__)

FIRST_QUESTION = (
    "Alright, now you can actually hear me. And yep, this is actually my voice. "
    "I’d really love to get to know you a little. So, tell me about yourself — "
    "whatever you’d like me to know."
)
RETRY_TEXT = "I couldn't prepare your result. Please try again."
SPEECH_LIMIT_SECONDS = 120
PROFILE_FIELDS = ("work", "leisure", "goal")


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
        self._locks: dict[str, asyncio.Lock] = {}

    def lock(self, session_id: str) -> asyncio.Lock:
        return self._locks.setdefault(session_id, asyncio.Lock())

    async def get(self, session_id: str) -> dict | None:
        if self._redis is not None:
            raw = await self._redis.get(f"onboarding:{session_id}")
            return json.loads(raw) if raw else None
        return deepcopy(self._memory.get(session_id))

    async def save(self, session_id: str, state: dict) -> None:
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
            shown.append(Correction(str(note["wrong"]), str(note["better"]), str(note.get("kind") or "grammar")))
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


def _attempt(receipts: list[str] | None = None) -> dict:
    return {
        "runId": uuid4().hex,
        "status": "waiting",
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
        self._intro_audio: TtsAudio | None = None
        self._intro_lock = asyncio.Lock()

    async def intro_audio(self) -> TtsAudio:
        async with self._intro_lock:
            if self._intro_audio is None:
                audio = await self.pipeline.tts.synthesize(FIRST_QUESTION)
                if not audio.data:
                    raise ValueError("empty onboarding intro audio")
                self._intro_audio = audio
            return self._intro_audio

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
                state = _attempt()
                if known:
                    state["status"] = "exempt"
            if request_id not in state["receipts"]:
                if reset == "force" or (reset == "start" and state["status"] in {"waiting", "active", "pending"}):
                    if state["status"] == "completed":
                        await self.personalization.prepare(session_id)
                        await self.store.save(session_id, state)
                    state = _attempt(state["receipts"])
                state["receipts"] = (state["receipts"] + [request_id])[-256:]
                await self.store.save(session_id, state)
            return public_state(state)

    async def action(self, session_id: str, run_id: str, action: str, request_id: str = "") -> PipelineResult:
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
            audio = await self.intro_audio()
            state["status"] = "active"
            await self.store.save(session_id, state)
            return self._result(state, FIRST_QUESTION, audio)
        if action == "retry":
            if state["status"] == "completed":
                review = state.get("review") or {}
                spoken = review.get("spokenText") or state.get("question") or ""
                subtitle = review.get("closingText") or spoken
                turn = state["turns"][-1] if state["turns"] else None
                audio = await self.pipeline.tts.synthesize(spoken) if spoken else None
                return self._result(state, subtitle, audio, turn)
            if state["status"] == "pending":
                return await self._finish(session_id, state)
            if state["status"] == "active" and state["turns"]:
                turn = state["turns"][-1]
                if turn["delivered"]:
                    question = turn["reply"]
                    return self._result(state, question, await self.pipeline.tts.synthesize(question), turn)
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
            audio = await self.pipeline.tts.synthesize(question)
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
                stt = await self.pipeline.stt.transcribe(audio, content_type, filename, language=None)
                if stt.no_speech or not stt.text.strip():
                    return self._result(state, CLARIFY_TEXT, await self.pipeline.tts.synthesize(CLARIFY_TEXT))
                seconds = stt.duration_seconds if stt.duration_seconds > 0 else duration
                if not math.isfinite(seconds) or seconds <= 0:
                    raise ValueError("recording duration unavailable")
                turn = {
                    "requestId": request_id, "question": state["question"], "transcript": stt.text,
                    "seconds": seconds, "words": [dict(word) for word in stt.words],
                    "corrections": None, "analysis": None, "delivered": False,
                }
                state["turns"].append(turn)
                state["seconds"] += seconds
                await self.store.save(session_id, state)
            return await self._advance_turn(session_id, state, turn)

    async def _advance_turn(self, session_id: str, state: dict, turn: dict) -> PipelineResult:
        if turn["corrections"] is None:
            notes = await self.pipeline.llm.complete_notes(turn["transcript"])
            turn["corrections"] = [note.to_json() for note in notes]
            await self.store.save(session_id, state)
        if turn["analysis"] is None:
            state["ask"] = next_ask(state.get("profile") or {})
            try:
                turn["analysis"] = await self.model.assess(state)
            except Exception:
                logger.exception("onboarding assessment failed session=%s", session_id)
                # A long answer may already be ready to close. Retry reuses it instead of asking again.
                if state["seconds"] >= SPEECH_LIMIT_SECONDS:
                    state["status"] = "pending"
                    await self.store.save(session_id, state)
                    return self._result(state, RETRY_TEXT, turn=turn)
                raise
            state["profile"] = turn["analysis"]["profile"]
            _apply_level(state, turn["analysis"])
            await self.store.save(session_id, state)
        assessment = turn["analysis"]
        state["profile"] = assessment["profile"]
        _apply_level(state, assessment)
        if should_close(state):
            state["status"] = "pending"
            state["assessment"] = assessment
            await self.store.save(session_id, state)
            return await self._finish(session_id, state, turn)
        question = assessment["question"]
        reply_audio = await self.pipeline.tts.synthesize(question)
        state["question"] = question
        turn["reply"] = question
        turn["delivered"] = True
        await self.store.save(session_id, state)
        result = self._result(state, question, reply_audio, turn)
        result.streak = await self.pipeline.record_completed_turn(session_id, turn["seconds"], len(question))
        return result

    async def _finish(self, session_id: str, state: dict, turn: dict | None = None) -> PipelineResult:
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
                audio = await self.pipeline.tts.synthesize(question)
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
                state["review"] = await self._compose_review(state)
                await self.store.save(session_id, state)
            review = state["review"]
            spoken = review["spokenText"]
            subtitle = review["closingText"]
            audio = await self.pipeline.tts.synthesize(spoken)
            state["question"] = spoken
            turn["reply"] = spoken
            turn["delivered"] = True
            state["resultText"] = None
            state["status"] = "completed"
            await self._remember(session_id, state)
            await self.store.save(session_id, state)
        except Exception:
            logger.exception("onboarding result failed session=%s", session_id)
            state["status"] = "pending"
            # Keep the accepted turns and pending status for an explicit retry without more speech.
            await self.store.save(session_id, state)
            return self._result(state, RETRY_TEXT, turn=turn)
        await self.personalization.seed(session_id, state)
        result = self._result(state, subtitle, audio, turn)
        result.streak = await self.pipeline.record_completed_turn(session_id, turn["seconds"], len(spoken))
        return result

    async def progress_profile(self, session_id: str) -> dict:
        async with self.store.lock(session_id):
            return {
                "assessment": public_assessment(await self.store.get_assessment(session_id)),
                "dailyMinutes": await self.store.get_goal(session_id),
                "currentStreak": await self.pipeline.streaks.shown(session_id),
            }

    async def set_goal(self, session_id: str, minutes: int) -> dict:
        if minutes not in {5, 10, 15}:
            raise ValueError("invalid practice goal")
        async with self.store.lock(session_id):
            await self.store.set_goal(session_id, minutes)
        return {"minutes": minutes}

    async def _compose_review(self, state: dict) -> dict:
        candidates = correction_candidates(state["turns"])
        accepted: set[str] = set()
        if candidates:
            try:
                accepted = await asyncio.wait_for(self.model.verify_corrections(candidates), timeout=10)
            except Exception:
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
        callback = grounded_callback(raw.get("callback"), transcripts)
        subtitle, spoken = closing_lines(callback)
        seconds = float(state.get("seconds") or 0)
        timings = bool(metrics.get("hasWords"))
        grammar = apply_skill(raw["grammar"], seconds, "grammar")
        vocabulary = apply_skill(raw["vocabulary"], seconds, "vocabulary")
        fluency = apply_skill(raw["fluency"], seconds, "fluency", timings=timings)
        grammar["examples"] = examples["grammar"]
        vocabulary["examples"] = examples["vocabulary"]
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
            "closingText": subtitle,
            "spokenText": spoken,
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
    def _result(state: dict | None, text: str = "", audio: TtsAudio | None = None, turn: dict | None = None) -> PipelineResult:
        return PipelineResult(
            audio=audio, transcript=turn["transcript"] if turn else "", reply_text=text,
            timings_ms={}, corrections=_shown_corrections(turn),
            onboarding=public_state(state) if state else {"status": "ignored"},
        )
