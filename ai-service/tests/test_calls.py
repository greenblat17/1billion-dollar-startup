from __future__ import annotations

import json
from datetime import datetime
from zoneinfo import ZoneInfo

import pytest
from fakeredis import FakeAsyncRedis

from app.call_review import CallReviews, apply_call_level, parse_call_moves, validate_call_review_response
from app.calls import CallStore, moscow_day
from app.dialogue import MemoryDialogueStore
from app.llm import Correction
from app.metrics import DEFAULT_RATES, MemoryMetricsStore
from app.onboarding import OnboardingStore
from app.onboarding_model import CALL_REVIEW_RESPONSE_FORMAT, OnboardingModel, REVIEW_MAX_TOKENS, REVIEW_RETRY_MAX_TOKENS
from app.onboarding_score import placed_level, step_score
from app.pipeline import ClipPipeline
from app.stt import SttResult
from tests.conftest import FakeLlm, FakeTts

MOSCOW = ZoneInfo("Europe/Moscow")


class Clock:
    def __init__(self, moment: datetime) -> None:
        self.moment = moment

    def __call__(self) -> float:
        return self.moment.timestamp()


class Scorer:
    def __init__(self, move: int = 1, fail: bool = False, upgrades=None) -> None:
        self.upgrades = upgrades or []
        self.move = move
        self.fail = fail
        self.calls = 0

    async def verify_corrections(self, candidates):
        return {item["id"] for item in candidates}

    async def discover_vocabulary_upgrades(self, turns):
        return [{**item, "turn": turns[0]["turn"]} for item in self.upgrades]

    async def verify_vocabulary_upgrades(self, candidates):
        return {item["id"]: {"accepted": True, "reason": "Предложено более точное слово."}
                for item in candidates}

    async def compose_call_review(self, payload):
        self.calls += 1
        if self.fail:
            raise RuntimeError("provider down")
        raw = {
            "levelText": "This conversation sits just above your current level.",
            "recap": "That was a good talk about your startup.",
            "overallMove": self.move,
            "grammar": {"text": "Grammar is a little steadier.", "move": self.move},
            "vocabulary": {"text": "Word choice is about the same.", "move": 0},
            "fluency": {"text": "The turns are a little more connected.", "move": self.move},
        }
        for name, band, position in (("grammar", "B1", "mid"),
                                     ("vocabulary", "A2", "low"),
                                     ("fluency", "B2", "mid")):
            missing = payload["baseline"].get(name) is None
            raw[name].update(band=band if missing else None,
                             position=position if missing else None, shade="0")
        return parse_call_moves(json.dumps(raw))


class Streaks:
    async def shown(self, session_id: str) -> int:
        return 4


class SpeakingStt:
    def __init__(self, text: str, seconds: float) -> None:
        self.text = text
        self.seconds = seconds

    async def transcribe(self, audio, content_type, filename, language="en"):
        return SttResult(
            text=self.text,
            duration_seconds=self.seconds,
            no_speech=not self.text.strip(),
            words=[{"word": "hello", "start": 0.0, "end": 0.4}],
        )


def baseline() -> dict:
    return {
        "runId": "a" * 32,
        "cefr": "B1",
        "position": "mid",
        "shade": "0",
        "overallScore": 52,
        "nextBand": "B2",
        "pointsToNext": 9,
        "grammar": 52,
        "vocabulary": 52,
        "fluency": 52,
    }


@pytest.mark.asyncio
async def test_open_call_records_speech_and_ignores_silence():
    clock = Clock(datetime(2026, 9, 30, 12, tzinfo=MOSCOW))
    store = CallStore(clock=clock, goal_of=_goal(10))
    opened = await store.open("tg-1")
    assert opened["alreadyActive"] is False
    assert await store.is_open_today("tg-1") is False
    assert (await store.open("tg-1"))["alreadyActive"] is False
    assert opened["todaySeconds"] == 0
    assert opened["goalSeconds"] == 600
    silent = await store.append_turn("tg-1", "  ", "again", [], 3, [])
    assert silent["todaySeconds"] == 0
    heard = await store.append_turn(
        "tg-1", "I build software", "Nice.", [{"wrong": "build", "better": "am building", "kind": "grammar"}], 4.5, [{"word": "I"}],
    )
    assert heard["todaySeconds"] == 4.5
    assert await store.is_open_today("tg-1") is True
    assert (await store.open("tg-1"))["alreadyActive"] is True
    saved = await store.get(opened["callId"])
    assert saved["turns"][0]["transcript"] == "I build software"
    assert saved["turns"][0]["words"] == [{"word": "I"}]


