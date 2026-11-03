from __future__ import annotations

import pytest
import httpx
import time
from fakeredis import FakeAsyncRedis

from app.first_call_feedback import FirstCallFeedback, ModeFeedback, offer_eligibility
from tests.conftest import build_app


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
        "sessionId": "tg-1", "username": "alex", "character": "speaky", "callId": "a" * 32,
        "choice": "liked", "message": "Great pace",
    }
    assert len((await feedback.list_rated(0, 1))["items"]) == 1
    await feedback.aclose()


def test_offer_needs_review_and_real_conversation():
    call = {"sessionId": "tg-1", "turns": [{"transcript": "hello"}], "review": {"score": 52}}
    completed = {"status": "completed"}
    assert offer_eligibility(call, completed, "tg-1") is None
    assert offer_eligibility(call, {"status": "exempt"}, "tg-1") is None
    assert offer_eligibility({**call, "character": "kora_h"}, {"status": "exempt"}, "tg-1") is None
    assert offer_eligibility({**call, "turns": []}, completed, "tg-1") == "no_user_turn"
    assert offer_eligibility({**call, "review": None}, completed, "tg-1") == "review_unavailable"
    assert offer_eligibility(call, completed, "tg-2") == "session_mismatch"


@pytest.mark.asyncio
async def test_existing_user_receives_one_survey_on_next_eligible_call():
    feedback = FirstCallFeedback()
    existing_call = {"sessionId": "tg-1", "turns": [{"transcript": "hello"}], "review": {"score": 52}}
    assert offer_eligibility(existing_call, {"status": "completed"}, "tg-1") is None
    assert (await feedback.offer("tg-1", "b" * 32))["status"] == "offered"
    assert (await feedback.offer("tg-1", "c" * 32))["reason"] == "already_offered"


@pytest.mark.asyncio
@pytest.mark.parametrize("redis", [None, FakeAsyncRedis(decode_responses=True)])
async def test_kora_feedback_is_per_call_and_text_goes_to_latest_rated_call(redis):
    feedback = FirstCallFeedback(redis)
    first, second = "b" * 32, "c" * 32
    assert (await feedback.offer("tg-1", first, "@alex", character="kora_h")) == {
        "status": "offered", "character": "kora_h",
    }
    assert (await feedback.offer("tg-1", first, character="kora_h"))["reason"] == "already_offered"
    assert (await feedback.rate("tg-2", first, "liked"))["status"] == "ignored"
    assert (await feedback.rate("tg-1", first, "neutral"))["status"] == "rated"
    assert (await feedback.offer("tg-1", second, "@alex", character="kora_h"))["status"] == "offered"
    assert (await feedback.rate("tg-1", second, "liked"))["status"] == "rated"
    assert (await feedback.answer("tg-1", text="This felt warmer"))["status"] == "saved"
    assert (await feedback.answer("tg-1", call_id=first))["status"] == "saved"
    assert (await feedback.offer("tg-1", "d" * 32))["status"] == "offered"
    listed = await feedback.list_rated()
    assert listed["total"] == 2
    assert {item["callId"] for item in listed["items"]} == {first, second}
    assert {item["character"] for item in listed["items"]} == {"kora_h"}
    assert next(item for item in listed["items"] if item["callId"] == second)["message"] == "This felt warmer"
    await feedback.aclose()


@pytest.mark.asyncio
async def test_feedback_api_uses_character_saved_on_each_closed_call():
    app, *_ = build_app()
    calls = app.state.pipeline.calls

    async def reviewed_call(character):
        opened = await calls.open("tg-1")
        call_id = opened["callId"]
        await calls.set_character(call_id, character)
        await calls.append_turn("tg-1", "Hello", "Hi!", [], 1.0, [])
        await calls.seal("tg-1")
        await calls.save_review(call_id, {"score": 50})
        return call_id

    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test",
                                headers={"X-Internal-Token": "test-internal-token"}) as client:
        for character, expected in (("kora_h", "offered"), ("kora_h", "offered"),
                                    ("speaky", "offered"), ("speaky", "offered")):
            if character == "speaky":
                await app.state.onboarding.store.save("tg-1", {"status": "completed"})
            call_id = await reviewed_call(character)
            response = (await client.post("/internal/calls/feedback", json={
                "sessionId": "tg-1", "action": "offer", "callId": call_id,
            })).json()
            assert response["status"] == expected
            if expected == "offered":
                assert response["character"] == character


