from __future__ import annotations

import json
from typing import Any, Protocol

from openai import AsyncOpenAI

from app.retry import once_on_retryable

REVIEW_SYSTEM = """You score a spoken English conversation for an on-screen review.

Always reply with a JSON object only:
{"steps":[{"metric":"Grammar"|"Vocabulary","score":int,"lead":string,"bullets":[string],"examples":[{"original":string,"improved":string}],"tip":string}]}

Return exactly two steps: Grammar first, then Vocabulary.
"score" is 0-100. "lead" is one short sentence. "bullets" are 1-3 short labels.
"examples" are 1-2 pairs from the user's turns: original transcript span, improved English.
"tip" is one short coaching sentence.
Do not add Pronunciation, Fluency, or Speed. Do not use wrong|||better notes.
If the user spoke but grammar is already fine, still return Grammar with a high score and empty or tiny examples.
"""


class SessionReviewer(Protocol):
    async def review(self, turns: list[dict[str, str]]) -> dict[str, Any]: ...


class OpenAiSessionReviewer:
    def __init__(self, client: AsyncOpenAI, model: str, max_tokens: int = 800) -> None:
        self._client = client
        self._model = model
        self._max_tokens = max_tokens

    async def review(self, turns: list[dict[str, str]]) -> dict[str, Any]:
        transcript = "\n".join(
            f"{item.get('role', '')}: {item.get('text', '')}" for item in turns if item.get("text")
        )

        async def call() -> Any:
            return await self._client.chat.completions.create(
                model=self._model,
                messages=[
                    {"role": "system", "content": REVIEW_SYSTEM},
                    {"role": "user", "content": transcript},
                ],
                temperature=0.0,
                max_completion_tokens=self._max_tokens,
                response_format={"type": "json_object"},
            )

        response = await once_on_retryable(call)
        text = (response.choices[0].message.content or "").strip()
        if not text:
            raise RuntimeError("review llm returned empty reply")
        return parse_review(text)


def parse_review(raw: str) -> dict[str, Any]:
    text = raw.strip()
    if text.startswith("```"):
        lines = text.split("\n")
        lines = lines[1:]
        if lines and lines[-1].strip() == "```":
            lines = lines[:-1]
        text = "\n".join(lines)
    payload = json.loads(text)
    if not isinstance(payload, dict):
        raise RuntimeError("review json was not an object")
    steps_raw = payload.get("steps") or []
    if not isinstance(steps_raw, list):
        raise RuntimeError("review json missing steps")
    steps = [_step(item) for item in steps_raw]
    steps = [item for item in steps if item is not None]
    by_metric = {item["metric"]: item for item in steps}
    ordered = []
    for metric in ("Grammar", "Vocabulary"):
        step = by_metric.get(metric)
        if step is None:
            raise RuntimeError(f"review json missing {metric}")
        ordered.append(step)
    return {"steps": ordered}


def _step(item: Any) -> dict[str, Any] | None:
    if not isinstance(item, dict):
        return None
    metric = str(item.get("metric") or "").strip()
    if metric not in {"Grammar", "Vocabulary"}:
        return None
    examples = []
    for example in item.get("examples") or []:
        if not isinstance(example, dict):
            continue
        original = str(example.get("original") or "").strip()
        improved = str(example.get("improved") or "").strip()
        if original and improved:
            examples.append({"original": original, "improved": improved})
    bullets_raw = item.get("bullets") or []
    bullets = [str(bullet).strip() for bullet in bullets_raw if str(bullet).strip()][:3]
    score_raw = item.get("score")
    try:
        score = int(score_raw)
    except (TypeError, ValueError):
        score = 0
    score = max(0, min(100, score))
    lead = str(item.get("lead") or "").strip() or "Keep practicing this area."
    tip = str(item.get("tip") or "").strip() or "Try again in the next conversation."
    return {
        "metric": metric,
        "score": score,
        "lead": lead,
        "bullets": bullets,
        "examples": examples,
        "tip": tip,
    }