@pytest.mark.asyncio
async def test_call_character_is_saved_with_call_and_cannot_change_after_opening():
    store = CallStore()
    opened = await store.open("tg-1")
    call_id = opened["callId"]
    await store.set_character(call_id, "kora_h")
    assert (await store.get(call_id))["character"] == "kora_h"
    await store.save_opening(call_id, "Hi!")
    await store.mark_opening_delivered(call_id)
    with pytest.raises(ValueError, match="cannot be changed"):
        await store.set_character(call_id, "speaky")
    assert (await store.get(call_id))["character"] == "kora_h"


@pytest.mark.asyncio
async def test_call_without_a_goal_keeps_tracking_time_without_a_goal_nudge():
    store = CallStore(goal_of=_goal(0))
    opened = await store.open("tg-1")
    assert opened["goalSeconds"] == 0
    progress = await store.append_turn("tg-1", "I build software", "Tell me more.", [], 320, [])
    assert progress["todaySeconds"] == 320
    assert progress["goalSeconds"] == 0
    assert progress["goalJustCrossed"] is False


@pytest.mark.asyncio
async def test_goal_nudge_happens_once_and_a_new_day_seals_the_old_call():
    clock = Clock(datetime(2026, 9, 30, 12, tzinfo=MOSCOW))
    store = CallStore(clock=clock, goal_of=_goal(5))
    first = await store.open("tg-1")
    early = await store.append_turn("tg-1", "hello there", "Hi.", [], 200, [])
    assert early["goalJustCrossed"] is False
    crossed = await store.append_turn("tg-1", "and more speech", "Go on.", [], 120, [])
    assert crossed["goalJustCrossed"] is True
    assert crossed["todaySeconds"] == 320
    again = await store.append_turn("tg-1", "still talking", "Yes.", [], 30, [])
    assert again["goalJustCrossed"] is False
    clock.moment = datetime(2026, 10, 1, 9, tzinfo=MOSCOW)
    assert await store.is_open_today("tg-1") is False
    nxt = await store.open("tg-1")
    assert nxt["alreadyActive"] is False
    assert nxt["unseenCallId"] == first["callId"]
    assert nxt["closedPreviousCallId"] == first["callId"]
    assert nxt["closedPreviousUnix"] == clock.moment.timestamp()
    assert nxt["callId"] != first["callId"]
    assert nxt["todaySeconds"] == 0
    sealed = await store.get(first["callId"])
    assert sealed["status"] == "closed"
    assert sealed["closeReason"] == "next_moscow_day"
    repeat = await store.open("tg-1")
    assert repeat["alreadyActive"] is False
    assert repeat["unseenCallId"] is None
    assert repeat["callId"] == nxt["callId"]


@pytest.mark.asyncio
async def test_same_day_end_starts_a_new_call_and_minutes_add_up():
    store = CallStore(goal_of=_goal(10))
    first = await store.open("tg-1")
    await store.append_turn("tg-1", "one turn", "Okay.", [], 30, [])
    sealed = await store.seal("tg-1")
    assert sealed == first["callId"]
    second = await store.open("tg-1")
    await store.append_turn("tg-1", "two turn", "Right.", [], 20, [])
    assert second["callId"] != first["callId"]
    assert second["todaySeconds"] == 30
    after = await store.summary("tg-1")
    assert after["todaySeconds"] == 50


@pytest.mark.asyncio
async def test_call_state_survives_redis():
    redis = FakeAsyncRedis(decode_responses=True)
    store = CallStore(redis=redis, goal_of=_goal(10))
    opened = await store.open("tg-1")
    await store.append_turn("tg-1", "persisted speech", "Yes.", [], 8, [])
    again = CallStore(redis=redis, goal_of=_goal(10))
    current = await again.summary("tg-1")
    assert current["callId"] == opened["callId"]
    assert current["todaySeconds"] == 8
    await redis.aclose()


