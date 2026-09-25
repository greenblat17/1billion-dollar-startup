from __future__ import annotations

import json
import time
from dataclasses import dataclass
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

NOTES_SYSTEM = """You mark English mistakes in a spoken transcript for an on-screen splice.

Always reply with a JSON object only:
{"notes": [{"wrong": string, "better": string, "kind": "grammar"|"word"|"natural"}]}

"wrong" is an exact contiguous substring of the transcript. It must be whole words, never a piece of a longer word.
"better" replaces only that substring. Prefix + better + suffix must read as one sentence.

"kind":
- "grammar": the words break English grammar (tense, agreement, articles, prepositions, word order, missing or extra words).
- "word": grammatical, but a word is the wrong one for the meaning (wrong collocation, false friend, wrong verb).
- "natural": grammatical and the words fit, but a native speaker would clearly not say it that way (a calque or unidiomatic phrase).
If a span has a grammar mistake, its kind is "grammar", even if it could also sound more natural.
Use "natural" only for phrasing a native speaker would not use. Do not mark wording that is already fine but could be fancier.

Return "notes": [] only when the transcript is already correct and natural. Do not skip broken grammar.
Do not mark fluency, hesitation, pronunciation, repeats, false starts, or self-repair.
Maximum 3 notes. Spans must not overlap. Two separate holes are two notes. Do not swallow correct words that sit between holes.

How wide to cut:

1. Already correct and natural → [].
Transcript: "I walked on weekends."
{"notes": []}
Transcript: "I think it's a good idea."
{"notes": []}

2. One wrong word; the rest of the sentence is fine → only that word.
Transcript: "You is my friend who is living in the city."
{"notes": [{"wrong": "You is", "better": "You are", "kind": "grammar"}]}

3. Short phrase (article/preposition/noun). Do not strike the whole sentence.
Transcript: "Usually I walk on the weekend."
{"notes": [{"wrong": "on the weekend", "better": "on weekends", "kind": "grammar"}]}
Never {"wrong": "I walk", "better": "I walk on weekends"}.

4. A missing word: expand "wrong" so the splice is a real sentence.
Transcript: "How I celebrated it?"
{"notes": [{"wrong": "How I celebrated it?", "better": "How did I celebrate it?", "kind": "grammar"}]}

5. An extra word: include a neighbor so "better" is not empty.
Transcript: "I think that is the useful feedback."
{"notes": [{"wrong": "the useful", "better": "useful", "kind": "grammar"}]}

6. Two holes with good words between them → two notes.
Transcript: "I go to home and you is kind."
{"notes": [{"wrong": "go to home", "better": "go home", "kind": "grammar"}, {"wrong": "you is", "better": "you are", "kind": "grammar"}]}

7. Wrong word for the meaning → only that phrase.
Transcript: "I made a lot of photos on the trip."
{"notes": [{"wrong": "made a lot of photos", "better": "took a lot of photos", "kind": "word"}]}

8. Grammatical but not how a native would say it → the short phrase only.
Transcript: "We went to the sea and it was very interesting for me."
{"notes": [{"wrong": "very interesting for me", "better": "really fun", "kind": "natural"}]}
"""

NOTE_SEP = "|||"
MAX_CORRECTIONS = 3
CORRECTION_KINDS = ("grammar", "word", "natural")
DEFAULT_KIND = "grammar"


@dataclass(frozen=True)
class Correction:
    wrong: str
    better: str
    kind: str = DEFAULT_KIND

    @property
    def note(self) -> str:
        return f"{self.wrong}{NOTE_SEP}{self.better}"

    def to_json(self) -> dict[str, str]:
        return {"wrong": self.wrong, "better": self.better, "kind": self.kind}


class ChatModel(Protocol):
    async def complete_reply(self, history: list[ChatMessage], user_text: str) -> str: ...

    async def complete_notes(self, user_text: str) -> list[Correction]: ...


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

    async def complete_notes(self, user_text: str) -> list[Correction]:
        messages = [
            {"role": "system", "content": NOTES_SYSTEM},
            {"role": "user", "content": user_text},
        ]
        text = await self._complete(messages, self._notes_temperature)
        return parse_corrections(text)

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


def parse_corrections(raw: str) -> list[Correction]:
    payload = _load_json(raw)
    notes_raw = payload.get("notes") or []
    if not isinstance(notes_raw, list):
        notes_raw = []
    corrections = [item for raw_item in notes_raw if (item := _correction(raw_item))]
    corrections.sort(key=lambda item: CORRECTION_KINDS.index(item.kind))
    return corrections[:MAX_CORRECTIONS]


def _correction(item: Any) -> Correction | None:
    if isinstance(item, dict):
        wrong = str(item.get("wrong") or "").strip()
        better = str(item.get("better") or "").strip()
        kind = str(item.get("kind") or "").strip().lower()
        if kind not in CORRECTION_KINDS:
            kind = DEFAULT_KIND
        return Correction(wrong, better, kind) if wrong and better else None
    text = str(item).strip()
    if NOTE_SEP not in text:
        return None
    wrong, _, better = text.partition(NOTE_SEP)
    wrong = wrong.strip()
    better = better.strip()
    return Correction(wrong, better) if wrong and better else None


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
