from __future__ import annotations

from dataclasses import replace

import pytest
from fastapi.testclient import TestClient

from app.llm import Correction
from app.main import create_app
from tests.conftest import FakeLlm, FakeRealtime, FakeReviewer, FakeStt, FakeTts, build_app
from tests.conftest import test_settings as make_settings

AUTH = {"X-Internal-Token": "test-internal-token"}


def _client(app) -> TestClient:
    return TestClient(app, headers=AUTH)


def _start_session(client: TestClient) -> str:
    response = client.post("/v1/sessions")
    assert response.status_code == 201
    body = response.json()
    assert "greeting" in body and "text" in body["greeting"]
    return body["sessionId"]


def _wait_status(client: TestClient, job_id: str) -> dict:
    for _ in range(50):
        response = client.get(f"/v1/clips/{job_id}")
        assert response.status_code == 200
        body = response.json()
        if body["status"] != "pending":
            return body
    raise AssertionError(f"job {job_id} stayed pending")


def test_health() -> None:
    app, _, _, _ = build_app()
    with TestClient(app) as client:
        response = client.get("/health")
        assert response.status_code == 200
        assert response.json() == {"status": "ok"}


def test_create_app_requires_internal_token() -> None:
    with pytest.raises(RuntimeError, match="AI_INTERNAL_TOKEN"):
        create_app(settings=replace(make_settings(), ai_internal_token=None))


def test_openapi_docs_are_disabled() -> None:
    app, _, _, _ = build_app()
    with _client(app) as client:
        assert client.get("/docs").status_code == 404
        assert client.get("/openapi.json").status_code == 404


def test_clips_without_internal_token_are_401() -> None:
    app, _, _, _ = build_app()
    with TestClient(app) as client:
        assert client.post("/v1/sessions").status_code == 401
        assert client.get("/v1/clips/missing").status_code == 401
        assert client.get("/docs").status_code == 401


def test_clips_with_wrong_internal_token_are_401() -> None:
    app, _, _, _ = build_app()
    with TestClient(app, headers={"X-Internal-Token": "nope"}) as client:
        assert client.post("/v1/sessions").status_code == 401


def test_unknown_job_is_404() -> None:
    app, _, _, _ = build_app()
    with _client(app) as client:
        assert client.get("/v1/clips/missing").status_code == 404
        assert client.get("/v1/clips/missing/audio").status_code == 404


def test_unknown_session_clip_is_404() -> None:
    app, _, _, _ = build_app()
    with _client(app) as client:
        created = client.post(
            "/v1/clips",
            data={"sessionId": "missing"},
            files={"audio": ("voice.ogg", b"fake-ogg", "audio/ogg")},
        )
        assert created.status_code == 404


def test_session_greeting_audio() -> None:
    tts = FakeTts()
    app, _, _, tts = build_app(tts=tts)
    with _client(app) as client:
        response = client.post("/v1/sessions")
        body = response.json()
        session_id = body["sessionId"]
        assert "Speaky, your English practice buddy" in body["greeting"]["text"]
        assert "this is actually my voice" not in body["greeting"]["text"]
        audio = client.get(f"/v1/sessions/{session_id}/greeting/audio")
        assert audio.status_code == 200
        assert audio.content.startswith(b"OggS")
        assert any("this is actually my voice" in text for text in tts.texts)
        assert client.get("/v1/sessions/missing/greeting/audio").status_code == 404


def test_clip_contract_returns_audio() -> None:
    app, _, _, tts = build_app(stt=FakeStt(["I went to the shop"]))
    with _client(app) as client:
        session_id = _start_session(client)
        created = client.post(
            "/v1/clips",
            data={"sessionId": session_id},
            files={"audio": ("voice.ogg", b"fake-ogg", "audio/ogg")},
        )
        assert created.status_code == 202
        job_id = created.json()["jobId"]
        body = _wait_status(client, job_id)
        assert body["status"] == "ok"
        assert body["jobId"] == job_id
        assert body["transcript"] == "I went to the shop"
        assert body["replyText"] == "Got it: I went to the shop"
        assert body["result"]["notes"] == []
        assert "timingsMs" in body
        audio = client.get(f"/v1/clips/{job_id}/audio")
        assert audio.status_code == 200
        assert audio.headers["content-type"].startswith("audio/ogg")
        assert audio.content.startswith(b"OggS")
        assert tts.texts == ["Got it: I went to the shop"]


