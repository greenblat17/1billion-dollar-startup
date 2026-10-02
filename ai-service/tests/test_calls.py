from __future__ import annotations

import json
from datetime import datetime
from zoneinfo import ZoneInfo

import pytest
from fakeredis import FakeAsyncRedis

from app.call_review import CallReviews, apply_call_level, parse_call_moves
from app.calls import CallStore, moscow_day
from app.dialogue import MemoryDialogueStore
from app.llm import Correction
from app.metrics import DEFAULT_RATES, MemoryMetricsStore
from app.onboarding import OnboardingStore
from app.onboarding_score import step_score
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
    def __init__(self, move: int = 1, fail: bool = False, suggestions: list[dict] | None = None) -> None:
        self.move = move
        self.fail = fail
        self.calls = 0
        self.suggestions = suggestions

    async def verify_corrections(self, candidates):
        return {item["id"] for item in candidates}

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
            "vocabularySuggestions": self.suggestions or [],
        }
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
    assert nxt["callId"] != first["callId"]
    assert nxt["todaySeconds"] == 0
    sealed = await store.get(first["callId"])
    assert sealed["status"] == "closed"
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
    assert step_score(50, 2) <= 52
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
        {"grammar": [{"wrong": "build", "better": "am building"}], "vocabulary": []},
        {"paceWpm": 90, "longPauses": 1, "fillers": None, "longestStretchSec": 4},
    )
    assert moved["assessment"]["overallScore"] == 54
    assert moved["assessment"]["cefr"] == "B1"
    assert moved["assessment"]["grammar"] <= 54
    assert moved["public"]["previousScore"] == 52
    assert moved["public"]["recap"] == "We talked about your week. Then we got into the product launch."
    assert moved["public"]["fluency"]["score"] >= 50


@pytest.mark.asyncio
async def test_review_is_cached_and_a_failure_can_be_retried():
    store = OnboardingStore()
    await store.save_assessment("tg-1", baseline())
    calls = CallStore(goal_of=_goal(10))
    opened = await calls.open("tg-1")
    await calls.append_turn(
        "tg-1", "I build software", "Tell me more.",
        [{"wrong": "build", "better": "am building", "kind": "grammar"}], 12, [{"word": "I", "start": 0, "end": 0.2}],
    )
    await calls.seal("tg-1")
    scorer = Scorer(move=1)
    reviews = CallReviews(calls, store, scorer, Streaks())
    first = await reviews.review(opened["callId"])
    second = await reviews.review(opened["callId"])
    assert scorer.calls == 1
    assert first["overallScore"] == second["overallScore"] == 53
    assert first["streak"] == 4
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


@pytest.mark.asyncio
async def test_call_review_keeps_alternatives_only_when_vocabulary_has_no_corrections():
    store = OnboardingStore()
    await store.save_assessment("tg-1", baseline())
    calls = CallStore(goal_of=_goal(10))
    opened = await calls.open("tg-1")
    await calls.append_turn("tg-1", "I use the same words every single time.", "Tell me more.", [], 12, [])
    await calls.seal("tg-1")
    suggestion = {
        "original": "I use the same words every single time",
        "alternative": "I tend to fall back on the same words",
        "explanation": "Fall back on describes relying on familiar words out of habit.",
    }
    review = await CallReviews(calls, store, Scorer(suggestions=[suggestion]), Streaks()).review(opened["callId"])
    assert review["vocabulary"]["examples"] == []
    assert review["vocabulary"]["suggestions"] == [suggestion]


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
