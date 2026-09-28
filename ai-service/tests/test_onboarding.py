from __future__ import annotations

import asyncio
from copy import deepcopy

import pytest
from fakeredis import FakeAsyncRedis
from fastapi.testclient import TestClient

from app.dialogue import MemoryDialogueStore
from app.llm import Correction
from app.main import create_app
from app.onboarding import FIRST_QUESTION, INSUFFICIENT_TEXT, OnboardingService, OnboardingStore
from app.onboarding_model import parse_assessment
from app.pipeline import ClipPipeline
from app.stt import SttResult
from tests.conftest import FakeLlm, FakeTts, test_settings as settings

AUTH = {"X-Internal-Token": "test-internal-token"}


class Stt:
    def __init__(self, seconds=15, text="I build software. I need English to work with clients."):
        self.seconds = seconds
        self.text = text
        self.calls = 0

    async def transcribe(self, audio, content_type, filename, language="en"):
        self.calls += 1
        return SttResult(text=self.text, duration_seconds=self.seconds, no_speech=not self.text)


class Model:
    def __init__(self, context="software developer", goal="clients", cefr="B1"):
        self.assessment = {"profile": {"context": context, "goal": goal}, "cefr": cefr, "question": "What do you enjoy about your work?"}
        self.calls = 0
        self.fail = False
        self.continue_calls = 0

    async def assess(self, state):
        self.calls += 1
        if self.fail:
            raise RuntimeError("provider unavailable")
        return deepcopy(self.assessment)

    async def continue_question(self, profile):
        self.continue_calls += 1
        return "What would you like to build next?"


def service(stt=None, model=None, store=None, tts=None):
    pipeline = ClipPipeline(
        stt=stt or Stt(), llm=FakeLlm([Correction("I builds", "I build")]),
        tts=tts or FakeTts(), dialogue=MemoryDialogueStore(40, 3600),
    )
    return OnboardingService(store or OnboardingStore(), pipeline, model or Model())


async def begin(s, session="tg-test"):
    state = await s.resolve(session, "start1", "start")
    await s.action(session, state["runId"], "begin")
    return state["runId"]


async def turn(s, run, request="v1", session="tg-test"):
    return await s.turn(session, run, request, b"voice", "audio/ogg", "voice.ogg")


@pytest.mark.asyncio
async def test_new_user_requires_button_and_existing_user_is_exempt():
    s = service()
    state = await s.resolve("tg-test", "s1")
    ignored = await turn(s, state["runId"])
    assert ignored.onboarding["status"] == "ignored"
    assert s.pipeline.stt.calls == 0
    await s.pipeline.metrics.record_start("tg-old", None)
    assert (await s.resolve("tg-old", "s1"))["status"] == "exempt"
    assert (await s.resolve("tg-old", "s2", "force"))["status"] == "waiting"


@pytest.mark.asyncio
@pytest.mark.parametrize("seconds,status", [(29, "active"), (30, "completed"), (60, "completed"), (90, "completed")])
async def test_duration_boundaries_and_full_last_recording(seconds, status):
    s = service(stt=Stt(seconds))
    run = await begin(s)
    result = await turn(s, run)
    assert result.onboarding["status"] == status
    assert result.onboarding["seconds"] == seconds
    assert (result.audio is None) == (status == "completed")
    assert result.corrections
    if status == "completed":
        assert result.reply_text.startswith("Estimated English level: B1")
        assert s.pipeline.tts.texts == [FIRST_QUESTION]


@pytest.mark.asyncio
async def test_missing_goal_waits_until_cap_but_never_asks_after_sixty():
    s = service(stt=Stt(30), model=Model(goal=None))
    run = await begin(s)
    assert (await turn(s, run)).onboarding["status"] == "active"
    assert (await turn(s, run, "v2")).onboarding["status"] == "completed"
    assert len(s.pipeline.tts.texts) == 2


@pytest.mark.asyncio
async def test_unlimited_short_answers_and_insufficient_english():
    s = service(stt=Stt(5), model=Model(cefr=None))
    run = await begin(s)
    for i in range(11):
        assert (await turn(s, run, f"v{i}")).onboarding["status"] == "active"
    result = await turn(s, run, "v11")
    assert result.reply_text == INSUFFICIENT_TEXT
    assert result.onboarding["status"] == "completed"


