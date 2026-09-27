from __future__ import annotations

import pytest
from fakeredis import FakeAsyncRedis
from fastapi.testclient import TestClient

from app.dialogue import MemoryDialogueStore
from app.lessons import MemoryLessonStore, RedisLessonStore
from app.llm import Correction
from app.pipeline import CLARIFY_TEXT, ClipPipeline
from tests.conftest import FakeLlm, FakeStt, FakeTts, build_app

AUTH = {"X-Internal-Token": "test-internal-token"}


@pytest.mark.asyncio
async def test_open_twice_returns_the_same_lesson() -> None:
    store = MemoryLessonStore()
    first = await store.open("tg-1")
    second = await store.open("tg-1")
    assert second == first
    assert await store.current("tg-1") == first


@pytest.mark.asyncio
async def test_pipeline_appends_corrections_and_seal_keeps_dialogue() -> None:
    dialogue = MemoryDialogueStore(max_messages=40, ttl_seconds=86400)
    lessons = MemoryLessonStore()
    notes = [Correction("goes", "go", "grammar")]
    pipeline = ClipPipeline(
        stt=FakeStt(["I goes home"]),
        llm=FakeLlm(notes=notes),
        tts=FakeTts(),
        dialogue=dialogue,
        lessons=lessons,
    )
    lesson_id = await lessons.open("tg-1")
    await pipeline.run("tg-1", b"voice", "audio/ogg", "voice.ogg")

    lesson = await lessons.get(lesson_id)
    assert lesson is not None
    assert lesson["status"] == "open"
    assert lesson["turns"] == [
        {
            "transcript": "I goes home",
            "replyText": "Got it: I goes home",
            "corrections": [{"wrong": "goes", "better": "go", "kind": "grammar"}],
        }
    ]
    history = await dialogue.history("tg-1")
    assert [item.content for item in history] == ["I goes home", "Got it: I goes home"]

    assert await lessons.seal("tg-1") is True
    assert await dialogue.history("tg-1") == history
    sealed = await lessons.get(lesson_id)
    assert sealed is not None
    assert sealed["status"] == "closed"
    assert sealed["endedAt"]
    assert await lessons.current("tg-1") is None

    await pipeline.run("tg-1", b"voice", "audio/ogg", "voice.ogg")
    after = await lessons.get(lesson_id)
    assert after is not None
    assert len(after["turns"]) == 1
    assert await lessons.seal("tg-1") is False


@pytest.mark.asyncio
async def test_clarify_turn_is_stored_only_while_the_lesson_is_open() -> None:
    lessons = MemoryLessonStore()
    pipeline = ClipPipeline(
        stt=FakeStt([""]),
        llm=FakeLlm(),
        tts=FakeTts(),
        dialogue=MemoryDialogueStore(max_messages=40, ttl_seconds=86400),
        lessons=lessons,
    )
    lesson_id = await lessons.open("tg-2")
    await pipeline.run("tg-2", b"noise", "audio/ogg", "voice.ogg")
    lesson = await lessons.get(lesson_id)
    assert lesson is not None
    assert lesson["turns"][0]["replyText"] == CLARIFY_TEXT
    assert lesson["turns"][0]["corrections"] == []

    await lessons.seal("tg-2")
    await pipeline.run("tg-2", b"noise", "audio/ogg", "voice.ogg")
    sealed = await lessons.get(lesson_id)
    assert sealed is not None
    assert len(sealed["turns"]) == 1


@pytest.mark.asyncio
async def test_redis_lesson_has_no_ttl_and_a_second_open_is_new() -> None:
    redis = FakeAsyncRedis(decode_responses=True)
    store = RedisLessonStore(redis)
    first = await store.open("tg-9")
    assert await redis.ttl(f"lesson:{first}") == -1
    assert await redis.ttl(f"lesson:open:tg-9") == -1
    await store.seal("tg-9")
    second = await store.open("tg-9")
    assert second != first
    indexed = await redis.lrange("lessons:by_user:tg-9", 0, -1)
    assert indexed == [first, second]
    await store.aclose()


def test_lesson_routes_open_current_and_seal() -> None:
    app, _, _, _ = build_app()
    with TestClient(app, headers=AUTH) as client:
        missing = client.post("/internal/lessons/open", json={})
        assert missing.status_code == 400

        idle = client.post("/internal/lessons/current", json={"sessionId": "tg-3"})
        assert idle.status_code == 200
        assert idle.json()["lessonId"] is None

        opened = client.post("/internal/lessons/open", json={"sessionId": "tg-3"})
        assert opened.status_code == 200
        lesson_id = opened.json()["lessonId"]
        again = client.post("/internal/lessons/open", json={"sessionId": "tg-3"})
        assert again.json()["lessonId"] == lesson_id

        current = client.post("/internal/lessons/current", json={"sessionId": "tg-3"})
        assert current.json()["lessonId"] == lesson_id

        sealed = client.post("/internal/lessons/seal", json={"sessionId": "tg-3"})
        assert sealed.json()["sealed"] is True
        empty = client.post("/internal/lessons/seal", json={"sessionId": "tg-3"})
        assert empty.json()["sealed"] is False
        assert client.post("/internal/lessons/current", json={"sessionId": "tg-3"}).json()["lessonId"] is None

        fresh = client.post("/internal/lessons/open", json={"sessionId": "tg-3"})
        assert fresh.json()["lessonId"] != lesson_id
