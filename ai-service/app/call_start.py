"""Prepare the first spoken turn of a Telegram practice call."""
from __future__ import annotations

import asyncio
import base64

from app.calls import CallStore
from app.dialogue import DialogueStore
from app.metrics_v2 import bind_metrics, reset_metrics
from app.speech import SessionSpeech
from app.characters import Characters, CharacterStore

KORA_H_OPENING_REQUEST = (
    "Let's start a conversation. Say hello first, then ask one easy question "
    "to find a topic I might enjoy."
)


class CallStarter:
    def __init__(self, calls: CallStore, model, personalization, dialogue: DialogueStore, speech: SessionSpeech,
                 characters: Characters | None = None) -> None:
        self.calls = calls
        self.model = model
        self.personalization = personalization
        self.dialogue = dialogue
        self.speech = speech
        self.characters = characters or Characters(CharacterStore())
        self._locks: dict[str, asyncio.Lock] = {}

    def lock(self, session_id: str) -> asyncio.Lock:
        return self._locks.setdefault(session_id, asyncio.Lock())

    async def start(self, session_id: str, first_name: str | None = None,
                    scenario: dict[str, str] | None = None,
                    reminder_question: str | None = None) -> dict:
        bound = bind_metrics(session_id)
        try:
            return await self._start_locked(session_id, first_name, scenario, reminder_question)
        finally:
            reset_metrics(bound)

    async def _start_locked(self, session_id: str, first_name: str | None = None,
                            scenario: dict[str, str] | None = None,
                            reminder_question: str | None = None) -> dict:
        async with self.lock(session_id):
            summary = await self.calls.open(session_id, scenario)
            call = await self.calls.get(summary["callId"])
            assert call is not None
            if call["turns"] or call.get("openingDelivered"):
                return {**summary, "status": "active"}
            variant = await self.characters.variant(session_id, scenario=call.get("scenario"))
            kora = variant is not None
            history_id = self.characters.history_id(session_id, variant) if variant else session_id
            history = await self.dialogue.history(history_id)
            question = call.get("openingDraft") if kora else call.get("openingQuestion")
            if not question:
                question = call.get("openingQuestion")
            if reminder_question:
                name = first_name.strip() if isinstance(first_name, str) else ""
                if not (1 <= len(name) <= 25 and len(name.split()) <= 3
                        and all(char.isalpha() or char in " '-" for char in name)):
                    name = ""
                question = f"Hi, {name}! {reminder_question}" if name else f"Hi! {reminder_question}"
                await self.calls.replace_opening(summary["callId"], question)
            elif not scenario and call.get("openingFromReminder"):
                await self.calls.clear_reminder_opening(summary["callId"])
                question = None
            if not question:
                context = await self.personalization.prepare(session_id, continuation=False)
                opening_request = KORA_H_OPENING_REQUEST if variant == "kora_h" else "Let's start a conversation."
                question = await self.characters.reply(history, opening_request, variant=variant,
                                                       profile_note=context) if kora else await self.model.start_call_question({
                    "personalContext": context,
                    "firstName": first_name,
                    "recentConversation": [
                        {"role": turn.role, "content": turn.content}
                        for turn in history[-8:]
                    ],
                    "scenario": call.get("scenario"),
                })
                await self.calls.save_opening(summary["callId"], question)
            audio = await self.characters.synthesize(question, history=history, user="Let's start a conversation.", speed=await self.speech.speed(session_id), variant=variant) if kora else await self.speech.synthesize(session_id, question)
            if kora:
                question = audio.spoken_text or question
                await self.calls.save_spoken_opening(summary["callId"], question)
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
                variant = await self.characters.variant(session_id)
                history_id = self.characters.history_id(session_id, variant) if variant else session_id
                await self.dialogue.record_turn(history_id, "Let's start a practice conversation.", question)
