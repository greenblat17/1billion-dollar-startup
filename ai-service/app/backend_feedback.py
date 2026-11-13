"""Feedback persistence with atomic backend ratings and pending-answer ownership."""
from app.foundation_client import FoundationClient
from app.first_call_feedback import CHOICES, MODES


class BackendFirstCallFeedback:
    family = 'first'

    def __init__(self, backend: FoundationClient):
        self.backend = backend

    async def _op(self, action, session_id=None, **args):
        return await self.backend.legacy_feedback(action, self.family, session_id, args)

    async def offer(self, session_id, call_id, username='', character='speaky'):
        return await self._op('offer', session_id, callId=call_id, username=username, character=character)

    async def rate(self, session_id, call_id, choice):
        if choice not in CHOICES:
            raise ValueError('invalid feedback choice')
        return await self._op('rate', session_id, callId=call_id, choice=choice)

    async def answer(self, session_id, text=None, call_id=None):
        return await self._op('answer', session_id, callId=call_id, text=text)

    async def list_rated(self, offset=0, limit=25, include_rated_at=False):
        return await self._op('list', offset=offset, limit=limit, includeRatedAt=include_rated_at)

    async def aclose(self):
        pass


class BackendModeFeedback(BackendFirstCallFeedback):
    family = 'mode'

    async def offer(self, session_id, instance_id, mode, username='', character='speaky'):
        if mode not in MODES:
            raise ValueError('invalid feedback mode')
        return await self._op('offer', session_id, callId=instance_id, mode=mode,
                              username=username, character=character)

    async def confirm(self, session_id, instance_id):
        return await self._op('confirm', session_id, callId=instance_id)

    async def release(self, session_id, instance_id):
        return await self._op('release', session_id, callId=instance_id)

    async def answer(self, session_id, text=None, instance_id=None):
        return await self._op('answer', session_id, callId=instance_id, text=text)

    async def migrate_legacy(self, session_id, get_call, recent_call_ids, *, force=False):
        # Backend joins retained calls and ratings in the same transaction.
        await self._op('migrate', session_id, force=force)
