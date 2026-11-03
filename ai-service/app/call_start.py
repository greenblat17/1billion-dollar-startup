"""Prepare the first spoken turn of a Telegram practice call."""
from __future__ import annotations

import asyncio
import base64
import json

from app.calls import CallStore
from app.dialogue import DialogueStore
from app.metrics_v2 import bind_metrics, reset_metrics
from app.speech import SessionSpeech
from app.characters import Characters, CharacterStore
from app.conversation import (ConversationHistory, ConversationMode, ConversationRoute, ConversationRouting,
                              HistoryContext, Personality, PersonalityRuntime, SpokenTurn)
from app.roleplay import ROLEPLAY_CONTEXT_PREFIX

KORA_H_OPENING_REQUEST = (
    "Start a new conversation with me. Give me a warm welcome and an easy check-in, "
    "then invite me into one topic. If I left a topic unfinished last time, "
    "ask what happened next."
)


class CallStarter:
    def __init__(self, calls: CallStore, model, personalization, dialogue: DialogueStore, speech: SessionSpeech,
                 characters: Characters | None = None, history: ConversationHistory | None = None) -> None:
        self.calls = calls
        self.model = model
        self.personalization = personalization
        self.dialogue = dialogue
        self.speech = speech
        self.characters = characters or Characters(CharacterStore())
        self.routing = ConversationRouting(self.characters)
        self.history = history or ConversationHistory(dialogue)
        self.personality = PersonalityRuntime(model, speech, self.characters)
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
            mode = ConversationMode.SITUATION if call.get("scenario") is not None else ConversationMode.FREE
            route = await self.routing.resolve(session_id, mode)
            await self.calls.set_character(summary["callId"], route.personality.value)
            kora = route.personality == Personality.KORA_H
            history_context = (self.history.situation(self.history.target(session_id, route), call)
                               if mode == ConversationMode.SITUATION else await self.history.load(session_id, route))
            history = history_context.messages
            question = call.get("openingDraft") if kora else call.get("openingQuestion")
            tone = None
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
                opening_request = ("Begin the assigned scene with a natural first line and one relevant question."
                                   if mode == ConversationMode.SITUATION else
                                   KORA_H_OPENING_REQUEST if kora else "Let's start a conversation.")
                # The placeholder from an earlier call is not a learner disclosure.
                opening_history = ([turn for turn in history
                                    if turn.content != "Let's start a practice conversation."][-8:]
                                   if kora else history)
                opening_context = HistoryContext(history_context.scope_id, opening_history,
                                                 history_context.mode, history_context.legacy_personality,
                                                 history_context.shared)
                role_context = (ROLEPLAY_CONTEXT_PREFIX + json.dumps(call["scenario"], ensure_ascii=False) +
                                "\nVerified learner context: " + (context or "")) if mode == ConversationMode.SITUATION else context
                turn = await self.personality.reply(route, opening_context, opening_request,
                                                    role_context, opening=True) if kora else None
                question = turn.text if kora else await self.model.start_call_question({
                    "personalContext": context,
                    "firstName": first_name,
                    "recentConversation": [
                        {"role": turn.role, "content": turn.content}
                        for turn in history[-8:]
                    ],
                    "scenario": call.get("scenario"),
                })
                tone = turn.tone if kora else None
                await self.calls.save_opening(summary["callId"], question)
            if kora:
                tone = "plain"
            audio = await self.personality.synthesize(session_id, route, SpokenTurn(question, tone),
                                                      history=history_context, user="Let's start a conversation.")
            if kora:
                question = audio.spoken_text or question
                await self.calls.save_spoken_opening(summary["callId"], question)
            return {
                **summary, "character": route.personality.value, "status": "ready", "question": question,
                "audioBase64": base64.b64encode(audio.data).decode("ascii"),
                "audioContentType": audio.content_type,
            }

    async def delivered(self, call_id: str) -> None:
        opening = await self.calls.mark_opening_delivered(call_id)
        if opening is None:
            call = await self.calls.get(call_id)
            if (call is not None and str(call["sessionId"]).startswith("tg-")
                    and call.get("openingDelivered") and call.get("openingQuestion")):
                opening = str(call["sessionId"]), str(call["openingQuestion"])
        if opening is not None:
            session_id, question = opening
            call = await self.calls.get(call_id)
            if call is not None and not call.get("scenario"):
                free_route = ConversationRoute(ConversationMode.FREE, Personality.SPEAKY)
                if self.history.uses_shared_route(session_id, free_route) and call.get("character") in {"speaky", "kora_h"}:
                    route = ConversationRoute(ConversationMode.FREE, Personality(call["character"]))
                else:
                    route = await self.routing.resolve(session_id, ConversationMode.FREE)
                context = self.history.target(session_id, route)
                await self.history.record(context, "Let's start a practice conversation.", question,
                                          turn_id=f"opening:{call_id}")
