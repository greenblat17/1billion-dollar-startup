from __future__ import annotations

import json
import time
from typing import Any, Protocol

from openai import AsyncOpenAI

from app.dialogue import ChatMessage
from app.metrics import MetricsStore
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

NOTES_SYSTEM = """You mark ungrammatical English in a spoken transcript for an on-screen splice.

Always reply with a JSON object only:
{"notes": [{"wrong": string, "better": string}]}

"wrong" is an exact contiguous substring of the transcript. It must be whole words, never a piece of a longer word.
"better" replaces only that substring. Prefix + better + suffix must read as one sentence.

Return "notes": [] only when the transcript is already grammatical. Do not skip broken grammar.
Do not mark style, fluency, hesitation, pronunciation, repeats, false starts, or self-repair.
Do not make wording "more natural" if it is already grammatical.
Maximum 3 notes. Two separate holes are two notes. Do not swallow correct words that sit between holes.

How wide to cut (not a list of grammar types):

1. Already grammatical → [].
Transcript: "I walked on weekends."
{"notes": []}

2. One wrong word; the rest of the sentence is fine → only that word.
Transcript: "You is my friend who is living in the city."
{"notes": [{"wrong": "You is", "better": "You are"}]}

3. Short phrase (article/preposition/noun). Do not strike the whole sentence.
Transcript: "Usually I walk on the weekend."
{"notes": [{"wrong": "on the weekend", "better": "on weekends"}]}
Never {"wrong": "I walk", "better": "I walk on weekends"}.

4. A missing word: expand "wrong" so the splice is a real sentence.
Transcript: "How I celebrated it?"
{"notes": [{"wrong": "How I celebrated it?", "better": "How did I celebrate it?"}]}

5. An extra word: include a neighbor so "better" is not empty.
Transcript: "I think that is the useful feedback."
{"notes": [{"wrong": "the useful", "better": "useful"}]}

6. Two holes with good words between them → two notes.
Transcript: "I go to home and you is kind."
{"notes": [{"wrong": "go to home", "better": "go home"}, {"wrong": "you is", "better": "you are"}]}
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
        metrics: MetricsStore | None = None,
    ) -> None:
        self._client = client
        self._model = model
        self._reply_temperature = reply_temperature
        self._notes_temperature = notes_temperature
        self._max_tokens = max_tokens
        self._metrics = metrics

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

        started = time.perf_counter()
        response = await once_on_retryable(call)
        if self._metrics is not None:
            prompt_tokens, completion_tokens = read_usage(response)
            await self._metrics.record_llm(
                prompt_tokens,
                completion_tokens,
                int((time.perf_counter() - started) * 1000),
            )
        text = (response.choices[0].message.content or "").strip()
        if not text:
            raise RuntimeError("llm returned empty reply")
        return text


def read_usage(response: Any) -> tuple[int, int]:
    usage = getattr(response, "usage", None)
    if usage is None:
        return 0, 0
    if isinstance(usage, dict):
        prompt = usage.get("prompt_tokens")
        completion = usage.get("completion_tokens")
    else:
        prompt = getattr(usage, "prompt_tokens", None)
        completion = getattr(usage, "completion_tokens", None)
    return _nonneg_token(prompt), _nonneg_token(completion)


def _nonneg_token(value: Any) -> int:
    if isinstance(value, bool) or value is None:
        return 0
    try:
        number = int(value)
    except (TypeError, ValueError):
        return 0
    return number if number > 0 else 0


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
