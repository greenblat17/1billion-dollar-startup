from __future__ import annotations

import json
import time
from typing import Any, Protocol

from openai import AsyncOpenAI

from app.llm import read_usage
from app.metrics import MetricsStore
from app.retry import once_on_retryable

LESSON_SCORE_SYSTEM = """You score one spoken English lesson on an absolute 0-100 scale.

Always reply with a JSON object only:
{"grammar": int, "vocabulary": int, "fluency": int}

Each number is 0-100.
The scale is the speaker's overall English in this lesson, not how few mistakes they made.
Simple correct English stays low. Short correct present-simple sentences, with no mistakes, are about 25-35, not 90-100.
Connected speech that uses more complex grammar and precise words can score high even with one mistake.
Mistakes lower a score inside that ceiling. They do not set the ceiling.
"grammar" is how much grammar the speaker can control.
"vocabulary" is how wide and precise their words are.
"fluency" is how connected and complete their turns are. Fragments, one-word answers, and repetitions lower it.
Do not write a CEFR label. Score only this lesson.
"""

SCORE_KEYS = ("grammar", "vocabulary", "fluency")


class LessonScorer(Protocol):
    async def score(self, lesson: dict[str, Any]) -> dict[str, int]: ...


class OpenAiLessonScorer:
    def __init__(self, client: AsyncOpenAI, model: str, metrics: MetricsStore | None = None, max_tokens: int = 200) -> None:
        self._client = client
        self._model = model
        self._metrics = metrics
        self._max_tokens = max_tokens

    async def score(self, lesson: dict[str, Any]) -> dict[str, int]:
        messages = [
            {"role": "system", "content": LESSON_SCORE_SYSTEM},
            {"role": "user", "content": lesson_score_input(lesson)},
        ]

        async def call() -> Any:
            return await self._client.chat.completions.create(
                model=self._model,
                messages=messages,
                temperature=0.0,
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
            raise RuntimeError("lesson score llm returned empty reply")
        return parse_lesson_scores(text)


def lesson_score_input(lesson: dict[str, Any]) -> str:
    lines: list[str] = []
    for turn in lesson.get("turns") or []:
        if not isinstance(turn, dict):
            continue
        transcript = str(turn.get("transcript") or "").strip()
        if transcript:
            lines.append(f"User: {transcript}")
        for note in turn.get("corrections") or []:
            if not isinstance(note, dict):
                continue
            kind = str(note.get("kind") or "").strip()
            wrong = str(note.get("wrong") or "").strip()
            better = str(note.get("better") or "").strip()
            if wrong and better:
                lines.append(f"Note ({kind}): {wrong} -> {better}")
    return "\n".join(lines) if lines else "User: (no speech)"


def parse_lesson_scores(raw: str) -> dict[str, int]:
    text = raw.strip()
    if text.startswith("```"):
        lines = text.split("\n")
        lines = lines[1:]
        if lines and lines[-1].strip() == "```":
            lines = lines[:-1]
        text = "\n".join(lines)
    payload = json.loads(text)
    if not isinstance(payload, dict):
        raise RuntimeError("lesson score json was not an object")
    scores: dict[str, int] = {}
    for key in SCORE_KEYS:
        if key not in payload:
            raise RuntimeError(f"lesson score json missing {key}")
        scores[key] = _clamp_score(payload[key])
    return scores


def lesson_score_payload(scores: dict[str, int], lesson: dict[str, Any]) -> dict[str, Any]:
    corrections: list[dict[str, str]] = []
    for turn in lesson.get("turns") or []:
        if not isinstance(turn, dict):
            continue
        for note in turn.get("corrections") or []:
            if not isinstance(note, dict):
                continue
            wrong = str(note.get("wrong") or "").strip()
            better = str(note.get("better") or "").strip()
            kind = str(note.get("kind") or "").strip()
            if wrong and better and kind:
                corrections.append({"wrong": wrong, "better": better, "kind": kind})
    return {
        "grammar": scores["grammar"],
        "vocabulary": scores["vocabulary"],
        "fluency": scores["fluency"],
        "corrections": corrections,
    }


def _clamp_score(value: Any) -> int:
    try:
        score = int(value)
    except (TypeError, ValueError):
        score = 0
    return max(0, min(100, score))
