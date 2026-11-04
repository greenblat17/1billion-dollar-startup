import json

import pytest
from fakeredis import FakeAsyncRedis

from app.vocabulary_builder import VocabularyBuilder


@pytest.mark.asyncio
@pytest.mark.parametrize("redis", [None, FakeAsyncRedis(decode_responses=True)])
async def test_safe_facts_follow_committed_state_without_lesson_content(redis):
    builder = VocabularyBuilder(redis, None, None, None, clock=lambda: 1000.0)
    session = "tg-42"
    card = {"id": "a" * 12, "word": "private phrase", "meaning": "private meaning",
            "source": "manual"}

    def add(state):
        state["items"].append(card)

    await builder._change(session, add)

    def start(state):
        state["lesson"] = {"id": "b" * 12, "format": 5, "status": "active",
                           "words": [card], "index": 0, "prompt": "private question"}

    await builder._change(session, start)
    batch = await builder.analytics_after()
    assert [item["kind"] for item in batch["events"]] == ["word_saved", "lesson_created"]
    assert batch["events"][1]["wordCount"] == 1
    assert "private" not in json.dumps(batch)
    assert (await builder.analytics_after(batch["cursor"]))["events"] == []
    await builder.analytics_ack(batch["cursor"])
    if redis is not None:
        assert (await builder.analytics_after())["events"] == []
    await builder.close()


@pytest.mark.asyncio
async def test_trace_facts_exclude_transcript_and_retry_does_not_duplicate():
    builder = VocabularyBuilder(None, None, None, None, clock=lambda: 1000.0)
    session = "tg-42"
    word = {"id": "a" * 12, "word": "private phrase", "source": "manual"}
    lesson = {"id": "b" * 12, "format": 5, "status": "active", "words": [word],
              "index": 1, "prompt": "private question"}
    await builder._change(session, lambda state: state.update(lesson=lesson))

    def advance(state):
        if state["lesson"]["index"] != 1:
            raise ValueError("duplicate")
        state["lesson"]["index"] = 2

    events = [{"type": "answer", "phase": "Use", "wordId": word["id"],
               "category": "used", "transcript": "private transcript", "reason": "private reason"},
              {"type": "transition", "phase": "Use", "wordId": word["id"],
               "fromIndex": 1, "toIndex": 2}]
    await builder._change_with_events(session, lesson, advance, events)
    with pytest.raises(ValueError):
        await builder._change_with_events(session, lesson, advance, events)
    batch = await builder.analytics_after()
    assert [item["kind"] for item in batch["events"]].count("answer") == 1
    assert batch["events"][-2]["category"] == "used"
    assert "private" not in json.dumps(batch)
