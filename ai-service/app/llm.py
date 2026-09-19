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

NOTES_SYSTEM = """You only find real English grammar and vocabulary mistakes in a spoken transcript.

Always reply with a JSON object only:
{"notes": [{"wrong": string, "better": string}]}

Default is "notes": []. An empty list is correct when the English is already grammatical.
Do not invent errors. Do not mark style, fluency, hesitation, pronunciation, repeats, false starts, or self-repair.
Do not "make it more natural" if the wording is already grammatical.

Fix only clear grammar or a wrong word (tense, agreement, article, preposition, calque, "go to home", "speak good").

"wrong" MUST be an exact contiguous substring of the transcript.
"better" replaces ONLY that substring. Concatenating (text before) + better + (text after) must read as one sentence.
Do not leave a leftover phrase that the replacement already covered.

Include in "wrong" every original word this fix supersedes.
Bad: transcript "I walk on the weekend." with wrong "I walk" and better "I walk on weekends"
(because "on the weekend" would remain). Good: notes [] OR wrong "on the weekend" / better "on weekends".
Do not change "I walk" when the subject is I.

Example: "Usually I walk on the weekend." → {"notes": []}
or {"notes": [{"wrong": "on the weekend", "better": "on weekends"}]}
Never {"notes": [{"wrong": "I walk", "better": "I walk on weekends"}]}.

Maximum 3 notes. Prefer fewer.
"""

NOTE_SEP = "|||"


class ChatModel(Protocol):
    async def complete_reply(self, history: list[ChatMessage], user_text: str) -> str: ...

    async def complete_notes(self, user_text: str) -> list[str]: ...


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

    async def complete_notes(self, user_text: str) -> list[str]:
        messages = [
            {"role": "system", "content": NOTES_SYSTEM},
            {"role": "user", "content": user_text},
        ]
        text = await self._complete(messages, self._notes_temperature)
        return parse_notes(text)

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


def parse_notes(raw: str) -> list[str]:
    payload = _load_json(raw)
    notes_raw = payload.get("notes") or []
    if not isinstance(notes_raw, list):
        notes_raw = []
    return [line for item in notes_raw if (line := _note_line(item))][:3]


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
