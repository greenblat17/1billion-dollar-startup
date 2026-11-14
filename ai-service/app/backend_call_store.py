"""Legacy daily calls through atomic backend operations, preserving the Python call interface."""
import asyncio
from app.audit_artifacts import recorded_at
from app.foundation_client import BackendFailure, FoundationClient


_SCENARIO_UNSET = object()


class BackendCallStore:
    def __init__(self, backend: FoundationClient, *, speech_time_enabled=False):
        self.backend = backend
        self.speech_time_enabled = speech_time_enabled
        self._locks = {}

    def lock(self, session_id):
        return self._locks.setdefault(session_id, asyncio.Lock())

    async def _op(self, action, session_id=None, call_id=None, **args):
        try:
            return (await self.backend.legacy_call(action, session_id, call_id, args))['result']
        except BackendFailure as error:
            if error.code == 'call_not_found':
                raise KeyError(call_id) from None
            if error.status == 400:
                raise ValueError(error.code) from None
            raise

    async def open(self, session_id, scenario=_SCENARIO_UNSET):
        args = {'speechTimeEnabled': self.speech_time_enabled}
        if scenario is not _SCENARIO_UNSET:
            args['scenario'] = scenario
        return await self._op('open', session_id, **args)

    async def is_open_today(self, session_id):
        return await self._op('is_open_today', session_id)

    async def open_call_today(self, session_id):
        return await self._op('open_today', session_id)

    async def set_character(self, call_id, character):
        await self._op('set_character', call_id=call_id, character=character)

    async def summary(self, session_id):
        return await self._op('summary', session_id)

    async def recent_ids(self, session_id):
        return await self._op('recent_ids', session_id)

    async def append_turn(self, session_id, transcript, reply, corrections, seconds, words, *,
                          recording_seconds=None, duration_source='recording_legacy',
                          duration_fallback_reason=None, attempt_id=None):
        return await self._op('append', session_id, transcript=transcript, reply=reply,
                              corrections=[dict(item) for item in corrections if isinstance(item, dict)],
                              seconds=seconds, words=[dict(item) for item in words if isinstance(item, dict)],
                              recordingSeconds=recording_seconds, durationSource=duration_source,
                              durationFallbackReason=duration_fallback_reason, attemptId=attempt_id,
                              receivedAt=recorded_at())

    async def seal(self, session_id, reason='end_button'):
        return await self._op('seal', session_id, reason=reason)

    async def note_telegram_voice(self, session_id, call_id, message_id):
        return await self._op('note_voice', session_id, call_id, messageId=message_id)

    async def get(self, call_id):
        return await self._op('get', call_id=call_id)

    async def save_opening(self, call_id, question):
        await self._op('save_opening', call_id=call_id, question=question)

    async def replace_opening(self, call_id, question):
        await self._op('replace_opening', call_id=call_id, question=question)

    async def clear_reminder_opening(self, call_id):
        await self._op('clear_reminder_opening', call_id=call_id)

    async def save_spoken_opening(self, call_id, question):
        await self._op('save_spoken_opening', call_id=call_id, question=question)

    async def mark_opening_delivered(self, call_id):
        result = await self._op('mark_opening_delivered', call_id=call_id)
        return tuple(result) if result is not None else None

    async def save_review(self, call_id, review):
        return await self._op('save_review', call_id=call_id, review=review)

    async def prune_all(self):
        return await self._op('prune')

    async def aclose(self):
        pass