@pytest.mark.asyncio
async def test_telegram_voice_reactions_follow_each_call_and_survive_restart():
    redis = FakeAsyncRedis(decode_responses=True)
    store = CallStore(redis=redis)
    first = await store.open("tg-1")
    assert await store.note_telegram_voice("tg-1", first["callId"], 10) is False
    assert await store.note_telegram_voice("tg-1", first["callId"], 11) is False
    assert (await store.get(first["callId"]))["lastTelegramVoiceId"] == 11
    await store.seal("tg-1")

    second = await store.open("tg-1")
    await store.save_opening(second["callId"], "Hi! How are you?")
    await store.mark_opening_delivered(second["callId"])
    restarted = CallStore(redis=redis)
    assert await restarted.note_telegram_voice("tg-1", second["callId"], 20) is True
    assert await restarted.note_telegram_voice("tg-1", second["callId"], 21) is False
    assert await restarted.note_telegram_voice("tg-1", second["callId"], 20) is True
    assert (await restarted.get(second["callId"]))["lastTelegramVoiceId"] == 21
    with pytest.raises(ValueError, match="call is not open"):
        await restarted.note_telegram_voice("tg-1", first["callId"], 22)
    await redis.aclose()


def test_one_call_cannot_jump_the_level():
    assert step_score(52, 30) == 54
    assert step_score(52, -30) == 50
    assert step_score(50, 2) == 52
    moved = apply_call_level(
        baseline(),
        parse_call_moves(json.dumps({
            "levelText": "A small step.",
            "recap": "We talked about your week. Then we got into the product launch. And the team.",
            "overallMove": 30,
            "grammar": {"text": "A bit clearer.", "move": 30},
            "vocabulary": {"text": "Same range.", "move": 0},
            "fluency": {"text": "A bit smoother.", "move": -30},
        })),
        {"grammar": [{"wrong": "build", "better": "am building", "explanation": "Здесь нужно am building."}], "vocabulary": []},
        {"paceWpm": 90, "longPauses": 1, "fillers": None, "longestStretchSec": 4},
    )
    assert moved["assessment"]["overallScore"] == 54
    assert moved["assessment"]["cefr"] == "B1"
    assert moved["assessment"]["grammar"] <= 54
    assert moved["public"]["previousScore"] == 52
    assert moved["public"]["grammar"]["examples"][0]["explanation"] == "Здесь нужно am building."
    assert moved["public"]["recap"] == "We talked about your week. Then we got into the product launch."
    assert moved["public"]["fluency"]["score"] >= 50


def test_call_places_missing_skill_scores_from_this_conversation_once():
    assessment = {**baseline(), "grammar": None, "vocabulary": None, "fluency": None}
    moves = parse_call_moves(json.dumps({
        "levelText": "You explain your work clearly.",
        "recap": "I enjoyed talking about your work.",
        "overallMove": 0,
        "grammar": {"text": "You connect clauses.", "move": 0,
                    "band": "B1", "position": "mid", "shade": "0"},
        "vocabulary": {"text": "You use basic words repeatedly.", "move": 0,
                       "band": "A2", "position": "low", "shade": "0"},
        "fluency": {"text": "You speak in connected turns.", "move": 0,
                    "band": "B2", "position": "mid", "shade": "0"},
    }))
    first = apply_call_level(assessment, moves, {"grammar": [], "vocabulary": []}, {})
    assert first["assessment"]["grammar"] == 52
    assert first["assessment"]["vocabulary"] == 32
    assert first["assessment"]["fluency"] == 70
    assert first["assessment"]["overallScore"] == 52

    next_moves = {**moves, "vocabulary": {**moves["vocabulary"], "move": -2,
                                           "band": "C1", "position": "high"}}
    second = apply_call_level(first["assessment"], next_moves,
                              {"grammar": [], "vocabulary": []}, {})
    assert second["assessment"]["vocabulary"] == 30


@pytest.mark.asyncio
async def test_call_review_saves_first_skill_scores_when_baseline_has_none():
    store = OnboardingStore()
    await store.save_assessment("tg-1", {**baseline(), "grammar": None,
                                         "vocabulary": None, "fluency": None})
    calls = CallStore(goal_of=_goal(10))
    opened = await calls.open("tg-1")
    await calls.append_turn("tg-1", "We need to fix our startup and improve the product.",
                            "What would you improve?", [], 12, [])
    await calls.seal("tg-1")

    review = await CallReviews(calls, store, Scorer(), Streaks()).review(opened["callId"])

    assert [review[name]["score"] for name in ("grammar", "vocabulary", "fluency")] == [52, 32, 70]
    saved = await store.get_assessment("tg-1")
    assert [saved[name] for name in ("grammar", "vocabulary", "fluency")] == [52, 32, 70]


