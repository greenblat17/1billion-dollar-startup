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
        try:
            await asyncio.wait_for(self.store.put(key, bytes(audio)), 30)
            await self.command('voice.audio_stored', objectKey=key)
        except BaseException as error:
            log_event('audio_archive_failed', error=error, stage='store', direction=direction)
            try:
                await self.store.delete(key)
            except Exception as cleanup_error:
                log_event('audio_archive_failed', error=cleanup_error, stage='rollback')
            raise

    async def append(self, role, text, source=None):
        return await self.command('voice.append', sourceId=source or uuid4().hex, role=role,
                                  body=text[:12000], occurredAt=timestamp())

