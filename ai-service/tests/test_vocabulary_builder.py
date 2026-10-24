from __future__ import annotations

import json

import pytest
from fakeredis import FakeAsyncRedis

from app.stt import SttResult
from app.tts import TtsAudio
from app.vocabulary_builder import VocabularyBuilder


class Clock:
    def __init__(self) -> None:
        self.now = 1000.0

    def __call__(self) -> float:
        return self.now


class Speech:
    async def synthesize(self, session_id, text):
        return TtsAudio(text.encode(), "audio/ogg")


class Stt:
    def __init__(self) -> None:
        self.text = "I was overwhelmed by work."

    async def transcribe(self, audio, content_type, filename, language="en"):
        return SttResult(self.text, no_speech=not self.text)


class Model:
    def __init__(self) -> None:
        self.prompts = []

    async def complete_json(self, system, payload, **kwargs):
        data = json.loads(payload)
        operation = kwargs["operation"]
        if operation == "vocabulary_card":
            return json.dumps({"word": data["input"], "meaning": "too much to manage",
                               "translation": "перегруженный", "example": "I felt overwhelmed at work."})
        if operation == "vocabulary_import":
            return json.dumps({"cards": [{"word": item.get("better") or item.get("alternative"), "meaning": "too much to manage",
                                          "translation": "перегруженный", "example": "I felt overwhelmed at work."}
                                         for item in data["inputs"]]})
        if operation == "vocabulary_prompt":
            round_name = data["round"]
            number = len(self.prompts)
            prompt = (f"Use overwhelmed in a sentence about work situation {number}." if round_name != "Independent"
                      else f"Tell me about too many tasks in situation {number}.")
            self.prompts.append(prompt)
            return json.dumps({"prompt": prompt})
        if operation == "vocabulary_grade":
            matched = "overwhelmed" if "overwhelmed" in data["transcript"] else ""
            return json.dumps({"correct": bool(matched), "uncertain": False,
                               "matchedPhrase": matched, "feedback": "Good use." if matched else "Try another way."})
        if operation == "vocabulary_hint":
            return json.dumps({"hint": "Think about too many tasks."})
        raise AssertionError(operation)


@pytest.mark.asyncio
async def test_three_rounds_master_only_after_all_successes():
    clock, stt, model = Clock(), Stt(), Model()
    builder = VocabularyBuilder(None, model, stt, Speech(), clock)
    await builder.propose("tg-1", "overwhelmed")
    card = await builder.confirm("tg-1")
    await builder.select("tg-1", card["id"], True)
    assert (await builder.select("tg-1", card["id"], True))["selected"] == [card["id"]]
    start = await builder.start("tg-1")
    lesson_id = start["lesson"]["id"]
    assert start["text"].startswith("Let's practice your words together.")
    assert start["lesson"]["round"] == "Guided"
    assert not (await builder.list("tg-1"))["items"][0]["mastered"]
    for index in range(3):
        result = await builder.voice("tg-1", lesson_id, index + 1, b"voice")
        if index < 2:
            assert result["lesson"]["status"] == "active"
            duplicate = await builder.voice("tg-1", lesson_id, index + 1, b"voice")
            assert duplicate["status"] == "duplicate"
            assert duplicate["lesson"]["index"] == index + 1
            assert not (await builder.list("tg-1"))["items"][0]["mastered"]
    assert result["status"] == "finished"
    assert "mastered" in result["summary"]
    assert (await builder.list("tg-1"))["items"][0]["mastered"]
    assert "overwhelmed" not in model.prompts[2].lower()


@pytest.mark.asyncio
async def test_retry_hint_timeout_and_partial_summary():
    clock, stt, model = Clock(), Stt(), Model()
    builder = VocabularyBuilder(None, model, stt, Speech(), clock)
    await builder.propose("tg-2", "overwhelmed")
    card = await builder.confirm("tg-2")
    await builder.select("tg-2", card["id"], True)
    lesson = (await builder.start("tg-2"))["lesson"]
    stt.text = "I had too much work."
    first = await builder.voice("tg-2", lesson["id"], 1, b"voice")
    assert first["lesson"]["attempts"] == 1
    assert first["lesson"]["index"] == 0
    hint = await builder.hint("tg-2", lesson["id"])
    assert hint["lesson"]["hints"] == 1
    stt.text = "I was overwhelmed."
    second = await builder.voice("tg-2", lesson["id"], 2, b"voice")
    assert second["lesson"]["index"] == 1
    assert not (await builder.list("tg-2"))["items"][0]["mastered"]
    clock.now += 301
    due = await builder.due()
    assert len(due) == 1 and due[0]["kind"] == "reminder"
    assert await builder.due() == []
    clock.now += 3300
    due = await builder.due()
    assert len(due) == 1 and due[0]["kind"] == "ended"
    assert "not completed" in due[0]["text"]
    assert (await builder.end("tg-2", lesson["id"]))["status"] == "stale"


@pytest.mark.asyncio
async def test_review_imports_shown_examples_once():
    builder = VocabularyBuilder(None, Model(), Stt(), Speech())
    review = {"vocabulary": {"examples": [{"better": "overwhelmed"}],
                             "suggestions": [{"alternative": "overwhelmed"}]}}
    assert await builder.import_review("tg-3", "call-1", review) == 1
    assert await builder.import_review("tg-3", "call-1", review) == 0
    assert len((await builder.list("tg-3"))["items"]) == 1


@pytest.mark.asyncio
async def test_dictionary_and_lesson_survive_service_instance_change():
    redis = FakeAsyncRedis(decode_responses=True)
    model, stt, clock = Model(), Stt(), Clock()
    first = VocabularyBuilder(redis, model, stt, Speech(), clock)
    await first.propose("tg-4", "overwhelmed")
    card = await first.confirm("tg-4")
    await first.select("tg-4", card["id"], True)
    lesson_id = (await first.start("tg-4"))["lesson"]["id"]
    restarted = VocabularyBuilder(redis, model, stt, Speech(), clock)
    state = await restarted.list("tg-4")
    assert state["selected"] == [card["id"]]
    assert state["lesson"]["id"] == lesson_id
    assert (await restarted.voice("tg-4", lesson_id, 1, b"voice"))["lesson"]["index"] == 1
