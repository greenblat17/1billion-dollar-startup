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
from app.memory import MemoryStore
from app.pipeline import CLARIFY_TEXT, ClipPipeline, PipelineResult

logger = logging.getLogger(__name__)

FIRST_QUESTION = (
    "Alright, now you can actually hear me. And yep, this is actually my voice. "
    "I’d really love to get to know you a little. So, tell me about yourself — "
    "whatever you’d like me to know."
)
RETRY_TEXT = "I couldn't prepare your result. Please try again."
SPEECH_LIMIT_SECONDS = 120
VOICE_LIMIT = 10
PROFILE_FIELDS = ("work", "leisure", "goal")


class OnboardingStore:
    """One current attempt per chat, independent of the expiring dialogue history.

    The service owns the per-chat lock; memory and Redis use the same JSON shape.
    Production runs one AI worker, as do the existing dialogue stores.
    """

    def __init__(self, redis: Redis | None = None) -> None:
        self._redis = redis
        self._memory: dict[str, dict] = {}
        self._locks: dict[str, asyncio.Lock] = {}

    def lock(self, session_id: str) -> asyncio.Lock:
        return self._locks.setdefault(session_id, asyncio.Lock())

    async def get(self, session_id: str) -> dict | None:
        if self._redis is not None:
            raw = await self._redis.get(f"onboarding:{session_id}")
            return json.loads(raw) if raw else None
        return deepcopy(self._memory.get(session_id))

    async def save(self, session_id: str, state: dict) -> None:
        if self._redis is not None:
            await self._redis.set(f"onboarding:{session_id}", json.dumps(state))
        else:
            self._memory[session_id] = deepcopy(state)

    async def aclose(self) -> None:
        if self._redis is not None:
            await self._redis.aclose()


def public_state(state: dict) -> dict:
    return {
        **{key: state.get(key) for key in ("runId", "status", "seconds", "cefr", "resultText")},
        "retryAvailable": state["status"] == "pending" or (state["status"] == "active" and bool(state["turns"])),
        "react": state["status"] == "active" and not state.get("voiceArrived") and not state["turns"],
    }


def _attempt(receipts: list[str] | None = None) -> dict:
    return {
        "runId": uuid4().hex,
        "status": "waiting",
        "seconds": 0.0,
        "turns": [],
        "profile": {},
        "cefr": None,
        "resultText": None,
        "question": FIRST_QUESTION,
        "receipts": receipts or [],
        "continued": False,
    }


def _filled(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())


def next_ask(profile: dict) -> str:
    for key in PROFILE_FIELDS:
        if not _filled(profile.get(key)):
            return key
    return "followup"


def checklist_done(profile: dict) -> bool:
    return all(_filled(profile.get(key)) for key in PROFILE_FIELDS)


def should_close(state: dict) -> bool:
    """Close once the checklist exists and either the speech cap or the long-answer cap is met.

    Missing fields keep the introduction going past two minutes. Ten recognized answers
    stop early only when a level estimate exists too.
    """
    if not checklist_done(state.get("profile") or {}):
        return False
    if len(state["turns"]) >= VOICE_LIMIT and state.get("cefr"):
        return True
    return state["seconds"] >= SPEECH_LIMIT_SECONDS


