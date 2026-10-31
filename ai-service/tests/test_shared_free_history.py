import asyncio
import json
import time

import pytest
from fakeredis import FakeAsyncRedis

from app.call_start import CallStarter
from app.calls import CallStore
from app.characters import Characters, CharacterStore
from app.conversation import (ConversationHistory, ConversationMode, ConversationRoute,
                              Personality)
from app.dialogue import (ChatMessage, MemoryDialogueStore, RedisDialogueStore,
                          _DUAL_WRITE_UNTIL_KEY, _dump_messages, shared_free_scope_id)
from app.speech import SessionSpeech
from app.pipeline import ClipPipeline
from tests.conftest import FakeLlm, FakeStt, FakeTts
from tests.test_call_start import Model, Personalization


def _contents(messages):
    return [message.content for message in messages]


@pytest.mark.asyncio
async def test_redis_migration_orders_retained_records_and_removes_only_exact_duplicates():
    redis = FakeAsyncRedis(decode_responses=True)
    now = time.time()
    old = now - 7 * 86400 - 1
    speaky = [
        ChatMessage("user", "expired", old),
        ChatMessage("user", "Speaky question", now - 20),
        ChatMessage("assistant", "Speaky answer", now - 19),
        ChatMessage("user", "same words", now - 10),
    ]
    kora = [
        ChatMessage("user", "no date", 0),
        ChatMessage("user", "Kora question", now - 15),
        ChatMessage("assistant", "Kora answer", now - 14),
        ChatMessage("user", "same words", now - 10),
        ChatMessage("user", "same words", now - 9),
    ]
    await redis.set("session:tg-1", _dump_messages(speaky))
    await redis.set("session:tg-1:kora_h", _dump_messages(kora))
    stores = [RedisDialogueStore(redis, 40, 7 * 86400) for _ in range(2)]

    histories = await asyncio.gather(*(store.shared_free_history("tg-1") for store in stores for _ in range(8)))

    expected = ["Speaky question", "Speaky answer", "Kora question", "Kora answer",
                "same words", "same words"]
    assert all(_contents(history) == expected for history in histories)
    assert _contents(await stores[0].shared_free_history("tg-1")) == expected
    assert await redis.get("shared-free:v1:migrated:tg-1") == "1"
    assert _contents(await stores[0].history("tg-1")) == _contents(speaky[1:])
    await redis.aclose()


@pytest.mark.asyncio
async def test_equal_time_preserves_turn_pair_order_and_legacy_missing_timestamp_is_excluded():
    redis = FakeAsyncRedis(decode_responses=True)
    stamp = time.time() - 5
    await redis.set("session:tg-1", json.dumps({"messages": [
        {"role": "user", "content": "old without time"},
        {"role": "user", "content": "Speaky user", "at": stamp},
        {"role": "assistant", "content": "Speaky reply", "at": stamp},
    ]}))
    await redis.set("session:tg-1:kora_h", _dump_messages([
        ChatMessage("user", "Kora user", stamp),
        ChatMessage("assistant", "Kora reply", stamp),
    ]))
    store = RedisDialogueStore(redis, 40, 7 * 86400)

    assert _contents(await store.shared_free_history("tg-1")) == [
        "Speaky user", "Speaky reply", "Kora user", "Kora reply",
    ]
    await redis.aclose()


@pytest.mark.asyncio
async def test_redis_concurrent_writes_are_atomic_idempotent_and_keep_rollback_streams():
    redis = FakeAsyncRedis(decode_responses=True)
    stores = [RedisDialogueStore(redis, 80, 7 * 86400) for _ in range(3)]
    await asyncio.gather(*(stores[index % 3].record_shared_free_turn(
        "tg-1", "speaky" if index % 2 == 0 else "kora_h",
        f"user {index}", f"reply {index}", f"turn-{index}",
    ) for index in range(20)))
    await stores[0].record_shared_free_turn("tg-1", "speaky", "user 0", "reply 0", "turn-0")

    shared = await stores[0].shared_free_history("tg-1")
    assert len(shared) == 40
    assert len(await stores[0].history("tg-1")) == 20
    assert len(await stores[0].history("tg-1:kora_h")) == 20
    assert _contents(shared).count("user 0") == 1
    assert {(item.role, item.content, item.created_at) for item in shared} == {
        (item.role, item.content, item.created_at)
        for key in ("tg-1", "tg-1:kora_h") for item in await stores[0].history(key)
    }
    await redis.aclose()


