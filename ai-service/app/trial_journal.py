"""Shared foundation journal and private audio archive, independent of voice provider."""
import asyncio
from datetime import datetime, timezone
from uuid import uuid4
from app.diagnostics import event as log_event


def timestamp():
    return datetime.now(timezone.utc).isoformat()


class TrialJournal:
    async def command(self, action, **args):
        return await self.backend.command(self.scope, uuid4().hex, action,
                                          dict(lessonId=self.lesson_id, callSessionId=self.episode, **args))

    async def record(self, direction, audio, metadata=None):
        if not audio:
            return
        sequence = self.audio_sequence[direction]
        self.audio_sequence[direction] += 1
        receipt = await self.command('voice.audio', direction=direction, sequence=sequence,
                                     metadata={'sampleRate': 16000 if direction == 'user' else 24000, 'encoding': 'pcm_s16le', **(metadata or {})})
        key = receipt['objectKey']
        stage = 'store'
        try:
            await asyncio.wait_for(self.store.put(key, bytes(audio)), 30)
            stage = 'confirm'
            await self.command('voice.audio_stored', objectKey=key)
        except BaseException as error:
            if isinstance(error, asyncio.CancelledError):
                log_event('audio_archive', stage=stage, direction=direction, outcome='cancelled')
            else:
                log_event('audio_archive_failed', error=error, stage=stage, direction=direction,
                          reason_code='audio_archive_unavailable')
            try:
                await self.store.delete(key)
            except Exception as cleanup_error:
                log_event('audio_archive_failed', error=cleanup_error, stage='rollback')
            raise

    async def append(self, role, text, source=None, metadata=None, occurred_at=None):
        return await self.command('voice.append', sourceId=source or uuid4().hex, role=role,
                                  body=text[:12000], occurredAt=occurred_at or timestamp(),
                                  **({'metadata': metadata, 'revision': 1} if metadata is not None else {}))

