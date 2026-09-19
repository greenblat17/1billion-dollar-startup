from __future__ import annotations

import json
from typing import Any, Protocol

from openai import AsyncOpenAI

from app.dialogue import ChatMessage
from app.retry import once_on_retryable

REPLY_SYSTEM = """You are Speaky, a warm English conversation partner helping the user practice speaking.

Always reply with a JSON object only:
{"reply": string}

"reply" is spoken to the user. Speak only English. Keep it to 2–4 short sentences.
Stay slightly above their level. Ask a natural follow-up so the talk continues.
Do not lecture, list grammar rules, give CEFR scores, or switch language unless they ask.
Do not mention errors, corrections, or the transcript as a quote.
Do not put corrections in "reply".
"""

CORRECTION_SYSTEM = """You rewrite a spoken English transcript so it is grammatical.

Always reply with a JSON object only:
{"corrected": string}

"corrected" is the same utterance, same meaning, as many of the same words as possible.
Fix only what is ungrammatical or a clearly wrong word.
Do not make it more natural, shorter, more fluent, or more polite.
Do not drop fillers, repeats, or self-repair unless they break grammar.
Do not answer the speaker. Do not add a greeting or extra sentence.
If the transcript is already grammatical, "corrected" must be that transcript with the same wording.
"""

NOTE_SEP = "|||"


class ChatModel(Protocol):
    async def complete_reply(self, history: list[ChatMessage], user_text: str) -> str: ...

    async def complete_correction(self, user_text: str) -> str: ...


class OpenAiChatModel:
    def __init__(
        self,
        client: AsyncOpenAI,
        model: str,
        reply_temperature: float = 0.7,
        notes_temperature: float = 0.0,
        max_tokens: int = 500,
    ) -> None:
        self._client = client
        self._model = model
        self._reply_temperature = reply_temperature
        self._notes_temperature = notes_temperature
        self._max_tokens = max_tokens

    async def complete_reply(self, history: list[ChatMessage], user_text: str) -> str:
        messages = [{"role": "system", "content": REPLY_SYSTEM}]
        messages.extend({"role": item.role, "content": item.content} for item in history)
        messages.append({"role": "user", "content": user_text})
        text = await self._complete(messages, self._reply_temperature)
        return parse_reply(text)

    async def complete_correction(self, user_text: str) -> str:
        messages = [
            {"role": "system", "content": CORRECTION_SYSTEM},
            {"role": "user", "content": user_text},
        ]
        text = await self._complete(messages, self._notes_temperature)
        return parse_corrected(text)

    async def _complete(self, messages: list[dict[str, str]], temperature: float) -> str:
        async def call() -> Any:
            return await self._client.chat.completions.create(
                model=self._model,
                messages=messages,
                temperature=temperature,
                max_completion_tokens=self._max_tokens,
                response_format={"type": "json_object"},
            )

        response = await once_on_retryable(call)
        text = (response.choices[0].message.content or "").strip()
        if not text:
            raise RuntimeError("llm returned empty reply")
        return text


def parse_reply(raw: str) -> str:
    payload = _load_json(raw)
    reply = str(payload.get("reply") or "").strip()
    if not reply:
        raise RuntimeError("llm json missing reply")
    return reply


def parse_corrected(raw: str) -> str:
    payload = _load_json(raw)
    corrected = str(payload.get("corrected") or "").strip()
    if not corrected:
        raise RuntimeError("llm json missing corrected")
    return corrected


def _load_json(raw: str) -> dict[str, Any]:
    text = raw.strip()
    if text.startswith("```"):
        lines = text.split("\n")
        lines = lines[1:]
        if lines and lines[-1].strip() == "```":
            lines = lines[:-1]
        text = "\n".join(lines)
    data = json.loads(text)
    if not isinstance(data, dict):
        raise RuntimeError("llm json was not an object")
    return data