def test_empty_transcript_clarifies_without_llm() -> None:
    llm = FakeLlm()
    tts = FakeTts()
    app, _, llm, tts = build_app(stt=FakeStt([""]), llm=llm, tts=tts)
    with _client(app) as client:
        session_id = _start_session(client)
        created = client.post(
            "/v1/clips",
            data={"sessionId": session_id},
            files={"audio": ("voice.ogg", b"silence", "audio/ogg")},
        )
        job_id = created.json()["jobId"]
        body = _wait_status(client, job_id)
        assert body["status"] == "ok"
        assert body["replyText"] == "I didn't catch that. Could you say it again?"
        assert body["result"]["notes"] == []
        assert llm.calls == []
        assert llm.notes_calls == []
        assert tts.texts == ["I didn't catch that. Could you say it again?"]


def test_second_clip_includes_dialogue_history() -> None:
    llm = FakeLlm()
    app, _, llm, _ = build_app(stt=FakeStt(["my name is Alex", "what is my name"]))
    with _client(app) as client:
        session_id = _start_session(client)
        first = client.post(
            "/v1/clips",
            data={"sessionId": session_id},
            files={"audio": ("voice.ogg", b"one", "audio/ogg")},
        )
        first_id = first.json()["jobId"]
        assert _wait_status(client, first_id)["status"] == "ok"

        second = client.post(
            "/v1/clips",
            data={"sessionId": session_id},
            files={"audio": ("voice.ogg", b"two", "audio/ogg")},
        )
        second_id = second.json()["jobId"]
        assert _wait_status(client, second_id)["status"] == "ok"

    assert len(llm.calls) == 2
    assert llm.notes_calls == ["my name is Alex", "what is my name"]
    second_history, second_user = llm.calls[1]
    assert second_user == "what is my name"
    assert second_history == ["my name is Alex", "Got it: my name is Alex"]


def test_create_session_with_id_is_get_or_create() -> None:
    app, _, _, _ = build_app()
    with _client(app) as client:
        first = client.post("/v1/sessions", json={"sessionId": "tg-42"})
        assert first.status_code == 201
        assert first.json()["sessionId"] == "tg-42"
        second = client.post("/v1/sessions", json={"sessionId": "tg-42"})
        assert second.status_code == 201
        assert second.json()["sessionId"] == "tg-42"
        created = client.post(
            "/v1/clips",
            data={"sessionId": "tg-42"},
            files={"audio": ("voice.ogg", b"fake-ogg", "audio/ogg")},
        )
        assert created.status_code == 202
        assert _wait_status(client, created.json()["jobId"])["status"] == "ok"


def test_clip_includes_coaching_notes() -> None:
    llm = FakeLlm(notes=[Correction("I was in Turkey", "I went to Turkey", "grammar")])
    app, _, _, tts = build_app(stt=FakeStt(["I was in Turkey last summer"]), llm=llm)
    with _client(app) as client:
        session_id = _start_session(client)
        created = client.post(
            "/v1/clips",
            data={"sessionId": session_id},
            files={"audio": ("voice.ogg", b"voice", "audio/ogg")},
        )
        body = _wait_status(client, created.json()["jobId"])
        assert body["status"] == "ok"
        assert body["result"]["notes"] == ["I was in Turkey|||I went to Turkey"]
        assert body["result"]["corrections"] == [
            {"wrong": "I was in Turkey", "better": "I went to Turkey", "kind": "grammar"},
        ]
        assert body["replyText"] == "Got it: I was in Turkey last summer"
        assert tts.texts == ["Got it: I was in Turkey last summer"]


def test_internal_realtime_and_review() -> None:
    realtime = FakeRealtime()
    reviewer = FakeReviewer()
    app, _, _, _ = build_app(realtime=realtime, reviewer=reviewer)
    with _client(app) as client:
        created = client.post(
            "/internal/realtime/call",
            json={"sdp": "v=0 offer", "topic": "Work", "tutorVoice": "marin"},
        )
        assert created.status_code == 200
        assert created.json() == {"sdp": "v=0 answer", "openaiCallId": "rtc_test"}
        assert realtime.calls == [("v=0 offer", "Work", "marin")]

        kept = client.post(
            "/internal/realtime/call",
            json={"sdp": "v=0 offer\r\n", "topic": "Work", "tutorVoice": "marin"},
        )
        assert kept.status_code == 200
        assert realtime.calls[-1] == ("v=0 offer\r\n", "Work", "marin")

        reviewed = client.post(
            "/internal/review",
            json={
                "turns": [
                    {"role": "user", "text": "I work here since 2023."},
                ]
            },
        )
        assert reviewed.status_code == 200
        body = reviewed.json()
        assert [step["metric"] for step in body["steps"]] == ["Grammar", "Vocabulary"]
        assert reviewer.calls[0][0]["text"] == "I work here since 2023."


def test_internal_realtime_rejects_bad_topic() -> None:
    app, _, _, _ = build_app()
    with _client(app) as client:
        response = client.post(
            "/internal/realtime/call",
            json={"sdp": "v=0", "topic": "Random", "tutorVoice": "marin"},
        )
        assert response.status_code == 400


