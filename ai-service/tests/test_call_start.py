from __future__ import annotations

import base64
from datetime import datetime
from zoneinfo import ZoneInfo

import pytest
from fakeredis import FakeAsyncRedis

from app.call_start import CallStarter
from app.calls import CallStore
from app.dialogue import MemoryDialogueStore
from app.speech import SessionSpeech
from tests.conftest import FakeTts


class Model:
    def __init__(self) -> None:
        self.calls = 0

    async def start_call_question(self, context):
        self.calls += 1
        assert "recentConversation" in context
        assert context["firstName"] is None
        return "Hi! How are you? What made you smile today?"


class Personalization:
    async def prepare(self, session_id, continuation=False):
        assert not continuation
        return "Use verified memory only."


class FailingTts(FakeTts):
    def __init__(self) -> None:
        super().__init__()
        self.fail = True

    async def synthesize(self, text, speed=None):
        if self.fail:
            self.fail = False
            raise RuntimeError("tts unavailable")
        return await super().synthesize(text, speed=speed)


@pytest.mark.asyncio
async def test_starter_reuses_question_and_marks_delivery_once():
    redis = FakeAsyncRedis(decode_responses=True)
    calls = CallStore(redis=redis, goal_of=lambda _: _goal())
    model, tts = Model(), FakeTts()
    dialogue = MemoryDialogueStore(max_messages=40, ttl_seconds=86400)
    starter = CallStarter(calls, model, Personalization(), dialogue, SessionSpeech(tts))

    first = await starter.start("tg-1")
    second = await starter.start("tg-1")
    assert first["callId"] == second["callId"]
    assert first["status"] == second["status"] == "ready"
    assert base64.b64decode(first["audioBase64"]).startswith(b"OggS")
    assert first["todaySeconds"] == 0
    assert first["goalSeconds"] == 300
    assert model.calls == 1

    restarted = CallStarter(CallStore(redis=redis, goal_of=lambda _: _goal()), model,
                            Personalization(), dialogue, SessionSpeech(tts))
    assert (await restarted.start("tg-1"))["question"] == first["question"]
    assert model.calls == 1
    await restarted.delivered(first["callId"])
    await restarted.delivered(first["callId"])
    assert (await restarted.start("tg-1"))["status"] == "active"
    history = await dialogue.history("tg-1")
    assert [turn.content for turn in history if turn.role == "assistant"] == [first["question"]]
    assert (await calls.get(first["callId"]))["seconds"] == 0
    await redis.aclose()


@pytest.mark.asyncio
async def test_starter_retries_tts_without_regenerating_question_and_new_call_gets_new_question():
    now = [datetime(2026, 10, 1, 12, tzinfo=ZoneInfo("Europe/Moscow")).timestamp()]
    calls = CallStore(clock=lambda: now[0])
    model = Model()
    tts = FailingTts()
    starter = CallStarter(calls, model, Personalization(),
                          MemoryDialogueStore(max_messages=40, ttl_seconds=86400), SessionSpeech(tts))
    with pytest.raises(RuntimeError, match="tts unavailable"):
        await starter.start("tg-1")
    first = await starter.start("tg-1")
    assert model.calls == 1
    await starter.delivered(first["callId"])
    assert (await starter.start("tg-1"))["status"] == "active"
    await calls.seal("tg-1")
    second = await starter.start("tg-1")
    assert second["callId"] != first["callId"]
    assert model.calls == 2
    now[0] += 86400
    third = await starter.start("tg-1")
    assert third["callId"] != second["callId"]
    assert model.calls == 3


@pytest.mark.asyncio
async def test_scenario_is_persisted_on_call_and_not_added_to_personal_dialogue():
    redis = FakeAsyncRedis(decode_responses=True)
    calls = CallStore(redis=redis)
    dialogue = MemoryDialogueStore(max_messages=40, ttl_seconds=86400)
    starter = CallStarter(calls, Model(), Personalization(), dialogue, SessionSpeech(FakeTts()))
    scenario = {"kind": "custom", "description": "I am negotiating rent with a landlord"}
    opening = await starter.start("tg-2", scenario=scenario)
    assert (await calls.get(opening["callId"]))["scenario"] == scenario
    await starter.delivered(opening["callId"])
    assert await dialogue.history("tg-2") == []
    await redis.aclose()


@pytest.mark.asyncio
async def test_switching_undelivered_opening_replaces_scenario_and_question():
    calls = CallStore()
    first = await calls.open("tg-3", {"kind": "job", "description": ""})
    await calls.save_opening(first["callId"], "What are your strengths?")
    second = await calls.open("tg-3", {"kind": "manager", "description": ""})
    assert second["callId"] == first["callId"]
    saved = await calls.get(first["callId"])
    assert saved["scenario"] == {"kind": "manager", "description": ""}
    assert saved["openingQuestion"] is None
    await calls.save_opening(first["callId"], "Can you update me on the project?")
    await calls.open("tg-3", None)
    saved = await calls.get(first["callId"])
    assert saved["scenario"] is None
    assert saved["openingQuestion"] is None


async def _goal():
    return 5
