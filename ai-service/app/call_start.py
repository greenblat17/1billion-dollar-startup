"""Prepare the first spoken turn of a Telegram practice call."""
from __future__ import annotations

import asyncio
import base64

from app.calls import CallStore
from app.dialogue import DialogueStore
from app.speech import SessionSpeech


class CallStarter:
    def __init__(self, calls: CallStore, model, personalization, dialogue: DialogueStore, speech: SessionSpeech) -> None:
        self.calls = calls
        self.model = model
        self.personalization = personalization
        self.dialogue = dialogue
        self.speech = speech
        self._locks: dict[str, asyncio.Lock] = {}

    async def start(self, session_id: str) -> dict:
        async with self._locks.setdefault(session_id, asyncio.Lock()):
            summary = await self.calls.open(session_id)
            call = await self.calls.get(summary["callId"])
            assert call is not None
            if call["turns"] or call.get("openingDelivered"):
                return {**summary, "status": "active"}
            question = call.get("openingQuestion")
            if not question:
                context = await self.personalization.prepare(session_id, continuation=True)
                history = await self.dialogue.history(session_id)
                question = await self.model.start_call_question({
                    "personalContext": context,
                    "recentConversation": [
                        {"role": turn.role, "content": turn.content}
                        for turn in history[-8:]
                    ],
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
            await self.dialogue.record_turn(session_id, "Let's start a practice conversation.", question)
