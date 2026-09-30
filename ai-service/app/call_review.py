"""Score a sealed call relative to the stored level. One call cannot jump the scale."""
from __future__ import annotations

import asyncio
import json
import logging
from typing import Any

from app.calls import CallStore
from app.onboarding import OnboardingStore
from app.onboarding_review import correction_candidates, fluency_metrics, select_examples
from app.onboarding_score import placed_level, step_score

logger = logging.getLogger(__name__)

CALL_REVIEW_SYSTEM = """You compare one finished English conversation with the learner's current level.
The current scores are the baseline. Judge only whether this conversation is a little weaker,
about the same, or a little stronger than that baseline.
Always reply with a JSON object only:
{"levelText": string, "overallMove": int, "grammar": {"text": string, "move": int},
 "vocabulary": {"text": string, "move": int}, "fluency": {"text": string, "move": int}}
Each move is an integer from -2 to 2. 0 means this conversation matches the current level.
Do not return a CEFR label or a 0-100 score. Do not invent a new level.
levelText is two short sentences about this conversation, relative to the current level.
Each skill text is one short sentence. Do not include a number or a CEFR letter in any sentence.
Use only the supplied examples for claims about mistakes. An empty list is not error-free speech.
Fluency text may mention pace, pauses, and fillers in words, not with a new number.
"""


def parse_call_moves(raw: str) -> dict[str, Any]:
    payload = json.loads(_strip_fence(raw))
    if not isinstance(payload, dict):
        raise ValueError("call review json was not an object")
    level = payload.get("levelText")
    if not isinstance(level, str) or not level.strip():
        raise ValueError("call review missing levelText")
    return {
        "levelText": " ".join(level.split()),
        "overallMove": _move(payload.get("overallMove")),
        "grammar": _skill_move(payload.get("grammar")),
        "vocabulary": _skill_move(payload.get("vocabulary")),
        "fluency": _skill_move(payload.get("fluency")),
    }


def apply_call_level(
    assessment: dict[str, Any],
    moves: dict[str, Any],
    examples: dict[str, list[dict[str, str]]],
    fluency: dict[str, Any],
) -> dict[str, Any]:
    """Step every shown score from the stored baseline and keep the same cell."""
    previous = assessment.get("overallScore")
    overall = step_score(previous if isinstance(previous, int) else None, moves["overallMove"])
    if overall is None:
        raise ValueError("call review requires a numeric baseline")
    placed = placed_level(overall)
    skills = {}
    for name in ("grammar", "vocabulary", "fluency"):
        prior = assessment.get(name)
        stepped = step_score(prior if isinstance(prior, int) else None, moves[name]["move"])
        skill = {
            "score": stepped if stepped is not None else prior,
            "text": moves[name]["text"],
            "examples": examples.get(name) or [],
        }
        skills[name] = skill
    skills["fluency"].update({
        "paceWpm": fluency.get("paceWpm"),
        "longPauses": fluency.get("longPauses"),
        "fillers": fluency.get("fillers"),
        "longestStretchSec": fluency.get("longestStretchSec"),
    })
    updated = {
        "runId": assessment.get("runId"),
        "cefr": placed["cefr"],
        "position": placed["position"],
        "shade": placed["shade"],
        "overallScore": placed["overallScore"],
        "nextBand": placed["nextBand"],
        "pointsToNext": placed["pointsToNext"],
        "grammar": skills["grammar"]["score"],
        "vocabulary": skills["vocabulary"]["score"],
        "fluency": skills["fluency"]["score"],
    }
    return {
        "assessment": updated,
        "public": {
            "levelText": moves["levelText"],
            "cefr": placed["cefr"],
            "overallScore": placed["overallScore"],
            "previousScore": previous,
            "nextBand": placed["nextBand"],
            "pointsToNext": placed["pointsToNext"],
            "grammar": {"score": skills["grammar"]["score"], "text": skills["grammar"]["text"], "examples": skills["grammar"]["examples"]},
            "vocabulary": {"score": skills["vocabulary"]["score"], "text": skills["vocabulary"]["text"], "examples": skills["vocabulary"]["examples"]},
            "fluency": {
                "score": skills["fluency"]["score"],
                "text": skills["fluency"]["text"],
                "paceWpm": skills["fluency"]["paceWpm"],
                "longPauses": skills["fluency"]["longPauses"],
                "fillers": skills["fluency"]["fillers"],
                "longestStretchSec": skills["fluency"]["longestStretchSec"],
            },
        },
    }


