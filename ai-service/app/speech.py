from __future__ import annotations

from redis.asyncio import Redis

from app.tts import TextToSpeech, TtsAudio
from app.operational_metrics import telegram_stage

SPEED_CHOICES = (0.8, 0.9, 1.0)


class SpeechSpeedStore:
    def __init__(self, redis: Redis | None = None) -> None:
        self._redis = redis
        self._memory: dict[str, float] = {}

    async def get(self, session_id: str) -> float | None:
        if self._redis is None:
            return self._memory.get(session_id)
        raw = await self._redis.get(f"speech-speed:{session_id}")
        try:
            value = float(raw) if raw is not None else None
        except ValueError:
            return None
        return value if value in SPEED_CHOICES else None

    async def set(self, session_id: str, speed: float) -> None:
        if isinstance(speed, bool) or speed not in SPEED_CHOICES:
            raise ValueError("unsupported speech speed")
        if self._redis is None:
            self._memory[session_id] = speed
        else:
            await self._redis.set(f"speech-speed:{session_id}", str(speed))

    async def aclose(self) -> None:
        if self._redis is not None:
            await self._redis.aclose()


class SessionSpeech:
    def __init__(self, tts: TextToSpeech, speeds: SpeechSpeedStore | None = None, default_speed: float = 0.9) -> None:
        self.tts = tts
        self.speeds = speeds or SpeechSpeedStore()
        self.default_speed = default_speed

    async def speed(self, session_id: str) -> float:
        return await self.speeds.get(session_id) or self.default_speed

    @telegram_stage("tts", session_arg=True)
    async def synthesize(self, session_id: str, text: str, *, speed: float | None = None) -> TtsAudio:
        selected = speed if speed is not None else await self.speed(session_id)
        if selected == self.default_speed:
            return await self.tts.synthesize(text)
        return await self.tts.synthesize(text, speed=selected)
