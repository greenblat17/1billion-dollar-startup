"""Legacy voice state adapter. Backend owns persistence and compare-and-swap."""
from __future__ import annotations

import asyncio

from app.foundation_client import FoundationClient
from app.learner_store import assessment_summary


class BackendLearnerStore:
    def __init__(self, backend: FoundationClient):
        self.backend = backend
        self._locks: dict[str, asyncio.Lock] = {}

    def lock(self, session_id: str) -> asyncio.Lock:
        return self._locks.setdefault(session_id, asyncio.Lock())

    async def _op(self, action, session_id=None, **args):
        return await self.backend.legacy_learner(action, session_id, args)

    async def get(self, session_id):
        return (await self._op('read', session_id))['onboarding']

    async def get_learner(self, session_id):
        return (await self._op('read', session_id))['memory']

    async def save_learner(self, session_id, memory):
        await self._op('save_memory', session_id, memory=memory, compare=False)

    async def save_learner_if_current(self, session_id, expected, updated):
        return (await self._op('save_memory', session_id, memory=updated, expected=expected, compare=True))['saved']

    async def save_assessment(self, session_id, assessment):
        await self._op('save_assessment', session_id, assessment=assessment)

    async def _read_assessment(self, session_id):
        return (await self._op('read', session_id))['assessment']

    async def get_assessment(self, session_id):
        state = await self._op('read', session_id)
        return state['assessment'] if state['assessment'] is not None else assessment_summary(state['onboarding'])

    async def set_goal(self, session_id, minutes):
        await self._op('set_goal', session_id, minutes=minutes)

    async def get_goal(self, session_id):
        return (await self._op('read', session_id))['minutes']

    async def save_vocabulary_trace(self, flow, review_id, turns, entries):
        received = [turn.get('receivedAt') for turn in turns if isinstance(turn, dict)]
        await self._op('trace.save', flow=flow, reviewId=review_id, entries=entries,
                       receivedAt=[value for value in received if type(value) in (int, float)])

    async def get_vocabulary_trace(self, flow, review_id):
        return (await self._op('trace.read', flow=flow, reviewId=review_id))['trace']

    async def prune_all(self):
        return (await self._op('prune'))['count']

    async def aclose(self):
        pass  # HTTP client lifetime belongs to the composition root.
