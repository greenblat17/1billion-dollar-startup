from unittest.mock import AsyncMock

import pytest
from fakeredis import FakeAsyncRedis

from app.backend_voice_storage import BackendSpeechSpeedStore
from app.sessions import GREETING_VOICE_TEXT
from app.speech import SessionSpeech, SpeechSpeedStore
from tests.conftest import FakeTts, build_app


@pytest.mark.asyncio
async def test_speed_is_independent_per_chat_and_survives_store_recreation() -> None:
    redis = FakeAsyncRedis(decode_responses=True)
    speeds = SpeechSpeedStore(redis)
    assert await speeds.get("tg-1") is None
    await speeds.set("tg-1", 0.8)
    assert await SpeechSpeedStore(redis).get("tg-1") == 0.8
    assert await speeds.get("tg-2") is None
    with pytest.raises(ValueError):
        await speeds.set("tg-1", 0.85)
    await redis.aclose()


@pytest.mark.asyncio
async def test_legacy_speed_adapters_preserve_values_and_reject_invalid_writes() -> None:
    redis = FakeAsyncRedis(decode_responses=True)
    await redis.set('speech-speed:tg-old', '0.90')
    redis_store = SpeechSpeedStore(redis)
    backend = AsyncMock()
    backend.voice_preferences.return_value = {'speed': 0.9}
    pg_store = BackendSpeechSpeedStore(backend)

    for store in (redis_store, pg_store):
        assert await store.get('tg-old') == 0.9
        for speed in (True, 0.85, '0.9'):
            with pytest.raises(ValueError, match='unsupported speech speed'):
                await store.set('tg-old', speed)
        assert await store.get('tg-old') == 0.9

    assert [call.args[0] for call in backend.voice_preferences.await_args_list] == ['read', 'read']
    for _ in range(2):
        await redis_store.set('tg-old', 0.8)
        await pg_store.set('tg-old', 0.8)
    assert await redis.get('speech-speed:tg-old') == '0.8'
    assert [call.args for call in backend.voice_preferences.await_args_list[-2:]] == [
        ('set_speed', 'tg-old', {'speed': 0.8}),
        ('set_speed', 'tg-old', {'speed': 0.8}),
    ]
    await redis.aclose()


@pytest.mark.asyncio
async def test_selected_speed_reaches_reply_and_intro_without_crossing_intro_cache() -> None:
    tts = FakeTts()
    app, _, _, _ = build_app(tts=tts)
    speech: SessionSpeech = app.state.pipeline.speech
    await speech.speeds.set("tg-fast", 1.0)
    await speech.speeds.set("tg-slow", 0.8)

    await speech.synthesize("tg-slow", "Reply")
    assert tts.speeds[-1] == 0.8


def test_speech_speed_api_is_authenticated_and_greeting_cache_is_per_speed() -> None:
    from fastapi.testclient import TestClient

    tts = FakeTts()
    app, _, _, _ = build_app(tts=tts)
    with TestClient(app, headers={"X-Internal-Token": "test-internal-token"}) as client:
        assert client.get("/internal/speech-speed/tg-1", headers={"X-Internal-Token": "wrong"}).status_code == 401
        assert client.get("/internal/speech-speed/tg-1").json() == {"speed": 0.9}
        invalid = client.post("/internal/speech-speed", json={"sessionId": "tg-1", "speed": 0.85})
        assert invalid.status_code == 400
        assert invalid.json() == {"detail": "speed must be 0.8, 0.9, or 1.0"}
        assert client.post("/internal/speech-speed", json={"sessionId": "tg-1", "speed": 0.8}).json() == {"speed": 0.8}
        assert client.get("/internal/speech-speed/tg-1").json() == {"speed": 0.8}
        assert client.get("/internal/speech-speed/tg-2").json() == {"speed": 0.9}
        for session in ("tg-1", "tg-2"):
            assert client.post("/v1/sessions", json={"sessionId": session}).status_code == 201
        for session in ("tg-1", "tg-2", "tg-1"):
            assert client.get(f"/v1/sessions/{session}/greeting/audio").status_code == 200
        created = client.post(
            "/v1/clips", data={"sessionId": "tg-1"},
            files={"audio": ("voice.ogg", b"fake-ogg", "audio/ogg")},
        )
        assert created.status_code == 202
        for _ in range(50):
            status = client.get(f"/v1/clips/{created.json()['jobId']}").json()
            if status["status"] != "pending":
                break
        assert status["status"] == "ok"
    greeting_speeds = [speed for text, speed in zip(tts.texts, tts.speeds) if text == GREETING_VOICE_TEXT]
    assert greeting_speeds == [0.8, None]
    assert tts.speeds[tts.texts.index("Got it: hello")] == 0.8