class OnboardingService:
    def __init__(
        self,
        store: OnboardingStore,
        pipeline: ClipPipeline,
        model: Any,
        memory: MemoryStore | None = None,
    ) -> None:
        self.store = store
        self.pipeline = pipeline
        self.model = model
        self.memory = memory or pipeline.memory or MemoryStore()
        if pipeline.memory is not self.memory:
            pipeline.bind_memory(self.memory)

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
            audio = await self.pipeline.tts.synthesize(FIRST_QUESTION)
            state["status"] = "active"
            await self.store.save(session_id, state)
            return self._result(state, FIRST_QUESTION, audio)
        if action == "retry":
            if state["status"] == "completed":
                question = state.get("question") or ""
                turn = state["turns"][-1] if state["turns"] else None
                audio = await self.pipeline.tts.synthesize(question) if question else None
                return self._result(state, question, audio, turn)
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
                state["continueQuestion"] = await self.model.continue_question(state["profile"])
                await self.store.save(session_id, state)
            question = state["continueQuestion"]
            audio = await self.pipeline.tts.synthesize(question)
            # Old chats can still tap this button. New attempts never send it.
            if not state["continued"]:
                await self.pipeline.dialogue.record_turn(session_id, "Let's continue our conversation.", question)
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
                await self._seed_memory(session_id, state, only_if_missing=True)
                return await self.pipeline.run(session_id, audio, content_type, filename)
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
                    "seconds": seconds, "corrections": None, "analysis": None, "delivered": False,
                }
                state["turns"].append(turn)
                state["seconds"] += seconds
                await self.store.save(session_id, state)
            return await self._advance_turn(session_id, state, turn)

    async def _advance_turn(self, session_id: str, state: dict, turn: dict) -> PipelineResult:
        if turn["corrections"] is None:
            notes = await self.pipeline.llm.complete_notes(turn["transcript"])
            turn["corrections"] = [note.to_json() for note in notes[:1]]
            await self.store.save(session_id, state)
        if turn["analysis"] is None:
            state["ask"] = next_ask(state.get("profile") or {})
            try:
                turn["analysis"] = await self.model.assess(state)
            except Exception:
                logger.exception("onboarding assessment failed session=%s", session_id)
                # A long answer may already be ready to close. Retry reuses it instead of asking again.
                if state["seconds"] >= SPEECH_LIMIT_SECONDS or len(state["turns"]) >= VOICE_LIMIT:
                    state["status"] = "pending"
                    await self.store.save(session_id, state)
                    return self._result(state, RETRY_TEXT, turn=turn)
                raise
            state["profile"] = turn["analysis"]["profile"]
            state["cefr"] = turn["analysis"]["cefr"]
            await self.store.save(session_id, state)
        assessment = turn["analysis"]
        state["profile"] = assessment["profile"]
        state["cefr"] = assessment["cefr"]
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
            state["cefr"] = assessment["cefr"]
            question = assessment["question"]
            audio = await self.pipeline.tts.synthesize(question)
            state["question"] = question
            turn["reply"] = question
            turn["delivered"] = True
            if not should_close(state):
                # The saved answer was not ready to close: keep asking the missing field.
                state["status"] = "active"
                await self.store.save(session_id, state)
                result = self._result(state, question, audio, turn)
                result.streak = await self.pipeline.record_completed_turn(session_id, turn["seconds"], len(question))
                return result
            state["resultText"] = None
            state["status"] = "completed"
            await self._remember(session_id, state)
            await self._seed_memory(session_id, state, only_if_missing=False)
            await self.store.save(session_id, state)
        except Exception:
            logger.exception("onboarding result failed session=%s", session_id)
            state["status"] = "pending"
            # Keep the accepted turns and pending status for an explicit retry without more speech.
            await self.store.save(session_id, state)
            return self._result(state, RETRY_TEXT, turn=turn)
        result = self._result(state, question, audio, turn)
        result.streak = await self.pipeline.record_completed_turn(session_id, turn["seconds"], len(question))
        return result

    async def _seed_memory(self, session_id: str, state: dict, *, only_if_missing: bool) -> None:
        name = await self.pipeline.metrics.chat_name(session_id)
        if only_if_missing:
            await self.memory.ensure_from_onboarding(session_id, state, name)
            return
        await self.memory.apply_onboarding(session_id, state, name)

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
    def _result(state: dict | None, text: str = "", audio: bytes | None = None, turn: dict | None = None) -> PipelineResult:
        return PipelineResult(
            audio=audio, transcript=turn["transcript"] if turn else "", reply_text=text,
            timings_ms={}, corrections=[Correction(**note) for note in (turn or {}).get("corrections") or []],
            onboarding=public_state(state) if state else {"status": "ignored"},
        )