def test_accumulated_scores_cross_position_and_band_boundaries():
    score = 56
    observed = []
    for _ in range(4):
        score = step_score(score, 2)
        level = placed_level(score)
        observed.append((level["overallScore"], level["cefr"], level["position"], level["pointsToNext"]))
    assert observed == [
        (58, "B1", "high", 3),
        (60, "B1", "high", 1),
        (62, "B2", "low", 19),
        (64, "B2", "low", 17),
    ]
    assert placed_level(54)["position"] == "mid"
    assert placed_level(55)["position"] == "high"
    assert placed_level(59)["overallScore"] == 59
    assert placed_level(61)["cefr"] == "B2"
    assert placed_level(step_score(61, -2))["cefr"] == "B1"
    assert placed_level(29)["cefr"] == "A1"
    assert placed_level(30)["cefr"] == "A2"
    assert step_score(95, 2) == 96
    assert step_score(11, -2) == 10


@pytest.mark.asyncio
async def test_successive_call_reviews_save_accumulated_level_and_skill_progress():
    store = OnboardingStore()
    initial = {**baseline(), "overallScore": 56, "position": "high", "shade": "-", "pointsToNext": 5,
               "grammar": 56, "fluency": 56}
    await store.save_assessment("tg-1", initial)
    await store.save_learner("tg-1", {
        "person": {}, "proficiency": {
            "overall_cefr": "B1", "position": "high", "overall_score": 56,
            "grammar": {"band": "B1", "position": "high", "score": 56},
        },
    })
    calls = CallStore(goal_of=_goal(10))
    scorer = Scorer(move=2)
    reviews = CallReviews(calls, store, scorer, Streaks())
    for expected in (58, 60, 62):
        opened = await calls.open("tg-1")
        await calls.append_turn("tg-1", "I explain my work.", "Tell me more.", [], 12, [])
        await calls.seal("tg-1")
        review = await reviews.review(opened["callId"])
        assert review["overallScore"] == expected
        assert (await store.get_assessment("tg-1"))["overallScore"] == expected
    assert review["cefr"] == "B2"
    assert review["grammar"]["score"] == 62
    proficiency = (await store.get_learner("tg-1"))["proficiency"]
    assert proficiency["overall_cefr"] == "B2"
    assert proficiency["grammar"]["band"] == "B2"
    assert proficiency["grammar"]["score"] == 62


@pytest.mark.asyncio
async def test_review_is_cached_and_a_failure_can_be_retried():
    store = OnboardingStore()
    await store.save_assessment("tg-1", baseline())
    calls = CallStore(goal_of=_goal(10))
    opened = await calls.open("tg-1")
    await calls.append_turn(
        "tg-1", "I build software", "Tell me more.",
        [{"wrong": "build", "better": "am building", "kind": "grammar", "explanation": "Здесь нужно am building."}], 12, [{"word": "I", "start": 0, "end": 0.2}],
    )
    await calls.seal("tg-1")
    scorer = Scorer(move=1)
    reviews = CallReviews(calls, store, scorer, Streaks())
    first = await reviews.review(opened["callId"])
    second = await reviews.review(opened["callId"])
    assert scorer.calls == 1
    assert first["overallScore"] == second["overallScore"] == 53
    assert first["streak"] == 4
    assert first["grammar"]["examples"][0]["explanation"] == "Здесь нужно am building."
    assert (await store.get_assessment("tg-1"))["overallScore"] == 53


    state = {
        "status": "completed", "runId": "a" * 32, "cefr": "A1", "position": "low",
        "review": {"shade": "0", "grammar": {"score": 12}, "vocabulary": {"score": 12}, "fluency": {"score": 12}},
    }
    await store.save("tg-1", state)
    assert (await store.get_assessment("tg-1"))["overallScore"] == 53
    failing = CallStore(goal_of=_goal(10))
    other = await failing.open("tg-2")
    await failing.append_turn("tg-2", "hello", "Hi.", [], 5, [])
    await failing.seal("tg-2")
    await store.save_assessment("tg-2", baseline())
    broken = CallReviews(failing, store, Scorer(fail=True), Streaks())
    missed = await broken.review(other["callId"])
    assert missed["retry"] is True
    assert (await store.get_assessment("tg-2"))["overallScore"] == 52
    healed = CallReviews(failing, store, Scorer(move=1), Streaks())
    assert (await healed.review(other["callId"]))["retry"] is False


