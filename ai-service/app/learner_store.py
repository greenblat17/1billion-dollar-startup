from __future__ import annotations

from typing import TYPE_CHECKING
if TYPE_CHECKING:
    from redis.asyncio import Redis

import asyncio
import json
import math
import re
import time
from copy import deepcopy


from app.review_score import overall_progress, normalize_shade

RAW_CONTENT_SECONDS = 7 * 24 * 60 * 60

def _expire_raw_turns(state: dict, now: float | None = None) -> dict:
    """Keep assessment/progress but remove raw turns whose age cannot meet retention."""
    cutoff = (now if now is not None else time.time()) - RAW_CONTENT_SECONDS
    original = state.get("turns") or []
    current = [turn for turn in original if isinstance(turn, dict)
               and isinstance(turn.get("receivedAt"), (int, float)) and turn["receivedAt"] > cutoff]
    state["turns"] = current
    # Questions, review examples and pending assessment can quote a user's answer.
    # Legacy states have no timestamp, so discard these when no dated turn remains.
    if not current:
        for key in ("continueQuestion", "review", "assessment", "resultText"):
            state.pop(key, None)
        state["question"] = ""
    return state


class LearnerStore:
    """Shared voice learner memory, goals and assessments; historical attempts are read-only."""

    def __init__(self, redis: Redis | None = None) -> None:
        self._redis = redis
        self._memory: dict[str, dict] = {}
        self._vocabulary_traces: dict[str, tuple[float, dict]] = {}
        self._goals: dict[str, int] = {}
        self._assessments: dict[str, dict] = {}
        self._learners: dict[str, dict] = {}
        self._locks: dict[str, asyncio.Lock] = {}

    def lock(self, session_id: str) -> asyncio.Lock:
        return self._locks.setdefault(session_id, asyncio.Lock())

    async def save_vocabulary_trace(self, flow: str, review_id: str,
                                    turns: list[dict], entries: list[dict]) -> None:
        if flow not in ("onboarding", "call") or not re.fullmatch(r"[0-9a-f]{32}", review_id):
            raise ValueError("invalid vocabulary trace identity")
        received = [turn.get("receivedAt") for turn in turns if isinstance(turn, dict)]
        received = [value for value in received if type(value) in (int, float) and math.isfinite(value)]
        if not received:
            return
        deadline = min(received) + RAW_CONTENT_SECONDS
        if deadline <= time.time():
            return
        key = f"vocabulary-review-debug:{flow}:{review_id}"
        payload = {"flow": flow, "reviewId": review_id, "entries": entries}
        if self._redis is not None:
            async with self._redis.pipeline(transaction=True) as pipe:
                pipe.set(key, json.dumps(payload, ensure_ascii=False))
                pipe.expireat(key, int(deadline))
                await pipe.execute()
        else:
            self._vocabulary_traces[key] = (deadline, deepcopy(payload))

    async def get_vocabulary_trace(self, flow: str, review_id: str) -> dict | None:
        if flow not in ("onboarding", "call") or not re.fullmatch(r"[0-9a-f]{32}", review_id):
            raise ValueError("invalid vocabulary trace identity")
        key = f"vocabulary-review-debug:{flow}:{review_id}"
        if self._redis is not None:
            raw = await self._redis.get(key)
            return json.loads(raw) if raw else None
        saved = self._vocabulary_traces.get(key)
        return deepcopy(saved[1]) if saved and saved[0] > time.time() else None

    async def get(self, session_id: str) -> dict | None:
        if self._redis is not None:
            raw = await self._redis.get(f"onboarding:{session_id}")
            if not raw:
                return None
            loaded = json.loads(raw)
            before = json.dumps(loaded, sort_keys=True)
            cleaned = _expire_raw_turns(loaded)
            if json.dumps(cleaned, sort_keys=True) != before:
                await self._redis.set(f"onboarding:{session_id}", json.dumps(cleaned))
            return cleaned
        state = deepcopy(self._memory.get(session_id))
        return _expire_raw_turns(state) if state else None

    async def prune_all(self) -> int:
        if self._redis is None:
            for session_id, state in self._memory.items():
                self._memory[session_id] = _expire_raw_turns(state)
            return len(self._memory)
        changed = 0
        async for key in self._redis.scan_iter(match="onboarding:*", count=100):
            async with self.lock(key.removeprefix("onboarding:")):
                raw = await self._redis.get(key)
                if not raw:
                    continue
                loaded = json.loads(raw)
                before = json.dumps(loaded, sort_keys=True)
                cleaned = _expire_raw_turns(loaded)
                if json.dumps(cleaned, sort_keys=True) != before:
                    await self._redis.set(key, json.dumps(cleaned))
                    changed += 1
        return changed

    async def save_assessment(self, session_id: str, assessment: dict) -> None:
        if self._redis is not None:
            await self._redis.set(f"assessment:{session_id}", json.dumps(assessment))
        else:
            self._assessments[session_id] = deepcopy(assessment)

    async def _read_assessment(self, session_id: str) -> dict | None:
        if self._redis is None:
            return deepcopy(self._assessments.get(session_id))
        raw = await self._redis.get(f"assessment:{session_id}")
        return json.loads(raw) if raw else None

    async def get_assessment(self, session_id: str) -> dict | None:
        if self._redis is not None:
            raw = await self._redis.get(f"assessment:{session_id}")
            saved = json.loads(raw) if raw else None
        else:
            saved = deepcopy(self._assessments.get(session_id))
        # Existing completed attempts are readable before the first snapshot write.
        return saved if saved is not None else assessment_summary(await self.get(session_id))

    async def get_learner(self, session_id: str) -> dict | None:
        if self._redis is not None:
            raw = await self._redis.get(f"learner:{session_id}")
            return json.loads(raw) if raw else None
        return deepcopy(self._learners.get(session_id))

    async def save_learner(self, session_id: str, memory: dict) -> None:
        if self._redis is not None:
            await self._redis.set(f"learner:{session_id}", json.dumps(memory))
        else:
            self._learners[session_id] = deepcopy(memory)

    async def save_learner_if_current(self, session_id: str, expected: dict,
                                      updated: dict) -> bool:
        """Avoid overwriting assessment/contact writes made during fact extraction."""
        if self._redis is None:
            if self._learners.get(session_id) != expected:
                return False
            self._learners[session_id] = deepcopy(updated)
            return True
        from redis.exceptions import WatchError
        key = f"learner:{session_id}"
        async with self._redis.pipeline(transaction=True) as pipe:
            try:
                await pipe.watch(key)
                raw = await pipe.get(key)
                if (json.loads(raw) if raw else None) != expected:
                    return False
                pipe.multi()
                pipe.set(key, json.dumps(updated))
                await pipe.execute()
                return True
            except WatchError:
                return False

    async def aclose(self) -> None:
        if self._redis is not None:
            await self._redis.aclose()

    async def set_goal(self, session_id: str, minutes: int) -> None:
        payload = json.dumps({"minutes": minutes})
        if self._redis is not None:
            await self._redis.set(f"practice-goal:{session_id}", payload)
        else:
            self._goals[session_id] = minutes

    async def get_goal(self, session_id: str) -> int | None:
        if self._redis is not None:
            raw = await self._redis.get(f"practice-goal:{session_id}")
            if not raw:
                return None
            minutes = json.loads(raw).get("minutes")
            return minutes if isinstance(minutes, int) else None
        return self._goals.get(session_id)


def assessment_summary(state: dict | None) -> dict | None:
    if not state or state.get("status") != "completed" or not isinstance(state.get("review"), dict):
        return None
    return {
        "runId": state.get("runId"),
        "cefr": state.get("cefr"),
        "position": state.get("position"),
        "shade": _stored_shade(state),
        **overall_progress(state.get("cefr"), state.get("position"), _stored_shade(state)),
        **{skill: (state["review"].get(skill) or {}).get("score")
           for skill in ("grammar", "vocabulary", "fluency")},
    }


def public_assessment(assessment: dict | None) -> dict | None:
    if not isinstance(assessment, dict):
        return None
    return {
        key: assessment.get(key)
        for key in ("cefr", "overallScore", "nextBand", "pointsToNext", "grammar", "vocabulary", "fluency")
    }


def _stored_shade(state: dict) -> str:
    review = state.get("review")
    if not isinstance(review, dict):
        return "0"
    return normalize_shade(review.get("shade"))

