"""Explicit legacy preference, rollout and artifact operations owned by PostgreSQL backend."""
from app.foundation_client import FoundationClient
from app.speech import SPEED_CHOICES


class BackendSpeechSpeedStore:
    def __init__(self, backend: FoundationClient):
        self.backend = backend

    async def get(self, session_id):
        value = (await self.backend.voice_preferences('read', session_id, {}))['speed']
        return value if value in SPEED_CHOICES else None

    async def set(self, session_id, speed):
        if isinstance(speed, bool) or speed not in SPEED_CHOICES:
            raise ValueError('unsupported speech speed')
        await self.backend.voice_preferences('set_speed', session_id, {'speed': speed})

    async def aclose(self):
        pass


class BackendCharacterStore:
    def __init__(self, backend: FoundationClient, *, telegram_default='speaky'):
        if telegram_default not in {'speaky', 'kora_h'}:
            raise ValueError('unknown default character')
        self.backend, self.telegram_default = backend, telegram_default

    async def get(self, session_id):
        value = (await self.backend.voice_preferences('read', session_id, {}))['character']
        if value in {'speaky', 'kora_h'}:
            return value
        return self.telegram_default if value is None and session_id.startswith('tg-') else 'speaky'

    async def set(self, session_id, character):
        if character not in {'speaky', 'kora_h'}:
            raise ValueError('unknown character')
        await self.backend.voice_preferences('set_character', session_id, {'character': character})

    async def aclose(self):
        pass


class BackendSpeechTimeRollout:
    def __init__(self, backend: FoundationClient):
        self.backend = backend

    async def activation_day(self):
        return (await self.backend.voice_preferences('rollout', None, {}))['activationDay']

    async def method_for_day(self, day):
        return 'vad' if day >= await self.activation_day() else 'recording'

    async def aclose(self):
        pass


class BackendAuditArtifacts:
    def __init__(self, backend: FoundationClient):
        self.backend = backend

    async def record(self, attempt_id, received_at, field, value):
        if field not in {'transcript', 'reply', 'reply_draft', 'conversation_plan'}:
            return
        await self.backend.audit_artifact('record', attemptId=attempt_id, receivedAt=received_at,
                                          field=field, value=value[:16000])

    async def get(self, attempt_id):
        return (await self.backend.audit_artifact('read', attemptId=attempt_id))['artifact']

    async def prune_all(self):
        return (await self.backend.audit_artifact('prune'))['count']

    async def aclose(self):
        pass