def test_call_review_uses_saved_explanation_without_requesting_another():
    request = {"grammarExamples": [{"wrong": "I builds", "better": "I build", "explanation": "После I нужен build."}], "vocabularyExamples": []}
    response = {
        "levelText": "You link your ideas clearly.", "recap": "I enjoyed talking about your work.",
        "overallMove": 1, "grammar": {"text": "You use clear sentences.", "move": 1},
        "vocabulary": {"text": "You choose familiar words.", "move": 0},
        "fluency": {"text": "Your turns flow well.", "move": 0},
    }
    for name in ("grammar", "vocabulary", "fluency"):
        response[name].update(band=None, position=None, shade="0")
    validate_call_review_response(json.dumps(response))
    moves = parse_call_moves(json.dumps(response))
    review = apply_call_level(baseline(), moves, {"grammar": request["grammarExamples"], "vocabulary": []}, {})
    assert review["public"]["grammar"]["examples"] == request["grammarExamples"]
    response["grammarExplanations"] = ["New explanation"]
    with pytest.raises(ValueError, match="invalid call review fields"):
        validate_call_review_response(json.dumps(response))


@pytest.mark.asyncio
async def test_call_review_shows_up_to_five_verified_examples_per_skill():
    store = OnboardingStore()
    await store.save_assessment("tg-1", baseline())
    calls = CallStore(goal_of=_goal(10))
    opened = await calls.open("tg-1")
    corrections = [
        {"wrong": wrong, "better": better, "kind": "grammar", "explanation": "Сохранённое объяснение грамматики."}
        for wrong, better in (
            ("I builds", "I build"), ("she go", "she goes"),
            ("we was", "we were"), ("he do", "he does"),
            ("they is", "they are"), ("it have", "it has"),
        )
    ]
    vocabulary = [
        {"wrong": wrong, "better": better, "kind": "word", "explanation": "Сохранённое объяснение слова."}
        for wrong, better in (
            ("do a decision", "make a decision"), ("strong rain", "heavy rain"),
            ("make a photo", "take a photo"), ("big mistake", "serious mistake"),
            ("fast shower", "quick shower"), ("do progress", "make progress"),
        )
    ]
    transcript = (
        "I builds, she go, we was, he do, they is, and it have. "
        "I do a decision in strong rain, make a photo, big mistake, fast shower, do progress."
    )
    await calls.append_turn("tg-1", transcript, "Tell me more.", corrections + vocabulary, 12, [])
    await calls.seal("tg-1")
    review = await CallReviews(calls, store, Scorer(), Streaks()).review(opened["callId"])
    examples = review["grammar"]["examples"]
    assert len(examples) == 5
    assert [item["wrong"] for item in examples] == [item["wrong"] for item in corrections[:5]]
    assert all(item["explanation"] == "Сохранённое объяснение грамматики." for item in examples)
    assert review["vocabulary"]["examples"] == []


@pytest.mark.asyncio
async def test_call_review_routes_lexical_error_to_grammar_beside_vocabulary_upgrade():
    store = OnboardingStore()
    await store.save_assessment("tg-1", baseline())
    calls = CallStore(goal_of=_goal(10))
    opened = await calls.open("tg-1")
    transcript = "I did a decision after a big increase in costs."
    await calls.append_turn("tg-1", transcript, "Tell me more.", [{
        "wrong": "I did a decision", "better": "I made a decision", "kind": "natural",
        "explanation": "Здесь говорят make a decision.",
    }], 12, [])
    await calls.seal("tg-1")
    upgrade = {"context": transcript, "original": "big", "alternative": "substantial",
               "category": "word_upgrade", "explanation": "Substantial точнее описывает масштаб роста."}
    review = await CallReviews(calls, store, Scorer(upgrades=[upgrade]), Streaks()).review(opened["callId"])
    assert review["grammar"]["examples"] == [{
        "wrong": "I did a decision", "better": "I made a decision",
        "explanation": "Здесь говорят make a decision.",
    }]
    assert review["vocabulary"]["examples"] == []
    assert review["vocabulary"]["suggestions"][0]["context"] == transcript
    assert review["vocabulary"]["suggestions"][0]["editedContext"] == (
        "I did a decision after a substantial increase in costs.")
    trace = await store.get_vocabulary_trace("call", opened["callId"])
    assert trace["entries"][0]["status"] == "selected"
    assert trace["entries"][0]["reason"] == "Предложено более точное слово."
    assert "reason" not in review["vocabulary"]["suggestions"][0]


