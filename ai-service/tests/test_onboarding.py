from __future__ import annotations

import asyncio
from copy import deepcopy

import pytest
from fakeredis import FakeAsyncRedis
from fastapi.testclient import TestClient

from app.dialogue import MemoryDialogueStore
from app.llm import Correction
from app.main import create_app
from app.llm import REPLY_SYSTEM
from app.onboarding import FIRST_QUESTION, OnboardingService, OnboardingStore, RETRY_TEXT
from app.onboarding_model import SYSTEM as ONBOARDING_SYSTEM
from app.realtime import SPEAKY_REALTIME_INSTRUCTIONS
from app.onboarding_model import parse_assessment
from app.pipeline import ClipPipeline
from app.stt import SttResult
from tests.conftest import FakeLlm, FakeTts, test_settings as settings

AUTH = {"X-Internal-Token": "test-internal-token"}


def test_spoken_turns_introduce_speaky_and_stay_with_the_person():
    assert "this is actually my voice" in FIRST_QUESTION
    assert "tell me about yourself" in FIRST_QUESTION
    assert "good to hear you" not in FIRST_QUESTION
    for prompt in (REPLY_SYSTEM, ONBOARDING_SYSTEM, SPEAKY_REALTIME_INSTRUCTIONS):
        assert "2–4 short sentences" not in prompt
        assert "cozy" in prompt
        assert "Oh, that's cool" in prompt
        assert "the phrase still cannot pass by" in prompt
        assert "answer before your own question" in prompt
        assert "love teaching people to speak English" in prompt


class Stt:
    def __init__(self, seconds=15, text="I build software. I need English to work with clients."):
        self.seconds = seconds
        self.text = text
        self.calls = 0

    async def transcribe(self, audio, content_type, filename, language="en"):
        self.calls += 1
        return SttResult(text=self.text, duration_seconds=self.seconds, no_speech=not self.text)


class Model:
    def __init__(self, work="software developer", leisure="hiking", goal="clients", cefr="B1"):
        self.assessment = {
            "profile": {"work": work, "leisure": leisure, "goal": goal},
            "cefr": cefr,
            "position": "high" if cefr in {"A1", "A2", "B1", "B2", "C1"} else None,
            "question": "What do you enjoy about your work?",
        }
        self.calls = 0
        self.fail = False
        self.review_fail = False
        self.review_calls = 0
        self.review_payloads = []
        self.review_callback: str | None = None
        self.continue_calls = 0
        self.asks: list[str] = []

    async def assess(self, state):
        self.calls += 1
        self.asks.append(state.get("ask"))
        if self.fail:
            raise RuntimeError("provider unavailable")
        return deepcopy(self.assessment)

    async def continue_question(self, profile):
        self.continue_calls += 1
        return "What would you like to build next?"

    async def verify_corrections(self, candidates):
        return {item["id"] for item in candidates}

    async def compose_review(self, payload):
        self.review_calls += 1
        self.review_payloads.append(deepcopy(payload))
        if self.review_fail:
            raise RuntimeError("review unavailable")
        transcripts = " ".join(payload.get("transcripts") or [])
        callback = self.review_callback
        if callback is None and "startup" in transcripts.lower():
            callback = "And your startup sounds really interesting — I hope you’ll tell me more about it sometime."
        return {
            "callback": callback,
            "levelText": "You can keep a conversation going about your own work.",
            "grammar": {
                "band": "B1",
                "position": "high",
                "text": "You handle basic sentences, and a few patterns still trip you up.",
                "notes": "Common linked clauses hold, with recurring article slips.",
                "flags": ["simple_clauses", "tense_contrast", "linked_clauses"],
            },
            "vocabulary": {
                "band": "B1",
                "position": "mid",
                "text": "You have enough words for everyday conversation.",
                "notes": "Familiar topics use concrete words, without much precision.",
                "flags": ["concrete_lexis", "topic_spread"],
            },
            "fluency": {
                "band": "B2",
                "position": "low",
                "text": "You can keep your thoughts moving.",
                "notes": "Turns finish, and ideas connect, with some search still visible.",
                "flags": ["completed_turns", "linked_ideas"],
            },
        }