class CallReviews:
    def __init__(self, calls: CallStore, store: OnboardingStore, model: Any, streaks: Any) -> None:
        self.calls = calls
        self.store = store
        self.model = model
        self.streaks = streaks

    async def review(self, call_id: str) -> dict[str, Any]:
        call = await self.calls.get(call_id)
        if call is None or call.get("status") != "closed":
            raise KeyError(call_id)
        cached = call.get("review")
        if isinstance(cached, dict):
            return await self._finish(cached, call)
        assessment = await self.store.get_assessment(str(call["sessionId"]))
        if not isinstance(assessment, dict) or not isinstance(assessment.get("overallScore"), int):
            raise ValueError("numeric baseline required")
        try:
            built = await self._compose(call, assessment)
        except Exception:
            logger.exception("call review failed call=%s", call_id)
            return {"callId": call_id, "retry": True}
        if not await self.calls.save_review(call_id, built["public"]):
            raise KeyError(call_id)
        await self.store.save_assessment(str(call["sessionId"]), built["assessment"])
        await self._sync_proficiency(str(call["sessionId"]), built["assessment"])
        return await self._finish(built["public"], call)

    async def _compose(self, call: dict, assessment: dict) -> dict[str, Any]:
        candidates = correction_candidates(call.get("turns") or [])
        accepted: set[str] = set()
        if candidates:
            try:
                accepted = await asyncio.wait_for(self.model.verify_corrections(candidates), timeout=10)
            except Exception:
                logger.exception("call correction verification failed; omitting examples")
        examples = select_examples(candidates, accepted)
        metrics = fluency_metrics(call.get("turns") or [])
        transcripts = [str(turn.get("transcript") or "").strip() for turn in call.get("turns") or []]
        raw = await self.model.compose_call_review({
            "baseline": {
                "cefr": assessment.get("cefr"),
                "overallScore": assessment.get("overallScore"),
                "grammar": assessment.get("grammar"),
                "vocabulary": assessment.get("vocabulary"),
                "fluency": assessment.get("fluency"),
            },
            "transcripts": [text for text in transcripts if text],
            "grammarExamples": examples["grammar"],
            "vocabularyExamples": examples["vocabulary"],
            "fluency": {key: metrics.get(key) for key in ("paceWpm", "longPauses", "fillers", "longestStretchSec")},
        })
        return apply_call_level(assessment, raw, examples, metrics)

    async def _finish(self, review: dict, call: dict) -> dict:
        payload = _with_clock(review, call)
        payload["streak"] = await self._streak(str(call["sessionId"]))
        return payload

    async def _streak(self, session_id: str) -> int:
        try:
            shown = await self.streaks.shown(session_id)
        except Exception:
            logger.exception("call review streak failed session=%s", session_id)
            return 0
        return int(shown or 0)

    async def _sync_proficiency(self, session_id: str, assessment: dict) -> None:
        memory = await self.store.get_learner(session_id)
        if not isinstance(memory, dict):
            return
        proficiency = dict(memory.get("proficiency") or {})
        proficiency["overall_cefr"] = assessment.get("cefr")
        proficiency["position"] = assessment.get("position")
        proficiency["overall_shade"] = assessment.get("shade")
        proficiency["overall_score"] = assessment.get("overallScore")
        for skill in ("grammar", "vocabulary", "fluency"):
            current = dict(proficiency.get(skill) or {})
            current["score"] = assessment.get(skill)
            proficiency[skill] = current
        memory["proficiency"] = proficiency
        await self.store.save_learner(session_id, memory)


def _with_clock(review: dict, call: dict) -> dict:
    return {
        **review,
        "callId": call["id"],
        "retry": False,
        "todaySeconds": float(call.get("todaySeconds") or 0),
        "goalSeconds": float(call.get("goalSeconds") or 0),
    }


def _skill_move(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError("call review skill missing")
    text = value.get("text")
    if not isinstance(text, str) or not text.strip():
        raise ValueError("call review skill text missing")
    return {"text": " ".join(text.split()), "move": _move(value.get("move"))}


def _move(value: Any) -> int:
    try:
        move = int(value)
    except (TypeError, ValueError):
        return 0
    return max(-2, min(2, move))


def _strip_fence(raw: str) -> str:
    text = raw.strip()
    if not text.startswith("```"):
        return text
    lines = text.split("\n")[1:]
    if lines and lines[-1].strip() == "```":
        lines = lines[:-1]
    return "\n".join(lines)
