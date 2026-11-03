"""Persistent Telegram character selection and Kora H replies."""
from __future__ import annotations

import json
import re
from dataclasses import dataclass

from redis.asyncio import Redis

from app.audit_artifacts import record_artifact
from app.kora_h_prompts import H_OPENING_TURN, H_TURN_RESPONSE_FORMAT, H_TURN_SYSTEM, STRATEGY as H_STRATEGY, parse_h_turn
from app.llm import OpenAiChatModel


@dataclass(frozen=True)
class CharacterReply:
    text: str
    tone: str | None = None


class CharacterStore:
    def __init__(self, redis: Redis | None = None) -> None:
        self.redis = redis
        self.memory: dict[str, str] = {}

    async def get(self, session_id: str) -> str:
        value = await self.redis.get(f"character:{session_id}") if self.redis else self.memory.get(session_id)
        # Older Kora selections are retired. They cannot enter the Kora H history.
        return value if value == "kora_h" else "speaky"

    async def set(self, session_id: str, character: str) -> None:
        if character not in {"speaky", "kora_h"}:
            raise ValueError("unknown character")
        if self.redis:
            await self.redis.set(f"character:{session_id}", character)
        else:
            self.memory[session_id] = character

    async def aclose(self) -> None:
        if self.redis:
            await self.redis.aclose()


class KoraHModel(OpenAiChatModel):
    async def complete_reply(self, history, user_text, profile_note=None, mode="ordinary") -> str:
        return (await self.complete_turn(history, user_text, profile_note, mode)).text

    async def complete_turn(self, history, user_text, profile_note=None, mode="ordinary") -> CharacterReply:
        messages = [{"role": turn.role, "content": turn.content} for turn in history]
        context = [{"role": "system", "content": profile_note}] if profile_note else []
        prompt = H_TURN_SYSTEM + (H_OPENING_TURN if mode == "opening" else "")
        error = None
        for attempt in range(2):
            raw = await self._complete([
                {"role": "system", "content": prompt},
                *context,
                *([{"role": "system", "content": f"The previous structured turn was invalid ({error}). "
                   "Return a complete turn that follows the response schema and the conversation rules."}]
                  if error else []),
                *messages,
                {"role": "user", "content": user_text},
            ], temperature=.7 if not attempt else .2, max_tokens=450, response_format=H_TURN_RESPONSE_FORMAT,
                operation="kora_h_turn")
            try:
                turn = parse_h_turn(raw, opening=mode == "opening")
            except ValueError as exc:
                error = str(exc)
                continue
            plan = turn["plan"]
            if attempt:
                plan["turn_retry"] = True
            await record_artifact("conversation_plan", json.dumps(plan))
            return CharacterReply(turn["reply"], plan["tone"])
        await record_artifact("conversation_plan", json.dumps({
            "strategy": H_STRATEGY, "turn_error": error,
        }))
        raise ValueError(f"invalid_kora_h_turn: {error}")


class Characters:
    def __init__(self, store: CharacterStore, model_h=None, speech_h=None, *,
                 enabled: bool = False, allowlist: str = "") -> None:
        self.store, self.model_h, self.speech_h = store, model_h, speech_h
        self.enabled = enabled
        self.public = allowlist.strip() == "*"
        values = [] if self.public else [value.strip() for value in allowlist.split(",") if value.strip()]
        if any(not value.isdecimal() or int(value) <= 0 for value in values):
            raise ValueError("KORA_H_ALLOWLIST must contain positive Telegram IDs")
        self.allowlist = frozenset(int(value) for value in values)

    @property
    def h_available(self) -> bool:
        return self.model_h is not None and self.speech_h is not None

    def allowed(self, session_id: str) -> bool:
        if not self.enabled or not self.h_available:
            return False
        match = (re.fullmatch(r"tg-([1-9][0-9]*)", session_id)
                 or re.fullmatch(r"tg-ChatId\(chatId=([1-9][0-9]*)\)", session_id))
        return match is not None and (self.public or int(match.group(1)) in self.allowlist)

    async def variant(self, session_id: str, *, scenario=None) -> str | None:
        if scenario is not None or not session_id.startswith("tg-"):
            return None
        selected = await self.store.get(session_id)
        return selected if selected == "kora_h" and self.allowed(session_id) else None

    async def selected(self, session_id: str, *, scenario=None) -> bool:
        # Never silently substitute a different character when its provider is unavailable.
        return await self.variant(session_id, scenario=scenario) is not None

    def require_available(self, variant: str = "kora_h") -> None:
        if variant != "kora_h" or not self.h_available:
            raise RuntimeError("Kora H is temporarily unavailable")

    async def reply(self, history, user: str, *, variant: str = "kora_h",
                    profile_note: str | None = None, opening: bool = False) -> str:
        return (await self.reply_turn(history, user, variant=variant,
                                      profile_note=profile_note, opening=opening)).text

    async def reply_turn(self, history, user: str, *, variant: str = "kora_h",
                         profile_note: str | None = None, opening: bool = False) -> CharacterReply:
        self.require_available(variant)
        if isinstance(self.model_h, KoraHModel):
            return await self.model_h.complete_turn(history, user, profile_note,
                                                    mode="opening" if opening else "ordinary")
        return CharacterReply(await self.model_h.complete_reply(
            history, user, profile_note, mode="opening" if opening else "ordinary"))

    async def synthesize(self, text: str, *, history=(), user="Let's talk.", speed: float = 1.0,
                         guided: bool = True, variant: str = "kora_h", tone: str | None = None):
        self.require_available(variant)
        if tone is not None:
            return await self.speech_h.synthesize(text, speed=speed, delivery=tone)
        return await self.speech_h.synthesize(text, speed=speed)

    @staticmethod
    def history_id(session_id: str, variant: str = "kora_h") -> str:
        return f"{session_id}:{variant}"
