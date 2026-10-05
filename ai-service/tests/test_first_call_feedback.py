from __future__ import annotations

import pytest
from fakeredis import FakeAsyncRedis

from app.calls import CallStore
from app.first_call_feedback import FirstCallFeedback


@pytest.mark.asyncio
@pytest.mark.parametrize("redis", [None, FakeAsyncRedis(decode_responses=True)])
async def test_feedback_is_offered_once_and_answer_is_optional(redis):
    feedback = FirstCallFeedback(redis)
    call_id = "a" * 32
    assert (await feedback.offer("tg-1", call_id))["status"] == "offered"
    assert (await feedback.offer("tg-1", "b" * 32))["status"] == "ignored"
    assert (await feedback.answer("tg-1", text="too early"))["status"] == "ignored"
    assert (await feedback.rate("tg-1", call_id, "neutral"))["status"] == "rated"
    assert (await feedback.rate("tg-1", call_id, "liked"))["status"] == "ignored"
    assert (await feedback.answer("tg-1", text="  More time to speak  "))["status"] == "saved"
    assert (await feedback.answer("tg-1", call_id=call_id))["status"] == "ignored"
    assert (await feedback._read("tg-1"))["answer"] == "text:More time to speak"
    await feedback.aclose()


@pytest.mark.asyncio
async def test_skip_and_long_answer():
    feedback = FirstCallFeedback()
    call_id = "a" * 32
    await feedback.offer("tg-1", call_id)
    await feedback.rate("tg-1", call_id, "liked")
    assert (await feedback.answer("tg-1", text="x" * 501))["status"] == "too_long"
    assert (await feedback.answer("tg-1", call_id=call_id))["status"] == "saved"
    assert (await feedback.answer("tg-1", text="late"))["status"] == "ignored"


@pytest.mark.asyncio
@pytest.mark.parametrize("redis", [None, FakeAsyncRedis(decode_responses=True)])
async def test_dashboard_lists_rated_feedback_with_username_and_optional_message(redis):
    feedback = FirstCallFeedback(redis)
    await feedback.offer("tg-1", "a" * 32, "@alex")
    assert (await feedback.list_rated())["total"] == 0
    await feedback.rate("tg-1", "a" * 32, "liked")
    await feedback.answer("tg-1", text="Great pace")
    await feedback.offer("tg-2", "b" * 32)
    await feedback.rate("tg-2", "b" * 32, "disliked")
    await feedback.answer("tg-2", call_id="b" * 32)
    listed = await feedback.list_rated()
    assert listed["total"] == 2
    assert listed["items"][0]["message"] is None
    assert listed["items"][1] == {
        "sessionId": "tg-1", "username": "alex", "choice": "liked", "message": "Great pace",
    }
    assert len((await feedback.list_rated(0, 1))["items"]) == 1
    await feedback.aclose()


@pytest.mark.asyncio
@pytest.mark.parametrize("redis", [None, FakeAsyncRedis(decode_responses=True)])
async def test_only_first_real_conversation_qualifies(redis):
    calls = CallStore(redis=redis)
    abandoned = await calls.open("tg-1")
    await calls.seal("tg-1")
    first = await calls.open("tg-1")
    await calls.append_turn("tg-1", "hello", "hi", [], 2, [])
    await calls.seal("tg-1")
    second = await calls.open("tg-1")
    assert await calls.is_first_practice_call("tg-1", first["callId"])
    assert not await calls.is_first_practice_call("tg-1", second["callId"])
    assert await calls.is_first_practice_call("tg-1", abandoned["callId"])
    await calls.aclose()
