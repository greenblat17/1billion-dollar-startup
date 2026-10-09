"""Prepare the first spoken turn of a Telegram practice call."""
from __future__ import annotations

import asyncio
import base64

from app.calls import CallStore
from app.dialogue import DialogueStore
from app.metrics_v2 import bind_metrics, reset_metrics
from app.speech import SessionSpeech


class CallStarter:
    def __init__(self, calls: CallStore, model, personalization, dialogue: DialogueStore, speech: SessionSpeech) -> None:
        self.calls = calls
        self.model = model
        self.personalization = personalization
        self.dialogue = dialogue
        self.speech = speech
        self._locks: dict[str, asyncio.Lock] = {}

    async def start(self, session_id: str, first_name: str | None = None,
                    scenario: dict[str, str] | None = None) -> dict:
        bound = bind_metrics(session_id)
        try:
            return await self._start_locked(session_id, first_name, scenario)
        finally:
            reset_metrics(bound)

    async def _start_locked(self, session_id: str, first_name: str | None = None,
                            scenario: dict[str, str] | None = None) -> dict:
        async with self._locks.setdefault(session_id, asyncio.Lock()):
            summary = await self.calls.open(session_id, scenario)
            call = await self.calls.get(summary["callId"])
            assert call is not None
            if call["turns"] or call.get("openingDelivered"):
                return {**summary, "status": "active"}
            question = call.get("openingQuestion")
            if not question:
                context = await self.personalization.prepare(session_id, continuation=False)
                history = await self.dialogue.history(session_id)
                question = await self.model.start_call_question({
                    "personalContext": context,
                    "firstName": first_name,
                    "recentConversation": [
                        {"role": turn.role, "content": turn.content}
                        for turn in history[-8:]
                    ],
                    "scenario": call.get("scenario"),
                })
                await self.calls.save_opening(summary["callId"], question)
            audio = await self.speech.synthesize(session_id, question)
            return {
                **summary, "status": "ready", "question": question,
                "audioBase64": base64.b64encode(audio.data).decode("ascii"),
                "audioContentType": audio.content_type,
            }

    async def delivered(self, call_id: str) -> None:
        opening = await self.calls.mark_opening_delivered(call_id)
        if opening is not None:
            session_id, question = opening
            call = await self.calls.get(call_id)
            if call is not None and not call.get("scenario"):
                await self.dialogue.record_turn(session_id, "Let's start a practice conversation.", question)
