"""Mode, personality, and history boundaries for spoken Telegram turns.

The mode owns state and constraints; the selected personality owns reply style and speech.
"""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from uuid import uuid4

from app.characters import Characters
from app.dialogue import ChatMessage, DialogueStore, shared_free_scope_id
from app.speech import SessionSpeech


class ConversationMode(str, Enum):
    ONBOARDING = "onboarding"
    FREE = "free"
    SITUATION = "situation"


class Personality(str, Enum):
    SPEAKY = "speaky"
    KORA_H = "kora_h"


@dataclass(frozen=True)
class ConversationRoute:
    mode: ConversationMode
    personality: Personality


@dataclass(frozen=True)
class HistoryContext:
    scope_id: str
    messages: list[ChatMessage]
    mode: ConversationMode
    legacy_personality: Personality = Personality.SPEAKY
    shared: bool = False


@dataclass(frozen=True)
class SpokenTurn:
    text: str
    tone: str | None = None


class ConversationRouting:
    def __init__(self, characters: Characters) -> None:
        self.characters = characters

    async def resolve(self, session_id: str, mode: ConversationMode) -> ConversationRoute:
        if not session_id.startswith("tg-"):
            return ConversationRoute(mode, Personality.SPEAKY)
        selected = await self.characters.store.get(session_id)
        if selected == "kora_h":
            if not self.characters.allowed(session_id):
                raise RuntimeError("Kora H is temporarily unavailable")
            return ConversationRoute(mode, Personality.KORA_H)
        return ConversationRoute(mode, Personality.SPEAKY)


class ConversationHistory:
    def __init__(self, dialogue: DialogueStore) -> None:
        self.dialogue = dialogue

    def scope_id(self, session_id: str, route: ConversationRoute) -> str:
        if self.uses_shared_route(session_id, route):
            return shared_free_scope_id(session_id)
        if route.mode != ConversationMode.FREE:
            return session_id
        return f"{session_id}:kora_h" if route.personality == Personality.KORA_H else session_id

    @staticmethod
    def uses_shared_route(session_id: str, route: ConversationRoute) -> bool:
        return session_id.startswith("tg-") and route.mode == ConversationMode.FREE

    async def load(self, session_id: str, route: ConversationRoute) -> HistoryContext:
        scope_id = self.scope_id(session_id, route)
        shared = self.uses_shared_route(session_id, route)
        if shared:
            messages = await self.dialogue.shared_free_history(session_id)
        else:
            messages = await self.dialogue.history(scope_id)
        return HistoryContext(scope_id, messages, route.mode, route.personality, shared)

    def target(self, session_id: str, route: ConversationRoute) -> HistoryContext:
        return HistoryContext(self.scope_id(session_id, route), [], route.mode, route.personality,
                              self.uses_shared_route(session_id, route))

    def is_shared(self, context: HistoryContext) -> bool:
        return context.shared

    @staticmethod
    def situation(context: HistoryContext, call: dict) -> HistoryContext:
        messages = [ChatMessage("assistant", call["openingQuestion"])] if call.get("openingQuestion") else []
        for turn in call.get("turns", []):
            messages.extend((ChatMessage("user", turn["transcript"]), ChatMessage("assistant", turn["reply"])))
        return HistoryContext(f"call:{call['id']}", messages, ConversationMode.SITUATION, context.legacy_personality)

    async def record(self, context: HistoryContext, user: str, reply: str,
                     *, turn_id: str | None = None) -> None:
        if self.is_shared(context):
            session_id = context.scope_id.removesuffix(":free:v1")
            await self.dialogue.record_shared_free_turn(
                session_id, context.legacy_personality.value, user, reply, turn_id or uuid4().hex,
            )
        else:
            await self.dialogue.record_turn(context.scope_id, user, reply)


class PersonalityRuntime:
    """Reply and speech adapters; neither adapter owns conversation state."""

    def __init__(self, model, speech: SessionSpeech, characters: Characters) -> None:
        self.model = model
        self.speech = speech
        self.characters = characters

    async def reply(self, route: ConversationRoute, history: HistoryContext, user: str,
                    profile_note: str | None = None, *, opening: bool = False,
                    closing: bool = False) -> SpokenTurn:
        if route.personality == Personality.KORA_H:
            if route.mode == ConversationMode.FREE:
                turn = await self.characters.reply_turn(history.messages, user, variant="kora_h",
                                                        profile_note=profile_note, opening=opening)
            else:
                mode = "onboarding_closing" if closing and route.mode == ConversationMode.ONBOARDING else route.mode.value
                turn = await self.characters.reply_mode_turn(mode, history.messages, user,
                                                             profile_note=profile_note, opening=opening)
            return SpokenTurn(turn.text, turn.tone)
        return SpokenTurn(await self.model.complete_reply(history.messages, user, profile_note))

    async def synthesize(self, session_id: str, route: ConversationRoute, turn: SpokenTurn,
                         *, history: HistoryContext | None = None, user: str = "Let's talk.",
                         guided: bool = True, speed: float | None = None):
        if route.personality == Personality.KORA_H:
            return await self.characters.synthesize(
                turn.text, history=history.messages if history else (), user=user,
                speed=speed if speed is not None else await self.speech.speed(session_id),
                guided=guided, variant="kora_h", tone=turn.tone,
            )
        return await self.speech.synthesize(session_id, turn.text, speed=speed)