@pytest.mark.asyncio
async def test_call_review_retries_invalid_response_with_review_fallback_model():
    class Llm:
        def __init__(self):
            self.calls = []

        async def complete_json(self, system, data, **kwargs):
            self.calls.append((system, kwargs))
            response = {
                "levelText": "You connect ideas and explain why.",
                "recap": "I enjoyed talking about your work.", "overallMove": 1,
                "grammar": {"text": "You make clear statements. One verb form needs work.", "move": 3 if len(self.calls) == 1 else 1},
                "vocabulary": {"text": "You use familiar words accurately. Your range is steady.", "move": 0},
                "fluency": {"text": "Your ideas connect. Your pace is steady.", "move": 0},
            }
            for name in ("grammar", "vocabulary", "fluency"):
                response[name].update(band=None, position=None, shade="0")
            return json.dumps(response)

    llm = Llm()
    result = await OnboardingModel(
        llm, "google/gemini-3.5-flash-lite", "openai/gpt-4o-mini",
    ).compose_call_review({
        "grammarExamples": [{"wrong": "I builds", "better": "I build"}],
        "vocabularyExamples": [],
    })
    assert result["grammar"]["move"] == 1
    assert [(call[1]["model"], call[1]["max_tokens"], call[1]["response_format"]) for call in llm.calls] == [
        ("google/gemini-3.5-flash-lite", REVIEW_MAX_TOKENS, CALL_REVIEW_RESPONSE_FORMAT),
        ("openai/gpt-4o-mini", REVIEW_RETRY_MAX_TOKENS, CALL_REVIEW_RESPONSE_FORMAT),
    ]
    assert all("exact excerpts" in call[0] and "conversation-time explanations" in call[0] for call in llm.calls)


@pytest.mark.asyncio
async def test_call_review_finds_upgrade_without_live_corrections():
    store = OnboardingStore()
    await store.save_assessment("tg-1", baseline())
    calls = CallStore(goal_of=_goal(10))
    opened = await calls.open("tg-1")
    transcript = "We saw a big increase in costs."
    await calls.append_turn("tg-1", transcript, "Tell me more.", [], 12, [])
    await calls.seal("tg-1")
    upgrade = {"context": transcript, "original": "big", "alternative": "substantial",
               "category": "word_upgrade", "explanation": "Substantial точнее описывает масштаб роста."}
    review = await CallReviews(calls, store, Scorer(upgrades=[upgrade]), Streaks()).review(opened["callId"])
    assert review["vocabulary"]["examples"] == []
    assert len(review["vocabulary"]["suggestions"]) == 1
    assert review["vocabulary"]["suggestions"][0]["original"] == "big"
    assert review["vocabulary"]["suggestions"][0]["editedContext"] == (
        "We saw a substantial increase in costs.")


@pytest.mark.asyncio
async def test_pipeline_appends_only_an_open_call():
    calls = CallStore(goal_of=_goal(10))
    pipeline = ClipPipeline(
        SpeakingStt("I build software", 6), FakeLlm(notes=[Correction("build", "am building", "grammar")]),
        FakeTts(), MemoryDialogueStore(40, 86400), MemoryMetricsStore(DEFAULT_RATES), calls=calls,
    )
    await pipeline.dialogue.create("tg-1")
    untouched = await pipeline.run("tg-1", b"audio", "audio/ogg", "voice.ogg")
    assert untouched.call is None
    await calls.open("tg-1")
    pipeline._stt = SpeakingStt("I build software", 6)
    heard = await pipeline.run("tg-1", b"audio", "audio/ogg", "voice.ogg")
    assert heard.call["todaySeconds"] == 6
    assert heard.call["recognizedSeconds"] == 6
    pipeline._stt = SpeakingStt("", 0)
    quiet = await pipeline.run("tg-1", b"audio", "audio/ogg", "voice.ogg")
    assert quiet.call["todaySeconds"] == 6


def _goal(minutes: int):
    async def lookup(_session_id: str) -> int:
        return minutes
    return lookup


def test_moscow_day_uses_the_practice_timezone():
    moment = datetime(2026, 9, 30, 23, 30, tzinfo=MOSCOW).timestamp()
    assert moscow_day(moment) == "2026-09-30"
