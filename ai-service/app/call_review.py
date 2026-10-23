"""Score a sealed call relative to the stored level. One call cannot jump the scale."""
from __future__ import annotations

import asyncio
import json
import logging
import re
from typing import Any

from app.calls import CallStore
from app.correction_verification import verify_corrections_with_metrics
from app.onboarding import OnboardingStore
from app.onboarding_review import REVIEW_COPY_POLICY, correction_candidates, fluency_metrics, select_examples
from app.onboarding_score import placed_level, step_score
from app.metrics_v2 import bind_metrics, reset_metrics
from app.vocabulary_suggestions import discover_vocabulary_suggestions

logger = logging.getLogger(__name__)

CALL_REVIEW_SYSTEM = """You compare one finished English conversation with the learner's current level.
The current scores are the baseline. Judge only whether this conversation is a little weaker,
about the same, or a little stronger than that baseline.
Always reply with a JSON object only:
{"levelText": string, "recap": string, "overallMove": int,
 "grammar": {"text": string, "move": int},
 "vocabulary": {"text": string, "move": int}, "fluency": {"text": string, "move": int}}
Each move is an integer from -2 to 2. 0 means this conversation matches the current level.
Do not return a CEFR label or a 0-100 score. Do not invent a new level.
For Vocabulary, compare the learner's own repeated lexical range, complexity,
and precision with the stored baseline. Consistently basic words across
substantial speech can support a negative move from an advanced baseline,
even when those words are correct. One simple word or a narrow sample cannot.
Optional teaching alternatives are not words the learner demonstrated.
levelText is two or three short diagnostic sentences about the English demonstrated in this
conversation relative to the current level. Explain the result using one or two exact excerpts
from the transcripts when available. Explain what they show, not just what topics were discussed.
Only mention a limitation when the speech demonstrates it. If evidence is thin, say so.
recap is one or two sentences Speaky says to close the call. Say you enjoyed talking and name the specific things you discussed, using only the transcripts. No score, no CEFR level, no mistakes, and no advice.
The recap is a separate closing, not part of the diagnostic assessment.
""" + REVIEW_COPY_POLICY


def parse_call_moves(raw: str) -> dict[str, Any]:
    payload = json.loads(_strip_fence(raw))
    if not isinstance(payload, dict):
        raise ValueError("call review json was not an object")
    level = payload.get("levelText")
    if not isinstance(level, str) or not level.strip():
        raise ValueError("call review missing levelText")
    return {
        "levelText": " ".join(level.split()),
        "recap": _recap(payload.get("recap")),
        "overallMove": _move(payload.get("overallMove")),
        "grammar": _skill_move(payload.get("grammar")),
        "vocabulary": _skill_move(payload.get("vocabulary")),
        "fluency": _skill_move(payload.get("fluency")),
    }


def validate_call_review_response(raw: str) -> None:
    """Check the full contract when a provider ignores the response schema."""
    payload = json.loads(_strip_fence(raw))
    expected = {
        "levelText", "recap", "overallMove", "grammar", "vocabulary", "fluency",
    }
    if not isinstance(payload, dict) or set(payload) != expected:
        raise ValueError("invalid call review fields")
    parse_call_moves(raw)
    if type(payload["overallMove"]) is not int or not -2 <= payload["overallMove"] <= 2:
        raise ValueError("invalid overall move")
    for name in ("grammar", "vocabulary", "fluency"):
        skill = payload[name]
        if (not isinstance(skill, dict) or set(skill) != {"text", "move"}
                or type(skill["move"]) is not int or not -2 <= skill["move"] <= 2):
            raise ValueError(f"invalid {name} move")


def apply_call_level(
    assessment: dict[str, Any],
    moves: dict[str, Any],
    examples: dict[str, list[dict[str, str]]],
    fluency: dict[str, Any],
    vocabulary_suggestions: list[dict[str, str]] | None = None,
) -> dict[str, Any]:
    """Step every shown score from the stored baseline by at most two points."""
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
            "examples": (examples.get(name) or []) if name in ("grammar", "vocabulary") else [],
            "suggestions": (vocabulary_suggestions or []) if name == "vocabulary" else [],
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
            "recap": moves["recap"],
            "cefr": placed["cefr"],
            "overallScore": placed["overallScore"],
            "previousScore": previous,
            "nextBand": placed["nextBand"],
            "pointsToNext": placed["pointsToNext"],
            "grammar": {"score": skills["grammar"]["score"], "text": skills["grammar"]["text"], "examples": skills["grammar"]["examples"]},
            "vocabulary": {"score": skills["vocabulary"]["score"], "text": skills["vocabulary"]["text"], "examples": skills["vocabulary"]["examples"], "suggestions": skills["vocabulary"]["suggestions"]},
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
        bound = bind_metrics(str(call["sessionId"]))
        try:
            built = await self._compose(call, assessment)
        except Exception:
            logger.exception("call review failed call=%s", call_id)
            return {"callId": call_id, "retry": True}
        finally:
            reset_metrics(bound)
        if not await self.calls.save_review(call_id, built["public"]):
            raise KeyError(call_id)
        await self.store.save_assessment(str(call["sessionId"]), built["assessment"])
        await self._sync_proficiency(str(call["sessionId"]), built["assessment"])
        return await self._finish(built["public"], call)

    async def _compose(self, call: dict, assessment: dict) -> dict[str, Any]:
        turns = call.get("turns") or []
        candidates = correction_candidates(turns)
        enrichment = asyncio.create_task(discover_vocabulary_suggestions(
            self.model, turns, candidates, flow="call",
            trace_sink=lambda entries: self.store.save_vocabulary_trace(
                "call", call["id"], turns, entries)))
        accepted: set[str] = set()
        if candidates:
            try:
                accepted = await verify_corrections_with_metrics(self.model, candidates, flow="call")
            except Exception:
                logger.exception("call correction verification failed; omitting examples")
        examples = select_examples(candidates, accepted)
        metrics = fluency_metrics(call.get("turns") or [])
        transcripts = [str(turn.get("transcript") or "").strip() for turn in call.get("turns") or []]
        try:
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
        except BaseException:
            enrichment.cancel()
            await asyncio.gather(enrichment, return_exceptions=True)
            raise
        suggestions = await enrichment
        return apply_call_level(assessment, raw, examples, metrics, suggestions)

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
            score = assessment.get(skill)
            current["score"] = score
            if isinstance(score, int):
                level = placed_level(score)
                current.update(band=level["cefr"], position=level["position"], shade=level["shade"])
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


_SENTENCE_END = re.compile(r"(?<=[.!?])\s+")


def _recap(value: Any) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError("call review missing recap")
    text = " ".join(value.split())
    sentences = [part.strip() for part in _SENTENCE_END.split(text) if part.strip()]
    return " ".join(sentences[:2])


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