@pytest.mark.asyncio
async def test_silence_does_not_spend_budget_or_call_model():
    s = service(stt=Stt(60, ""))
    run = await begin(s)
    result = await turn(s, run)
    assert result.audio
    assert result.onboarding["seconds"] == 0
    assert s.model.calls == 0


@pytest.mark.asyncio
async def test_duplicate_voice_is_not_counted_twice_even_after_completion():
    s = service(stt=Stt(30))
    run = await begin(s)
    await turn(s, run)
    assert (await turn(s, run)).onboarding["status"] == "ignored"
    assert s.pipeline.stt.calls == 1
    assert (await s.store.get("tg-test"))["seconds"] == 30
    assert (await s.pipeline.metrics.snapshot())["turns"] == 1


@pytest.mark.asyncio
async def test_summary_failure_retries_without_audio_or_double_counting():
    model = Model()
    model.fail = True
    s = service(stt=Stt(60), model=model)
    run = await begin(s)
    failed = await turn(s, run)
    assert failed.onboarding["status"] == "pending"
    assert failed.audio is None
    model.fail = False
    result = await s.action("tg-test", run, "retry")
    assert result.onboarding["status"] == "completed"
    assert s.pipeline.stt.calls == 1
    assert (await s.store.get("tg-test"))["seconds"] == 60
    calls = model.calls
    await s.action("tg-test", run, "retry")
    assert model.calls == calls
    assert (await s.pipeline.metrics.snapshot())["turns"] == 1


@pytest.mark.asyncio
async def test_tts_failure_preserves_turn_and_retry_does_not_call_stt_or_model_again():
    class FailingTts(FakeTts):
        fail = False

        async def synthesize(self, text):
            if self.fail:
                raise RuntimeError("TTS unavailable")
            return await super().synthesize(text)

    tts = FailingTts()
    s = service(tts=tts)
    run = await begin(s)
    tts.fail = True
    with pytest.raises(RuntimeError):
        await turn(s, run)
    tts.fail = False
    result = await s.action("tg-test", run, "retry")
    assert result.audio
    assert s.pipeline.stt.calls == 1
    assert s.model.calls == 1
    assert result.onboarding["seconds"] == 15


@pytest.mark.asyncio
async def test_reset_is_idempotent_and_old_callbacks_cannot_change_new_attempt():
    s = service()
    run = await begin(s)
    await turn(s, run)
    state = await s.resolve("tg-test", "start2", "start")
    assert state["runId"] != run
    assert state["seconds"] == 0
    assert (await s.resolve("tg-test", "start2", "start"))["runId"] == state["runId"]
    assert (await s.action("tg-test", run, "continue")).onboarding["status"] == "ignored"
    assert (await turn(s, run, "oldQueuedVoice")).onboarding["status"] == "ignored"


@pytest.mark.asyncio
async def test_restart_keeps_state_without_dialogue_ttl():
    redis = FakeAsyncRedis(decode_responses=True)
    s = service(store=OnboardingStore(redis))
    run = await begin(s)
    await turn(s, run)
    restarted = service(store=OnboardingStore(redis))
    state = await restarted.resolve("tg-test", "voice2")
    assert state["runId"] == run
    assert state["seconds"] == 15
    assert await redis.ttl("onboarding:tg-test") == -1
    assert (await turn(restarted, run, "v2")).onboarding["status"] == "completed"


@pytest.mark.asyncio
async def test_continue_uses_profile_once_without_replacing_ordinary_history():
    s = service(stt=Stt(30))
    await s.pipeline.dialogue.create("tg-test")
    await s.pipeline.dialogue.record_turn("tg-test", "Old topic", "Old reply")
    run = await begin(s)
    await turn(s, run)
    assert len(await s.pipeline.dialogue.history("tg-test")) == 2
    await s.action("tg-test", run, "continue")
    await s.action("tg-test", run, "continue")
    assert s.model.continue_calls == 1
    history = await s.pipeline.dialogue.history("tg-test")
    assert len(history) == 4
    assert history[-1].content == "What would you like to build next?"
    assert (await s.resolve("tg-test", "anotherStart", "start"))["status"] == "completed"


