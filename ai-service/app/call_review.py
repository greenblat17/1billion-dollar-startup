"""Score a sealed call relative to the stored level. One call cannot jump the scale."""
from __future__ import annotations

import asyncio
import json
import logging
from typing import Any

from app.calls import CallStore
from app.correction_verification import verify_corrections_with_metrics
from app.onboarding import OnboardingStore
from app.onboarding_review import REVIEW_COPY_POLICY, correction_candidates, fluency_metrics, select_examples
from app.onboarding_score import SCORE_TABLE, normalize_shade, placed_level, skill_score, step_score
from app.metrics_v2 import bind_metrics, reset_metrics
from app.vocabulary_suggestions import discover_vocabulary_suggestions

logger = logging.getLogger(__name__)
MIN_REVIEW_SPEECH_SECONDS = 30.0
REVIEW_PRESENTATION_VERSION = 2


def insufficient_review() -> dict[str, Any]:
    return {"reviewKind": "insufficient", "presentationVersion": REVIEW_PRESENTATION_VERSION}

CALL_REVIEW_SYSTEM = """You compare one finished English conversation with the learner's current level.
Existing numeric scores are the baseline. Judge whether this conversation is a little weaker,
about the same, or a little stronger than each available baseline.
Always reply with a JSON object only:
{"enoughEvidence": boolean, "levelText": string, "overallMove": int,
 "grammar": {"text": string, "move": int, "band": string|null, "position": string|null, "shade": string},
 "vocabulary": {"text": string, "move": int, "band": string|null, "position": string|null, "shade": string},
 "fluency": {"text": string, "move": int, "band": string|null, "position": string|null, "shade": string}}
Each move is an integer from -2 to 2. For scored skills, 0 means this conversation matches the current level.
Do not return a new overall CEFR label or a 0-100 score.
Set enoughEvidence=false ONLY when the transcripts clearly lack connected,
substantive learner English for useful personalized feedback. Isolated yes/no
answers, repetitions, and recognition noise may be insufficient even when the
recorded speech lasts 30 seconds or longer. When uncertain, set true and give
a cautious review based only on observed evidence. If false, fill the other
required fields conservatively; the code will discard their scores and text.
For each skill with a numeric baseline, set band=null, position=null, shade="0";
only its move changes the score. For a skill whose baseline is null, make its
first independent placement from THIS conversation: return A1-C1 band, low/mid/high
position, and a shade from --, -, 0, +, ++. Set move=0 for that skill.
Use band=null and position=null only if this conversation lacks enough connected
English to assess that skill. Do not copy the overall level into a missing skill.
For Vocabulary, compare the learner's own repeated lexical range, complexity,
and precision with the stored baseline. Consistently basic words across
substantial speech can support a negative move from an advanced baseline,
even when those words are correct. One simple word or a narrow sample cannot.
Optional teaching alternatives are not words the learner demonstrated.
levelText is two or three short diagnostic sentences about the English demonstrated in this
conversation relative to the current level. Explain the result using one or two exact excerpts
from the transcripts when available. Explain what they show, not just what topics were discussed.
Only mention a limitation when the speech demonstrates it. If evidence is thin, say so.
""" + REVIEW_COPY_POLICY


def parse_call_moves(raw: str) -> dict[str, Any]:
    payload = json.loads(_strip_fence(raw))
    if not isinstance(payload, dict):
        raise ValueError("call review json was not an object")
    level = payload.get("levelText")
    if not isinstance(level, str) or not level.strip():
        raise ValueError("call review missing levelText")
    return {
        "enoughEvidence": payload.get("enoughEvidence") is not False,
        "levelText": " ".join(level.split()),
        "overallMove": _move(payload.get("overallMove")),
        "grammar": _skill_move(payload.get("grammar")),
        "vocabulary": _skill_move(payload.get("vocabulary")),
        "fluency": _skill_move(payload.get("fluency")),
    }


def validate_call_review_response(raw: str) -> None:
    """Check the full contract when a provider ignores the response schema."""
    payload = json.loads(_strip_fence(raw))
    expected = {
        "enoughEvidence", "levelText", "overallMove", "grammar", "vocabulary", "fluency",
    }
    if not isinstance(payload, dict) or set(payload) != expected:
        raise ValueError("invalid call review fields")
    parse_call_moves(raw)
    if (type(payload["enoughEvidence"]) is not bool or type(payload["overallMove"]) is not int
            or not -2 <= payload["overallMove"] <= 2):
        raise ValueError("invalid overall move")
    for name in ("grammar", "vocabulary", "fluency"):
        skill = payload[name]
        if (not isinstance(skill, dict) or set(skill) != {"text", "move", "band", "position", "shade"}
                or type(skill["move"]) is not int or not -2 <= skill["move"] <= 2
                or skill["shade"] not in ("--", "-", "0", "+", "++")):
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
        stepped = step_score(prior, moves[name]["move"]) if isinstance(prior, int) else None
        if stepped is None and moves[name].get("band") is not None:
            stepped = skill_score(moves[name]["band"], moves[name]["position"], moves[name]["shade"])
        skill = {
            "score": stepped,
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
            "reviewKind": "full",
            "presentationVersion": REVIEW_PRESENTATION_VERSION,
            "levelText": moves["levelText"],
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
        if float(call.get("seconds") or 0) < MIN_REVIEW_SPEECH_SECONDS:
            short = insufficient_review()
            if not await self.calls.save_review(call_id, short):
                raise KeyError(call_id)
            return await self._finish(short, call)
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
        if built.get("assessment") is not None:
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
        if not raw["enoughEvidence"]:
            enrichment.cancel()
            await asyncio.gather(enrichment, return_exceptions=True)
            return {"public": insufficient_review()}
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


def _skill_move(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError("call review skill missing")
    text = value.get("text")
    if not isinstance(text, str) or not text.strip():
        raise ValueError("call review skill text missing")
    band = value.get("band")
    position = value.get("position")
    if (band is not None and (not isinstance(band, str) or band not in SCORE_TABLE
                              or position not in SCORE_TABLE[band])):
        raise ValueError("call review invalid initial skill placement")
    if band is None and position is not None:
        raise ValueError("call review position without band")
    if value.get("shade") not in ("--", "-", "0", "+", "++", None):
        raise ValueError("call review invalid initial skill shade")
    return {
        "text": " ".join(text.split()), "move": _move(value.get("move")),
        "band": band, "position": position,
        "shade": normalize_shade(value.get("shade")),
    }


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
