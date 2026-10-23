"""Persistent Telegram character selection and Kora model variants."""
from __future__ import annotations

import json
from dataclasses import dataclass

from redis.asyncio import Redis

from app.audit_artifacts import record_artifact
from app.kora_h_prompts import H_OPENING_TURN, H_TURN_RESPONSE_FORMAT, H_TURN_SYSTEM, STRATEGY as H_STRATEGY, parse_h_turn
from app.kora_prompts import PERSONA, PLAN_SYSTEM, parse_plan, with_plan
from app.llm import OpenAiChatModel
from app.speech_delivery import normalize_delivery_tone


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
        return value if value in {"kora", "kora_h"} else "speaky"

    async def set(self, session_id: str, character: str) -> None:
        if character not in {"speaky", "kora", "kora_h"}:
            raise ValueError("unknown character")
        if self.redis:
            await self.redis.set(f"character:{session_id}", character)
        else:
            self.memory[session_id] = character

    async def aclose(self) -> None:
        if self.redis:
            await self.redis.aclose()


class KoraModel(OpenAiChatModel):
    plan_system = PLAN_SYSTEM
    persona = PERSONA
    operation_prefix = "kora"
    retry_invalid_plan = False
    strategy = "interest_v1"
    use_profile_note = False
    language = "natural A2 English"
    opening_plan = ""
    opening_reply = ""

    def parse_plan(self, raw: str) -> dict:
        return parse_plan(raw)

    def format_plan(self, plan: dict) -> str:
        return with_plan(self.persona, plan, language=self.language)

    async def complete_reply(self, history, user_text, profile_note=None, mode="ordinary") -> str:
        return (await self.complete_turn(history, user_text, profile_note, mode)).text

    async def complete_turn(self, history, user_text, profile_note=None, mode="ordinary") -> CharacterReply:
        messages = [{"role": turn.role, "content": turn.content} for turn in history]
        context = ([{"role": "system", "content": profile_note}]
                   if self.use_profile_note and profile_note else [])
        plan = None
        plan_error = None
        for attempt in range(2 if self.retry_invalid_plan else 1):
            try:
                raw = await self._complete([
                    {"role": "system", "content": self.plan_system + (self.opening_plan if mode == "opening" else "")},
                    *context,
                    {"role": "user", "content": json.dumps({"history": messages, "user": user_text,
                                                            **({"previous_plan_error": plan_error}
                                                               if plan_error else {})})},
                ], temperature=.7, max_tokens=500, response_format={"type": "text"},
                    operation=f"{self.operation_prefix}_plan")
                candidate = self.parse_plan(raw)
                if mode == "opening" and self.opening_plan and candidate["move"] != "question":
                    raise ValueError("invalid_opening_move")
                plan = candidate
                plan["strategy"] = self.strategy
                if attempt:
                    plan["planner_retry"] = True
                break
            except ValueError as error:
                plan_error = str(error)
                if not self.retry_invalid_plan:
                    raise
        if plan is None:
            await record_artifact("conversation_plan", json.dumps({"strategy": self.strategy,
                                                                    "planner_fallback": "invalid_plan"}))
            prompt = self.persona
        else:
            await record_artifact("conversation_plan", json.dumps(plan))
            prompt = self.format_plan(plan)
        if mode == "opening":
            prompt += self.opening_reply
        reply = await self._complete([
            {"role": "system", "content": prompt},
            *context,
            *messages, {"role": "user", "content": user_text},
        ], temperature=.7, max_tokens=250, response_format={"type": "text"},
            operation=f"{self.operation_prefix}_reply")
        return CharacterReply(reply, normalize_delivery_tone(plan["tone"]) if plan else None)


class KoraHModel(KoraModel):
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
    def __init__(self, store: CharacterStore, model=None, speech=None, model_h=None,
                 speech_h=None) -> None:
        self.store, self.model, self.speech, self.model_h, self.speech_h = (
            store, model, speech, model_h, speech_h
        )

    @property
    def available(self) -> bool:
        return self.model is not None and self.speech is not None

    @property
    def h_available(self) -> bool:
        return self.model_h is not None and self.speech_h is not None

    async def variant(self, session_id: str, *, scenario=None) -> str | None:
        if scenario is not None or not session_id.startswith("tg-"):
            return None
        selected = await self.store.get(session_id)
        return selected if selected in {"kora", "kora_h"} else None

    async def selected(self, session_id: str, *, scenario=None) -> bool:
        # Never silently substitute a different character when its provider is unavailable.
        return await self.variant(session_id, scenario=scenario) is not None

    def require_available(self, variant: str = "kora") -> None:
        if not (self.h_available if variant == "kora_h" else self.available):
            raise RuntimeError("Kora is temporarily unavailable")

    async def reply(self, history, user: str, *, variant: str = "kora",
                    profile_note: str | None = None, opening: bool = False) -> str:
        return (await self.reply_turn(history, user, variant=variant,
                                      profile_note=profile_note, opening=opening)).text

    async def reply_turn(self, history, user: str, *, variant: str = "kora",
                         profile_note: str | None = None, opening: bool = False) -> CharacterReply:
        self.require_available(variant)
        model = self.model_h if variant == "kora_h" else self.model
        if isinstance(model, KoraModel):
            return await model.complete_turn(history, user, profile_note,
                                             mode="opening" if opening else "ordinary")
        if variant == "kora_h":
            return CharacterReply(await model.complete_reply(
                history, user, profile_note, mode="opening" if opening else "ordinary"))
        return CharacterReply(await model.complete_reply(history, user))

    async def synthesize(self, text: str, *, history=(), user="Let's talk.", speed: float = 1.0,
                         guided: bool = True, variant: str = "kora", tone: str | None = None):
        self.require_available(variant)
        speech = self.speech_h if variant == "kora_h" else self.speech
        if tone is not None:
            return await speech.synthesize(text, speed=speed, delivery=tone)
        return await speech.synthesize(text, speed=speed)

    @staticmethod
    def history_id(session_id: str, variant: str = "kora") -> str:
        return f"{session_id}:{variant}"