@pytest.mark.asyncio
@pytest.mark.parametrize("redis", [None, FakeAsyncRedis(decode_responses=True)])
async def test_mode_feedback_reoffers_until_first_rating_in_each_mode(redis):
    legacy = FirstCallFeedback(redis)
    feedback = ModeFeedback(legacy)
    ids = [format(index, "032x") for index in range(1, 6)]
    assert (await feedback.offer("tg-1", ids[0], "free"))["status"] == "offered"
    assert (await feedback.confirm("tg-1", ids[0]))["status"] == "saved"
    assert (await feedback.offer("tg-1", ids[1], "free"))["status"] == "offered"
    assert (await feedback.rate("tg-1", ids[1], "liked"))["status"] == "rated"
    assert (await feedback.offer("tg-1", ids[2], "free"))["reason"] == "already_rated"
    assert (await feedback.offer("tg-1", ids[2], "situation"))["status"] == "offered"
    assert (await feedback.release("tg-1", ids[2]))["status"] == "saved"
    assert (await feedback.offer("tg-1", ids[2], "situation"))["status"] == "offered"
    assert (await feedback.offer("tg-1", ids[3], "onboarding"))["status"] == "offered"
    assert (await feedback.rate("tg-1", ids[3], "neutral"))["status"] == "rated"
    assert (await feedback.offer("tg-1", ids[4], "onboarding"))["reason"] == "already_rated"
    assert (await feedback.answer("tg-1", text="Good pace"))["status"] == "saved"
    assert (await feedback.list_rated())["total"] == 2
    await legacy.aclose()


@pytest.mark.asyncio
@pytest.mark.parametrize("redis", [None, FakeAsyncRedis(decode_responses=True)])
async def test_historical_rating_only_blocks_mode_when_linked_call_proves_it(redis):
    legacy = FirstCallFeedback(redis)
    feedback = ModeFeedback(legacy)
    old_id, next_id = "a" * 32, "b" * 32
    await legacy.offer("tg-1", old_id, character="kora_h")
    await legacy.rate("tg-1", old_id, "liked")

    async def get_call(call_id):
        return {"sessionId": "tg-1", "scenario": {"kind": "job"},
                "startedUnix": time.time()} if call_id == old_id else None

    async def recent_ids(session_id):
        return [old_id] if session_id == "tg-1" else []

    await feedback.migrate_legacy("tg-1", get_call, recent_ids)
    assert (await feedback.offer("tg-1", next_id, "situation"))["reason"] == "already_rated"
    assert (await feedback.offer("tg-1", next_id, "free"))["status"] == "offered"
    await legacy.aclose()


@pytest.mark.asyncio
async def test_onboarding_feedback_api_uses_attempt_and_stops_after_rating():
    app, *_ = build_app()
    store = app.state.onboarding.store
    first, second = "1" * 32, "2" * 32

    async def completed(run_id):
        await store.save("tg-1", {"runId": run_id, "status": "completed", "character": "kora_h",
                                  "cefr": "A2", "position": "mid", "review": {"levelText": "A2"},
                                  "turns": [{"receivedAt": time.time(), "transcript": "Hello"}]})

    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test",
                                headers={"X-Internal-Token": "test-internal-token"}) as client:
        await completed(first)
        async def post(action, run_id, **kwargs):
            return (await client.post("/internal/calls/feedback", json={
                "sessionId": "tg-1", "action": action, "callId": run_id, **kwargs,
            })).json()

        offered = await post("offer", first)
        assert offered == {"status": "offered", "character": "kora_h", "mode": "onboarding"}
        assert (await post("confirm", first))["status"] == "saved"
        assert (await post("rate", first, choice="liked"))["status"] == "rated"
        await completed(second)
        assert (await post("offer", second))["reason"] == "already_rated"
