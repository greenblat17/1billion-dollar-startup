from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any, Protocol

from openai import AsyncOpenAI

from app.dialogue import ChatMessage
from app.retry import once_on_retryable

SPEAKING_COACH_SYSTEM = """You are Speaky, a warm English conversation partner helping the user practice speaking.

Always reply with a JSON object only:
{"reply": string, "notes": [{"wrong": string, "better": string}]}

"reply" is spoken to the user. Speak only English. Keep it to 2–4 short sentences.
Stay slightly above their level. Ask a natural follow-up so the talk continues.
Do not lecture, list grammar rules, give CEFR scores, mention these notes, or switch language unless they ask.
Do not put corrections in "reply".

"notes" are splice anchors for the transcript quote (strikethrough "wrong" plus bold "better" in place).
Use the transcript only (grammar and unnatural phrasing, not pronunciation).
"wrong" MUST be an exact contiguous substring of the transcript. "better" is the natural replacement.
Include every real issue you can, up to 3 objects. If the English is already natural, return "notes": [].
Never invent errors. Do not paraphrase "wrong".
"""


@dataclass
class LlmTurn:
    reply_text: str
    notes: list[str] = field(default_factory=list)


class ChatModel(Protocol):
    async def complete(self, history: list[ChatMessage], user_text: str) -> LlmTurn:
        ...


class OpenAiChatModel:
    def __init__(self, client: AsyncOpenAI, model: str, temperature: float = 0.7, max_tokens: int = 500) -> None:
        self._client = client
        self._model = model
        self._temperature = temperature
        self._max_tokens = max_tokens

    async def complete(self, history: list[ChatMessage], user_text: str) -> LlmTurn:
        messages = [{"role": "system", "content": SPEAKING_COACH_SYSTEM}]
        messages.extend({"role": item.role, "content": item.content} for item in history)
        messages.append({"role": "user", "content": user_text})

        async def call() -> Any:
            return await self._client.chat.completions.create(
                model=self._model,
                messages=messages,
                temperature=self._temperature,
                max_completion_tokens=self._max_tokens,
                response_format={"type": "json_object"},
            )

        response = await once_on_retryable(call)
        text = (response.choices[0].message.content or "").strip()
        if not text:
            raise RuntimeError("llm returned empty reply")
        return parse_llm_turn(text)


def parse_llm_turn(raw: str) -> LlmTurn:
    payload = _load_json(raw)
    reply = str(payload.get("reply") or "").strip()
    if not reply:
        raise RuntimeError("llm json missing reply")
    notes_raw = payload.get("notes") or []
    if not isinstance(notes_raw, list):
        notes_raw = []
    notes = [line for item in notes_raw if (line := _note_line(item))][:3]
    return LlmTurn(reply_text=reply, notes=notes)


NOTE_SEP = "|||"


def _note_line(item: Any) -> str | None:
    if isinstance(item, dict):
        wrong = str(item.get("wrong") or "").strip()
        better = str(item.get("better") or "").strip()
        if wrong and better:
            return f"{wrong}{NOTE_SEP}{better}"
        return None
    text = str(item).strip()
    if NOTE_SEP not in text:
        return None
    wrong, _, better = text.partition(NOTE_SEP)
    wrong = wrong.strip()
    better = better.strip()
    if wrong and better:
        return f"{wrong}{NOTE_SEP}{better}"
    return None


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