@pytest.mark.asyncio
async def test_rollback_window_stops_legacy_writes_but_keeps_existing_legacy_history():
    redis = FakeAsyncRedis(decode_responses=True)
    store = RedisDialogueStore(redis, 40, 7 * 86400)
    await store.record_shared_free_turn("tg-1", "speaky", "first", "reply", "one")
    await redis.set(_DUAL_WRITE_UNTIL_KEY, str(time.time() - 1))
    await store.record_shared_free_turn("tg-1", "kora_h", "second", "reply", "two")

    assert _contents(await store.shared_free_history("tg-1")) == ["first", "reply", "second", "reply"]
    assert _contents(await store.history("tg-1")) == ["first", "reply"]
    assert await store.history("tg-1:kora_h") == []
    await redis.aclose()


@pytest.mark.asyncio
async def test_retention_sweep_and_new_turn_keep_current_content():
    redis = FakeAsyncRedis(decode_responses=True)
    store = RedisDialogueStore(redis, 40, 7 * 86400)
    now = time.time()
    await redis.set("session:tg-1", _dump_messages([
        ChatMessage("user", "expired", now - 7 * 86400 - 1),
        ChatMessage("assistant", "current", now - 1),
    ]))

    await asyncio.gather(
        store.prune_all(),
        store.record_shared_free_turn("tg-1", "speaky", "new", "answer", "voice-1"),
    )

    assert _contents(await store.shared_free_history("tg-1")) == ["current", "new", "answer"]
    assert _contents(await store.history("tg-1")) == ["current", "new", "answer"]
    await redis.aclose()


@pytest.mark.asyncio
async def test_shared_history_trims_after_merge_and_keeps_situation_call_local():
    dialogue = MemoryDialogueStore(4, 7 * 86400)
    for index in range(2):
        await dialogue.record_turn("tg-1", f"Speaky {index}", "reply")
        await dialogue.record_turn("tg-1:kora_h", f"Kora {index}", "reply")
    history = ConversationHistory(dialogue)
    free = ConversationRoute(ConversationMode.FREE, Personality.KORA_H)
    situation = ConversationRoute(ConversationMode.SITUATION, Personality.SPEAKY)

    shared = await history.load("tg-1", free)
    local = history.situation(await history.load("tg-1", situation), {
        "id": "call-1", "openingQuestion": "Interview start", "turns": [],
    })

    assert shared.scope_id == shared_free_scope_id("tg-1")
    assert len(shared.messages) == 4
    assert _contents(local.messages) == ["Interview start"]
    assert _contents((await history.load("tg-1", free)).messages) == _contents(shared.messages)
    assert history.scope_id("app-1", free) == "app-1:kora_h"


@pytest.mark.asyncio
async def test_delivered_opening_retries_after_history_write_failure_without_duplicate():
    class FailingHistory(MemoryDialogueStore):
        fail = True

        async def record_shared_free_turn(self, *args):
            if self.fail:
                self.fail = False
                raise RuntimeError("write failed")
            await super().record_shared_free_turn(*args)

    dialogue = FailingHistory(40, 7 * 86400)
    history = ConversationHistory(dialogue)
    starter = CallStarter(CallStore(), Model(), Personalization(), dialogue,
                          SessionSpeech(FakeTts()), Characters(CharacterStore()), history)
    opened = await starter.start("tg-1")

    with pytest.raises(RuntimeError, match="write failed"):
        await starter.delivered(opened["callId"])
    await starter.delivered(opened["callId"])
    await starter.delivered(opened["callId"])

    shared = await history.load("tg-1", ConversationRoute(ConversationMode.FREE, Personality.SPEAKY))
    assert _contents(shared.messages) == ["Let's start a practice conversation.", opened["question"]]


@pytest.mark.asyncio
async def test_opening_uses_pinned_personality_for_legacy_rollback_write():
    class KoraTts(FakeTts):
        async def synthesize(self, text, **kwargs):
            return await super().synthesize(text)

    dialogue = MemoryDialogueStore(40, 7 * 86400)
    history = ConversationHistory(dialogue)
    characters = Characters(CharacterStore(), FakeLlm(), KoraTts(), enabled=True, allowlist="1")
    await characters.store.set("tg-1", "kora_h")
    starter = CallStarter(CallStore(), Model(), Personalization(), dialogue,
                          SessionSpeech(FakeTts()), characters, history)
    opened = await starter.start("tg-1")
    await characters.store.set("tg-1", "speaky")

    await starter.delivered(opened["callId"])

    assert len(await dialogue.history("tg-1:kora_h")) == 2
    assert await dialogue.history("tg-1") == []


