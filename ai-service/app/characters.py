"""Persistent Telegram character selection and the accepted two-stage Kora model."""
from __future__ import annotations

import json

from redis.asyncio import Redis

from app.audit_artifacts import record_artifact
from app.kora_prompts import PERSONA, PLAN_SYSTEM, parse_plan, with_plan
from app.llm import OpenAiChatModel


class CharacterStore:
    def __init__(self, redis: Redis | None = None) -> None:
        self.redis = redis
        self.memory: dict[str, str] = {}

    async def get(self, session_id: str) -> str:
        value = await self.redis.get(f"character:{session_id}") if self.redis else self.memory.get(session_id)
        return "kora" if value == "kora" else "speaky"

    async def set(self, session_id: str, character: str) -> None:
        if character not in {"speaky", "kora"}:
            raise ValueError("unknown character")
        if self.redis:
            await self.redis.set(f"character:{session_id}", character)
        else:
            self.memory[session_id] = character

    async def aclose(self) -> None:
        if self.redis:
            await self.redis.aclose()


class KoraModel(OpenAiChatModel):
    async def complete_reply(self, history, user_text, profile_note=None, mode="ordinary") -> str:
        messages = [{"role": turn.role, "content": turn.content} for turn in history]
        raw = await self._complete([
            {"role": "system", "content": PLAN_SYSTEM},
            {"role": "user", "content": json.dumps({"history": messages, "user": user_text})},
        ], temperature=.7, max_tokens=500, response_format={"type": "text"}, operation="kora_plan")
        plan = parse_plan(raw)
        await record_artifact("conversation_plan", json.dumps(plan))
        return await self._complete([
            {"role": "system", "content": with_plan(PERSONA, plan)},
            *messages, {"role": "user", "content": user_text},
        ], temperature=.7, max_tokens=250, response_format={"type": "text"}, operation="kora_reply")


class Characters:
    def __init__(self, store: CharacterStore, model=None, speech=None) -> None:
        self.store, self.model, self.speech = store, model, speech

    @property
    def available(self) -> bool:
        return self.model is not None and self.speech is not None

    async def selected(self, session_id: str, *, scenario=None) -> bool:
        # Never silently substitute a different character when its provider is unavailable.
        return scenario is None and session_id.startswith("tg-") and await self.store.get(session_id) == "kora"

    def require_available(self) -> None:
        if not self.available:
            raise RuntimeError("Kora is temporarily unavailable")

    async def reply(self, history, user: str) -> str:
        self.require_available()
        return await self.model.complete_reply(history, user)

    async def synthesize(self, text: str, *, history=(), user="Let's talk.", speed: float = 1.0,
                         guided: bool = True):
        self.require_available()
        return await self.speech.synthesize(text, history=history, user=user, speed=speed, guided=guided)

    @staticmethod
    def history_id(session_id: str) -> str:
        return f"{session_id}:kora"