def service(stt=None, model=None, store=None, tts=None, llm=None):
    pipeline = ClipPipeline(
        stt=stt or Stt(), llm=llm or FakeLlm([Correction("I builds", "I build")]),
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
@pytest.mark.parametrize("seconds,status", [(119, "active"), (120, "completed"), (150, "completed")])
async def test_speech_cap_closes_and_keeps_the_whole_recording(seconds, status):
    s = service(stt=Stt(seconds))
    run = await begin(s)
    result = await turn(s, run)
    assert result.onboarding["status"] == status
    assert result.onboarding["seconds"] == seconds
    assert result.audio
    assert result.corrections
    assert "Estimated English" not in result.reply_text
    if status == "completed":
        assert result.reply_text.startswith("You know what, I really enjoyed talking with you. I feel like I know you a little better now. 😊")
        assert "Let me show you what I noticed." in result.reply_text
        assert "😊" not in s.pipeline.tts.texts[-1]
        assert result.onboarding["resultText"] is None
        assert result.onboarding["review"]["grammar"]["score"] == 57
        assert result.onboarding["review"]["vocabulary"]["score"] == 52
        assert result.onboarding["review"]["fluency"]["score"] == 63
        assert "notes" not in result.onboarding["review"]["grammar"]
        assert "confidence" not in result.onboarding["review"]["grammar"]
        assert result.onboarding["overallScore"] == 57
        assert result.onboarding["nextBand"] == "B2"
        assert result.onboarding["pointsToNext"] == 6
        assert "position" not in result.onboarding
        stored_review = (await s.store.get("tg-test"))["review"]
        assert stored_review["grammar"]["confidence"] > 0
        assert stored_review["grammar"]["band"] == "B1"
        assert "notes" in stored_review["grammar"]
        history = await s.pipeline.dialogue.history("tg-test")
        assert history[-2].content == "I build software. I need English to work with clients."
        assert "I really enjoyed talking with you" in history[-1].content
        assert "😊" not in history[-1].content


@pytest.mark.asyncio
@pytest.mark.parametrize("missing", [("work",), ("leisure",), ("goal",), ("work", "leisure", "goal")])
async def test_incomplete_profile_closes_at_two_minutes(missing):
    model = Model(**{field: None for field in missing})
    s = service(stt=Stt(60), model=model)
    run = await begin(s)
    first = await turn(s, run)
    assert first.onboarding["status"] == "active"
    assert model.asks == ["work"]
    second = await turn(s, run, "v2")
    assert second.onboarding["status"] == "completed"
    assert model.asks[-1] == missing[0]
    assert second.onboarding["seconds"] == 120
    assert second.onboarding["review"]
    assert "Let me show you what I noticed." in second.reply_text
    state = await s.store.get("tg-test")
    assert all(state["profile"][field] is None for field in missing)


@pytest.mark.asyncio
async def test_failed_short_answer_does_not_close_or_wait_for_a_saved_retry():
    model = Model(leisure=None)
    s = service(stt=Stt(5), model=model)
    run = await begin(s)
    for i in range(9):
        assert (await turn(s, run, f"v{i}")).onboarding["status"] == "active"
    model.fail = True
    with pytest.raises(RuntimeError):
        await turn(s, run, "v9")
    assert (await s.store.get("tg-test"))["status"] == "active"
    model.fail = False
    retried = await s.action("tg-test", run, "retry")
    assert retried.onboarding["status"] == "active"
    assert retried.audio


@pytest.mark.asyncio
async def test_many_short_answers_stay_open_until_two_minutes():
    ready = service(stt=Stt(5))
    run = await begin(ready)
    for i in range(10):
        assert (await turn(ready, run, f"v{i}")).onboarding["status"] == "active"
    assert (await ready.store.get("tg-test"))["seconds"] == 50

    unknown = service(stt=Stt(5), model=Model(cefr=None))
    unknown_run = await begin(unknown)
    for i in range(10):
        assert (await turn(unknown, unknown_run, f"v{i}")).onboarding["status"] == "active"


@pytest.mark.asyncio
async def test_only_the_first_onboarding_voice_is_marked_for_a_reaction():
    s = service(stt=Stt(60, ""))
    state = await s.resolve("tg-test", "start", "start")
    assert state["react"] is False
    await s.action("tg-test", state["runId"], "begin")
    started = await s.resolve("tg-test", "after-begin")
    assert started["react"] is True
    await turn(s, state["runId"])
    heard = await s.resolve("tg-test", "after-voice")
    assert heard["react"] is False
    assert heard["seconds"] == 0


@pytest.mark.asyncio
async def test_onboarding_keeps_only_the_first_ranked_correction():
    s = service(stt=Stt(15, "I made a photo and you is kind"))
    s.pipeline.llm.notes = [
        Correction("you is", "you are", "grammar"),
        Correction("made a photo", "took a photo", "word"),
    ]
    run = await begin(s)
    result = await turn(s, run)
    assert [(item.wrong, item.better, item.kind) for item in result.corrections] == [
        ("you is", "you are", "grammar"),
    ]
    stored = (await s.store.get("tg-test"))["turns"][0]["corrections"]
    assert [(item["wrong"], item["kind"]) for item in stored] == [
        ("you is", "grammar"),
        ("made a photo", "word"),
    ]


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
    model = Model(work=None, leisure=None, goal=None)
    model.fail = True
    s = service(stt=Stt(120), model=model)
    run = await begin(s)
    failed = await turn(s, run)
    assert failed.onboarding["status"] == "pending"
    assert failed.audio is None
    assert failed.reply_text == RETRY_TEXT
    model.fail = False
    result = await s.action("tg-test", run, "retry")
    assert result.onboarding["status"] == "completed"
    assert s.pipeline.stt.calls == 1
    assert (await s.store.get("tg-test"))["seconds"] == 120
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
    assert (await turn(restarted, run, "v2")).onboarding["status"] == "active"


@pytest.mark.asyncio
async def test_close_remembers_profile_and_restart_wipes_it():
    s = service(stt=Stt(120))
    await s.pipeline.dialogue.create("tg-test")
    await s.pipeline.dialogue.record_turn("tg-test", "Old topic", "Old reply")
    run = await begin(s)
    await turn(s, run)
    history = await s.pipeline.dialogue.history("tg-test")
    assert [item.content for item in history] == [
        "Old topic",
        "Old reply",
        "I build software. I need English to work with clients.",
        "You know what, I really enjoyed talking with you. I feel like I know you a little better now.\nAnd I’ve got a pretty good sense of your English too. Let me show you what I noticed.",
    ]
    saved = await s.store.get("tg-test")
    assert saved["profile"]["work"] == "software developer"
    assert saved["cefr"] == "B1"
    follow = await turn(s, run, "later")
    assert follow.onboarding is None or follow.onboarding.get("status") != "active"
    assert '"overall_cefr": "B1"' in s.pipeline.llm.profile_notes[-1]
    assert '"work": "software developer"' in s.pipeline.llm.profile_notes[-1]
    assert "never say the level" in s.pipeline.llm.profile_notes[-1].lower()
    await s.action("tg-test", run, "continue")
    await s.action("tg-test", run, "continue")
    assert s.model.continue_calls == 1
    assert (await s.pipeline.dialogue.history("tg-test"))[-1].content == "What would you like to build next?"
    assert (await s.resolve("tg-test", "anotherStart", "start"))["status"] == "completed"
    assert (await s.resolve("tg-test", "restart", "force"))["status"] == "waiting"
    wiped = await s.store.get("tg-test")
    assert wiped["profile"] == {}
    assert wiped["cefr"] is None


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
    parsed = parse_assessment(
        '{"profile":{"work":"does not work","leisure":" ","goal":null},"cefr":null,"question":"What do you do?"}'
    )
    assert parsed["cefr"] is None
    assert parsed["position"] is None
    assert parsed["profile"] == {"work": "does not work", "leisure": None, "goal": None}
    with pytest.raises(ValueError):
        parse_assessment('{"profile":{},"cefr":"B1","question":"Hello?"}')
    with pytest.raises(ValueError):
        parse_assessment('{"profile":{},"cefr":"C2","position":"low","question":"Hello?"}')
    placed = parse_assessment('{"profile":{},"cefr":"b1","position":"High","question":"Hello?"}')
    assert placed["cefr"] == "B1"
    assert placed["position"] == "high"


def test_http_contract_returns_closing_audio_and_requires_auth():
    s = service(stt=Stt(120))
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
        assert body["result"]["audioAvailable"] is True
        assert "I really enjoyed talking with you" in body["replyText"]
        assert body["result"]["onboarding"]["review"]["levelText"]
        assert body["result"]["onboarding"]["status"] == "completed"
        profile = client.get("/internal/profile/tg-new", headers=AUTH).json()
        assert profile["assessment"]["cefr"] == "B1"
        assert profile["assessment"]["grammar"] == 57
        assert profile["currentStreak"] == 1
        assert body["result"]["onboarding"]["resultText"] is None
        assert client.get(f"/v1/clips/{job_id}/audio", headers=AUTH).status_code == 200


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
    assert result.onboarding["status"] == "active"


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


@pytest.mark.asyncio
async def test_generic_answer_keeps_the_closing_frame_and_a_startup_gets_a_callback():
    generic = service(stt=Stt(120, "I work as a developer. I like movies. I need English for work."))
    generic_run = await begin(generic)
    generic_result = await turn(generic, generic_run)
    assert generic_result.reply_text.startswith(
        "You know what, I really enjoyed talking with you. I feel like I know you a little better now. 😊"
    )
    assert "startup" not in generic_result.reply_text
    assert "Let me show you what I noticed." in generic_result.reply_text

    model = Model()
    model.review_callback = "And your startup sounds really interesting — I hope you’ll tell me more about it sometime."
    ungrounded = service(stt=Stt(120, "I work as a developer."), model=model)
    ungrounded_run = await begin(ungrounded, "tg-plain")
    dropped = await turn(ungrounded, ungrounded_run, session="tg-plain")
    assert "startup" not in dropped.reply_text

    specific = service(stt=Stt(120, "I am building a startup with two friends."))
    specific_run = await begin(specific, "tg-startup")
    heard = await turn(specific, specific_run, session="tg-startup")
    text = heard.reply_text
    assert text.index("enjoyed talking with you") < text.index("startup") < text.index("I feel like I know you")
    assert text.endswith("Let me show you what I noticed.")
    assert "😊" in text
    assert "😊" not in specific.pipeline.tts.texts[-1]


@pytest.mark.asyncio
async def test_missing_level_still_returns_a_review():
    s = service(stt=Stt(120), model=Model(cefr=None))
    run = await begin(s)
    result = await turn(s, run)
    assert result.onboarding["status"] == "completed"
    assert result.onboarding["cefr"] is None
    assert result.onboarding["review"]["levelText"]
    assert result.onboarding["review"]["fluency"]["longPauses"] is None


@pytest.mark.asyncio
@pytest.mark.parametrize("cefr,position", [("B1", "low"), ("B1", "mid"), ("B1", "high"), ("C2", None), (None, None)])
async def test_review_receives_overall_assessment_and_its_speech_evidence(cefr, position):
    model = Model(cefr=cefr)
    model.assessment["position"] = position
    transcript = "I builds tools because I want to help people learn."
    s = service(stt=Stt(120, transcript), model=model)
    run = await begin(s)
    result = await turn(s, run)
    payload = model.review_payloads[0]
    assert payload["cefr"] == cefr
    assert payload["position"] == position
    assert payload["transcripts"] == [transcript]
    assert payload["grammarExamples"] == [{"wrong": "I builds", "better": "I build"}]
    assert result.onboarding["cefr"] == cefr
    assert result.onboarding["review"]["levelText"] == (await s.store.get("tg-test"))["review"]["levelText"]


@pytest.mark.asyncio
async def test_review_failure_retries_without_another_recording():
    model = Model()
    model.review_fail = True
    s = service(stt=Stt(120), model=model)
    run = await begin(s)
    failed = await turn(s, run)
    assert failed.onboarding["status"] == "pending"
    assert failed.audio is None
    model.review_fail = False
    result = await s.action("tg-test", run, "retry")
    assert result.onboarding["status"] == "completed"
    assert s.pipeline.stt.calls == 1
    assert model.review_calls == 2
    await s.action("tg-test", run, "retry")
    assert model.review_calls == 2


@pytest.mark.asyncio
async def test_practice_goal_survives_force_reset():
    redis = FakeAsyncRedis(decode_responses=True)
    s = service(store=OnboardingStore(redis), stt=Stt(120))
    run = await begin(s)
    await turn(s, run)
    assert await s.set_goal("tg-test", 10) == {"minutes": 10}
    restarted = await s.resolve("tg-test", "restart", "force")
    assert restarted["status"] == "waiting"
    assert (await s.store.get("tg-test"))["profile"] == {}
    assert await s.store.get_goal("tg-test") == 10
    assert await redis.ttl("practice-goal:tg-test") == -1


def test_http_goal_requires_auth_and_known_minutes():
    s = service()
    app = create_app(settings=settings(), pipeline=s.pipeline, onboarding_model=s.model, onboarding_store=s.store)
    with TestClient(app) as client:
        assert client.post("/internal/onboarding/goal", json={}).status_code == 401
        assert client.post(
            "/internal/onboarding/goal", headers=AUTH, json={"sessionId": "tg-1", "requestId": "g", "minutes": 20},
        ).status_code == 400
        saved = client.post(
            "/internal/onboarding/goal", headers=AUTH, json={"sessionId": "tg-1", "requestId": "g", "minutes": 15},
        )
        assert saved.status_code == 200
        assert saved.json() == {"minutes": 15}


@pytest.mark.asyncio
@pytest.mark.parametrize("fails", [False, True])
async def test_review_uses_only_verified_examples_and_survives_verification_failure(fails):
    class FilteringModel(Model):
        async def verify_corrections(self, candidates):
            assert any(item["wrong"] == "the Rodri" for item in candidates)
            if fails:
                raise TimeoutError("verification unavailable")
            return {item["id"] for item in candidates if item["wrong"] == "I builds"}

    model = FilteringModel()
    llm = FakeLlm([
        Correction("I builds", "I build", "grammar"),
        Correction("the Rodri", "Rodri", "grammar"),
    ])
    s = service(stt=Stt(120, "I builds tools. I talked to the Rodri."), model=model, llm=llm)
    run = await begin(s)
    result = await turn(s, run)
    expected = [] if fails else [{"wrong": "I builds", "better": "I build"}]
    assert result.onboarding["status"] == "completed"
    assert model.review_payloads[0]["grammarExamples"] == expected
    assert result.onboarding["review"]["grammar"]["examples"] == expected
    assert model.review_payloads[0]["vocabularyExamples"] == []


@pytest.mark.asyncio
@pytest.mark.parametrize("persistent", [False, True])
async def test_profile_preserves_latest_assessment_goal_and_streak_during_reassessment(persistent):
    redis = FakeAsyncRedis(decode_responses=True) if persistent else None
    s = service(stt=Stt(120), store=OnboardingStore(redis))
    assert await s.progress_profile("tg-test") == {
        "assessment": None, "dailyMinutes": None, "currentStreak": 0,
    }
    assert await s.store.get("tg-test") is None
    run = await begin(s)
    await turn(s, run)
    await s.set_goal("tg-test", 10)
    before = await s.progress_profile("tg-test")
    assert before["assessment"] == {
        "cefr": "B1", "overallScore": 57, "nextBand": "B2", "pointsToNext": 6,
        "grammar": 57, "vocabulary": 52, "fluency": 63,
    }
    assert before["dailyMinutes"] == 10
    assert before["currentStreak"] == 1
    if persistent:
        s.store = OnboardingStore(redis)
        assert await s.progress_profile("tg-test") == before
    restarted = await s.resolve("tg-test", "restart", "force")
    assert restarted["status"] == "waiting"
    assert await s.progress_profile("tg-test") == before
    await s.action("tg-test", restarted["runId"], "begin")
    s.model.assessment.update(cefr="B2", position="low")
    await turn(s, restarted["runId"], "new-voice")
    after = await s.progress_profile("tg-test")
    assert after["assessment"]["cefr"] == "B2"
    assert after["assessment"]["overallScore"] == 63
    assert after["dailyMinutes"] == 10
    assert after["currentStreak"] == 1
    if redis:
        assert await redis.ttl("assessment:tg-test") == -1
        await redis.aclose()


@pytest.mark.asyncio
@pytest.mark.parametrize("persistent", [False, True])
async def test_legacy_completed_attempt_is_preserved_before_reset(persistent):
    redis = FakeAsyncRedis(decode_responses=True) if persistent else None
    s = service(stt=Stt(120), store=OnboardingStore(redis))
    await turn(s, await begin(s))
    # Simulate a completion saved by the previous release, without a snapshot key.
    if redis:
        await redis.delete("assessment:tg-test")
    else:
        s.store._assessments.clear()
    legacy = await s.progress_profile("tg-test")
    assert legacy["assessment"]["cefr"] == "B1"
    await s.resolve("tg-test", "restart", "force")
    assert await s.progress_profile("tg-test") == legacy
    if redis:
        await redis.aclose()


def test_profile_endpoint_requires_auth_and_does_not_create_an_attempt():
    s = service()
    app = create_app(settings=settings(), pipeline=s.pipeline, onboarding_model=s.model)
    with TestClient(app) as client:
        assert client.get("/internal/profile/tg-new").status_code == 401
        response = client.get("/internal/profile/tg-new", headers=AUTH)
        assert response.status_code == 200
        assert response.json() == {"assessment": None, "dailyMinutes": None, "currentStreak": 0}
        assert s.model.calls == 0
        assert s.model.review_calls == 0


@pytest.mark.asyncio
async def test_keep_talking_receives_person_proficiency_and_recent_conversation():
    class PersonalModel(Model):
        async def update_person(self, person, transcripts):
            return {"name": "Alex", "work": "startup", "leisure": "football", "goal": "work",
                    "facts": ["Watches matches alone to concentrate"]}

        async def continue_question(self, profile):
            self.continue_context = profile
            return "What got you into football?"

    model = PersonalModel()
    s = service(stt=Stt(120, "I watch matches alone to concentrate."), model=model)
    run = await begin(s)
    await turn(s, run)
    continued = await s.action("tg-test", run, "continue")
    context = model.continue_context
    assert "Watches matches alone to concentrate" in context["context"]
    assert '\"overall_cefr\": \"B1\"' in context["context"]
    assert '\"immediateContinuation\": true' in context["context"]
    assert any(item["content"] == "I watch matches alone to concentrate." for item in context["recentConversation"])
    assert continued.reply_text == "What got you into football?"


@pytest.mark.asyncio
async def test_intro_is_prepared_once_for_concurrent_users_and_reused_after_reset():
    class SlowTts(FakeTts):
        async def synthesize(self, text):
            await asyncio.sleep(0)
            return await super().synthesize(text)

    s = service(tts=SlowTts())
    warmup = asyncio.create_task(s.warm_intro(1))
    await asyncio.gather(begin(s, "tg-one"), begin(s, "tg-two"), warmup)
    assert s.pipeline.tts.texts == [FIRST_QUESTION]
    restarted = await s.resolve("tg-one", "again", "force")
    result = await s.action("tg-one", restarted["runId"], "begin")
    assert result.audio == b"OggS" + FIRST_QUESTION.encode()
    assert s.pipeline.tts.texts == [FIRST_QUESTION]


@pytest.mark.asyncio
async def test_failed_intro_warmup_does_not_cache_failure_or_prevent_begin():
    class FlakyTts(FakeTts):
        failed = False

        async def synthesize(self, text):
            if not self.failed:
                self.failed = True
                raise RuntimeError("temporary provider error")
            return await super().synthesize(text)

    s = service(tts=FlakyTts())
    await s.warm_intro(1)
    run = await begin(s)
    assert (await s.store.get("tg-test"))["status"] == "active"
    assert run
    assert s.pipeline.tts.texts == [FIRST_QUESTION]