@pytest.mark.asyncio
async def test_switching_personality_uses_one_context_and_two_legacy_rollback_streams():
    dialogue = MemoryDialogueStore(40, 7 * 86400)
    ordinary, kora = FakeLlm(), FakeLlm()
    characters = Characters(CharacterStore(), kora, FakeTts(), enabled=True, allowlist="1")
    pipeline = ClipPipeline(FakeStt(["I built a garden.", "The tomatoes grew."]),
                            ordinary, FakeTts(), dialogue, characters=characters)

    await pipeline.run("tg-1", b"x", "audio/ogg", "voice.ogg", turn_id="voice-1")
    await characters.store.set("tg-1", "kora_h")
    await pipeline.run("tg-1", b"x", "audio/ogg", "voice.ogg", turn_id="voice-2")

    assert kora.calls[0][0] == ["I built a garden.", "Got it: I built a garden."]
    assert len(await dialogue.history(shared_free_scope_id("tg-1"))) == 4
    assert len(await dialogue.history("tg-1")) == 2
    assert len(await dialogue.history("tg-1:kora_h")) == 2


@pytest.mark.asyncio
async def test_failed_speaky_audio_does_not_commit_a_shared_turn():
    class FailingTts(FakeTts):
        async def synthesize(self, text, speed=None):
            raise RuntimeError("tts failed")

    dialogue = MemoryDialogueStore(40, 7 * 86400)
    pipeline = ClipPipeline(FakeStt(["Hello"]), FakeLlm(), FailingTts(), dialogue)
    with pytest.raises(RuntimeError, match="tts failed"):
        await pipeline.run("tg-1", b"x", "audio/ogg", "voice.ogg", turn_id="voice-1")
    assert await dialogue.history(shared_free_scope_id("tg-1")) == []
    assert await dialogue.history("tg-1") == []


@pytest.mark.asyncio
async def test_replay_with_same_identity_and_changed_reply_is_rejected():
    redis = FakeAsyncRedis(decode_responses=True)
    store = RedisDialogueStore(redis, 40, 7 * 86400)
    await store.record_shared_free_turn("tg-1", "speaky", "hello", "first", "voice-1")
    with pytest.raises(ValueError, match="turn conflict"):
        await store.record_shared_free_turn("tg-1", "speaky", "hello", "different", "voice-1")
    assert _contents(await store.shared_free_history("tg-1")) == ["hello", "first"]
    await redis.aclose()


@pytest.mark.asyncio
async def test_lost_redis_commit_ack_retries_without_duplicate(monkeypatch):
    redis = FakeAsyncRedis(decode_responses=True)
    store = RedisDialogueStore(redis, 40, 7 * 86400)
    await store.shared_free_history("tg-1")
    original_pipeline = redis.pipeline
    lost_ack = [True]

    class PipelineWithLostAck:
        def __init__(self, inner):
            self.inner = inner

        async def __aenter__(self):
            await self.inner.__aenter__()
            return self

        async def __aexit__(self, *args):
            return await self.inner.__aexit__(*args)

        def __getattr__(self, name):
            return getattr(self.inner, name)

        async def execute(self):
            result = await self.inner.execute()
            if lost_ack[0]:
                lost_ack[0] = False
                raise RuntimeError("commit acknowledgment lost")
            return result

    monkeypatch.setattr(redis, "pipeline", lambda *args, **kwargs:
                        PipelineWithLostAck(original_pipeline(*args, **kwargs)))
    with pytest.raises(RuntimeError, match="acknowledgment lost"):
        await store.record_shared_free_turn("tg-1", "speaky", "hello", "reply", "voice-1")
    await store.record_shared_free_turn("tg-1", "speaky", "hello", "reply", "voice-1")

    assert _contents(await store.shared_free_history("tg-1")) == ["hello", "reply"]
    assert _contents(await store.history("tg-1")) == ["hello", "reply"]
    await redis.aclose()
