from __future__ import annotations

import time

import pytest
from fakeredis import FakeAsyncRedis

from app.audit_artifacts import AuditArtifacts, RETENTION_SECONDS, bind_receipt_time, reset_receipt_time
from app.calls import _expire_call_content
from app.dialogue import ChatMessage, _current_messages
from app.onboarding import FIRST_QUESTION, _expire_raw_turns


def test_seven_day_boundary_drops_raw_dialogue_and_review() -> None:
    now = time.time()
    cutoff = now - RETENTION_SECONDS
    messages = [ChatMessage("user", "expired", cutoff), ChatMessage("assistant", "current", cutoff + 1)]
    assert [message.content for message in _current_messages(messages)] == ["current"]

    onboarding = {
        "status": "completed", "turns": [{"receivedAt": cutoff, "transcript": "old speech"}],
        "review": {"grammar": {"examples": ["old speech"]}},
        "assessment": {"question": "old speech"}, "question": "old speech",
        "continueQuestion": "old speech", "resultText": "old speech",
    }
    cleaned = _expire_raw_turns(onboarding, now)
    assert cleaned["turns"] == []
    assert cleaned["question"] == FIRST_QUESTION
    assert all(key not in cleaned for key in ("review", "assessment", "continueQuestion", "resultText"))

    call = {
        "startedUnix": cutoff, "openingQuestion": "old speech",
        "turns": [{"receivedAt": cutoff, "transcript": "old speech"}],
        "review": {"recap": "old speech"},
    }
    cleaned_call = _expire_call_content(call, now)
    assert cleaned_call["turns"] == []
    assert cleaned_call["openingQuestion"] is None
    assert cleaned_call["review"] is None


def test_new_turn_uses_original_receipt_time() -> None:
    receipt = time.time() - 120
    token = bind_receipt_time(receipt)
    try:
        assert ChatMessage("user", "hello").created_at == receipt
    finally:
        reset_receipt_time(token)


@pytest.mark.asyncio
async def test_ai_artifact_uses_original_receipt_expiry() -> None:
    redis = FakeAsyncRedis(decode_responses=True)
    store = AuditArtifacts(redis)
    received_at = time.time() - 60
    await store.record("attempt", received_at, "transcript", "hello")
    assert await store.get("attempt") == {"transcript": "hello"}
    assert 0 < await redis.ttl("audit:attempt:attempt") <= RETENTION_SECONDS - 60
    await store.record("expired", time.time() - RETENTION_SECONDS, "reply", "old")
    assert await store.get("expired") == {}
