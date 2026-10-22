"""Persistent Telegram character selection and Kora model variants."""
from __future__ import annotations

import asyncio
import json
import logging

from redis.asyncio import Redis

from app.audit_artifacts import record_artifact
from app.kora_h_prompts import (H_PERSONA, H_PLAN_SYSTEM, H_OPENING_PLAN, H_OPENING_REPLY,
                                H_PERSPECTIVE_REVIEW, parse_h_plan, with_h_plan,
                                STRATEGY as H_STRATEGY)
from app.kora_prompts import PERSONA, PLAN_SYSTEM, parse_plan, with_plan
from app.llm import OpenAiChatModel

logger = logging.getLogger(__name__)


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

    async def review_reply(self, plan: dict | None, reply: str, messages: list[dict],
                           context: list[dict], user_text: str) -> str:
        return reply

    async def complete_reply(self, history, user_text, profile_note=None, mode="ordinary") -> str:
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
        return await self.review_reply(plan, reply, messages, context, user_text)


class KoraHModel(KoraModel):
    plan_system = H_PLAN_SYSTEM
    persona = H_PERSONA
    operation_prefix = "kora_h"
    retry_invalid_plan = True
    strategy = H_STRATEGY
    use_profile_note = True
    language = "English matched to the learner's verified level or current speech"
    opening_plan = H_OPENING_PLAN
    opening_reply = H_OPENING_REPLY

    def parse_plan(self, raw: str) -> dict:
        return parse_h_plan(raw)

    def format_plan(self, plan: dict) -> str:
        return with_h_plan(self.persona, plan, language=self.language)

    async def review_reply(self, plan: dict | None, reply: str, messages: list[dict],
                           context: list[dict], user_text: str) -> str:
        if plan is None or plan["move"] != "perspective":
            return reply
        review_unavailable = False
        try:
            raw = await self._complete([
                {"role": "system", "content": H_PERSPECTIVE_REVIEW},
                {"role": "user", "content": json.dumps({"history": messages, "user": user_text,
                                                        "reply": reply})},
            ], temperature=0, max_tokens=160, response_format={"type": "json_object"},
                operation="kora_h_perspective_review")
        except asyncio.CancelledError:
            raise
        except Exception as error:
            logger.warning("Kora H perspective review failed: %s", type(error).__name__)
            review_unavailable = True
            raw = ""
        try:
            verdict = json.loads(raw)
            accepted = verdict.get("substantive") is True
        except (ValueError, TypeError, AttributeError):
            accepted = False
        plan["perspective_review"] = ("unavailable" if review_unavailable else
                                      "accepted" if accepted else "rejected")
        if accepted:
            await record_artifact("conversation_plan", json.dumps(plan))
            return reply
        plan["move"] = "question"
        plan["contribution"] = plan["fallback_question"]
        plan["fallback_question"] = ""
        await record_artifact("conversation_plan", json.dumps(plan))
        return await self._complete([
            {"role": "system", "content": self.format_plan(plan)},
            *context, *messages, {"role": "user", "content": user_text},
        ], temperature=.7, max_tokens=250, response_format={"type": "text"},
            operation="kora_h_reply_fallback")


class Characters:
    def __init__(self, store: CharacterStore, model=None, speech=None, model_h=None) -> None:
        self.store, self.model, self.speech, self.model_h = store, model, speech, model_h

    @property
    def available(self) -> bool:
        return self.model is not None and self.speech is not None

    @property
    def h_available(self) -> bool:
        return self.model_h is not None and self.speech is not None

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
        self.require_available(variant)
        model = self.model_h if variant == "kora_h" else self.model
        if variant == "kora_h":
            return await model.complete_reply(history, user, profile_note,
                                              mode="opening" if opening else "ordinary")
        return await model.complete_reply(history, user)

    async def synthesize(self, text: str, *, history=(), user="Let's talk.", speed: float = 1.0,
                         guided: bool = True, variant: str = "kora"):
        self.require_available(variant)
        return await self.speech.synthesize(text, history=history, user=user, speed=speed,
                                            guided=guided and variant != "kora_h",
                                            warm=variant == "kora_h")

    @staticmethod
    def history_id(session_id: str, variant: str = "kora") -> str:
        return f"{session_id}:{variant}"