@pytest.mark.asyncio
async def test_parallel_duplicate_voice_requests_are_serialized():
    s = service(stt=Stt(15))
    run = await begin(s)
    results = await asyncio.gather(turn(s, run), turn(s, run))
    assert sorted(r.onboarding["status"] for r in results) == ["active", "ignored"]
    assert s.pipeline.stt.calls == 1


def test_assessment_validation_rejects_invalid_cefr_and_missing_fields():
    for raw in ['{}', '{"profile":{},"cefr":73,"question":"Hello?"}', '{"profile":{},"cefr":"B1"}']:
        with pytest.raises(ValueError):
            parse_assessment(raw)
    parsed = parse_assessment('{"profile":{"context":null},"cefr":null,"question":"What do you do?"}')
    assert parsed["cefr"] is None


def test_http_contract_returns_text_only_final_and_requires_auth():
    s = service(stt=Stt(60))
    app = create_app(settings=settings(), pipeline=s.pipeline, onboarding_model=s.model)
    with TestClient(app) as client:
        assert client.post("/internal/onboarding/state", json={}).status_code == 401
        assert client.post("/internal/onboarding/actions", json={}).status_code == 401
        assert client.post("/internal/onboarding/state", headers=AUTH, json={}).status_code == 400
        state = client.post("/internal/onboarding/state", headers=AUTH, json={"sessionId": "tg-new", "requestId": "s"}).json()
        action = client.post("/internal/onboarding/actions", headers=AUTH, json={
            "sessionId": "tg-new", "requestId": "b", "runId": state["runId"], "action": "begin",
        })
        assert action.status_code == 202
        for _ in range(100):
            if client.get(f"/v1/clips/{action.json()['jobId']}", headers=AUTH).json()["status"] != "pending":
                break
        created = client.post("/v1/clips", headers=AUTH,
            data={"sessionId": "tg-new", "onboardingRunId": state["runId"], "requestId": "v"},
            files={"audio": ("voice.ogg", b"voice", "audio/ogg")})
        job_id = created.json()["jobId"]
        for _ in range(100):
            body = client.get(f"/v1/clips/{job_id}", headers=AUTH).json()
            if body["status"] != "pending":
                break
        assert body["status"] == "ok"
        assert body["result"]["audioAvailable"] is False
        assert body["result"]["onboarding"]["status"] == "completed"
        assert client.get(f"/v1/clips/{job_id}/audio", headers=AUTH).status_code == 404


@pytest.mark.asyncio
async def test_callback_receipt_survives_restart_and_cannot_repeat_action():
    redis = FakeAsyncRedis(decode_responses=True)
    s = service(store=OnboardingStore(redis))
    state = await s.resolve("tg-test", "s1")
    run = state["runId"]
    await s.action("tg-test", run, "begin", "callback-1")
    restarted = service(store=OnboardingStore(redis))
    duplicate = await restarted.action("tg-test", run, "begin", "callback-1")
    assert duplicate.onboarding["status"] == "ignored"
    assert restarted.pipeline.tts.texts == []


@pytest.mark.asyncio
async def test_missing_stt_duration_uses_telegram_recording_duration():
    s = service(stt=Stt(0))
    run = await begin(s)
    result = await s.turn("tg-test", run, "voice", b"audio", "audio/ogg", "voice.ogg", 32)
    assert result.onboarding["seconds"] == 32
    assert result.onboarding["status"] == "completed"


@pytest.mark.asyncio
async def test_stt_failure_never_counts_the_recording():
    class BrokenStt(Stt):
        async def transcribe(self, *args, **kwargs):
            raise RuntimeError("STT unavailable")

    s = service(stt=BrokenStt())
    run = await begin(s)
    with pytest.raises(RuntimeError):
        await turn(s, run)
    state = await s.resolve("tg-test", "check")
    assert state["seconds"] == 0
    assert state["retryAvailable"] is False
