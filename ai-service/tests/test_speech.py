import pytest
from fakeredis import FakeAsyncRedis

from app.onboarding import FIRST_QUESTION
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
async def test_selected_speed_reaches_reply_and_intro_without_crossing_intro_cache() -> None:
    tts = FakeTts()
    app, _, _, _ = build_app(tts=tts)
    speech: SessionSpeech = app.state.pipeline.speech
    await speech.speeds.set("tg-fast", 1.0)
    await speech.speeds.set("tg-slow", 0.8)

    await speech.synthesize("tg-slow", "Reply")
    assert tts.speeds[-1] == 0.8

    onboarding = app.state.onboarding
    await onboarding.intro_audio("tg-slow")
    await onboarding.intro_audio("tg-fast")
    await onboarding.intro_audio("tg-slow")
    intro_speeds = [speed for text, speed in zip(tts.texts, tts.speeds) if text == FIRST_QUESTION]
    assert intro_speeds == [0.8, 1.0]


def test_speech_speed_api_is_authenticated_and_greeting_cache_is_per_speed() -> None:
    from fastapi.testclient import TestClient

    tts = FakeTts()
    app, _, _, _ = build_app(tts=tts)
    with TestClient(app, headers={"X-Internal-Token": "test-internal-token"}) as client:
        assert client.get("/internal/speech-speed/tg-1", headers={"X-Internal-Token": "wrong"}).status_code == 401
        assert client.get("/internal/speech-speed/tg-1").json() == {"speed": 0.9}
        assert client.post("/internal/speech-speed", json={"sessionId": "tg-1", "speed": 0.85}).status_code == 400
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
