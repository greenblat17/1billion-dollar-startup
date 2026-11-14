"""Legacy dialogue with database fencing; model computation never holds a database transaction."""
import asyncio
from uuid import uuid4
from app.audit_artifacts import recorded_at
from app.dialogue import ChatMessage
from app.foundation_client import FoundationClient


class BackendDialogueStore:
    def __init__(self, backend: FoundationClient, max_messages: int, ttl_seconds: int):
        self.backend, self.max_messages, self.ttl_seconds = backend, max_messages, ttl_seconds

    async def _op(self, action, session_id=None, **args):
        return await self.backend.legacy_dialogue(action, session_id, {
            'maxMessages': self.max_messages, 'ttlSeconds': self.ttl_seconds, **args})

    async def create(self, session_id=None):
        return (await self._op('create', (session_id or '').strip() or str(uuid4())))['sessionId']

    async def exists(self, session_id):
        return (await self._op('exists', session_id))['exists']

    @staticmethod
    def _messages(payload):
        return [ChatMessage(item['role'], item['content'], item['at']) for item in payload['messages']]

    async def history(self, session_id):
        return self._messages(await self._op('history', session_id))

    async def record_turn(self, session_id, user_text, reply):
        await self._op('record', session_id, turnId=uuid4().hex, userText=user_text, reply=reply, at=recorded_at())

    async def shared_free_history(self, session_id):
        return self._messages(await self._op('shared_history', session_id))

    async def record_shared_free_turn(self, session_id, personality, user_text, reply, turn_id):
        if personality not in {'speaky', 'kora_h'}:
            raise ValueError('unknown personality')
        await self._op('record_shared', session_id, personality=personality, turnId=turn_id,
                       userText=user_text, reply=reply, at=recorded_at())

    async def complete_turn(self, session_id, user_text, generate):
        claim = await self._op('claim', session_id)
        token = {key: claim[key] for key in ('fence', 'revision', 'generation')}
        try:
            reply = await generate(self._messages(claim), user_text)
            await self._op('record', session_id, turnId=uuid4().hex, userText=user_text,
                           reply=reply, at=recorded_at(), **token)
            return reply
        finally:
            try:
                await asyncio.shield(self._op('release', session_id, **token))
            except Exception:
                pass  # An unconfirmed release expires; it cannot release a later generation.

    async def prune_all(self):
        return (await self._op('prune'))['count']

    async def aclose(self):
        pass
