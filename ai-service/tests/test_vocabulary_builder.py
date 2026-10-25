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
    async def speed(self, session_id):
        return 0.9

    async def synthesize(self, session_id, text):
        return TtsAudio(text.encode(), "audio/ogg")


class KoraSpeech:
    def __init__(self) -> None:
        self.calls = []

    async def synthesize(self, text, *, speed, delivery):
        self.calls.append((text, speed, delivery))
        return TtsAudio(b"kora-audio", "audio/ogg")


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
            word = data["target"]["word"]
            prompt = (f"{word} means too much to manage. When did you feel {word} in situation {number}? Use {word}." if round_name != "Recall"
                      else f"Tell me about too many tasks in situation {number}.")
            self.prompts.append(prompt)
            return json.dumps({"prompt": prompt})
        if operation == "vocabulary_grade":
            word = data["target"]["word"]
            matched = word if word in data["transcript"] else ""
            return json.dumps({"correct": bool(matched), "uncertain": False,
                               "matchedPhrase": matched, "feedback": "Good use." if matched else "Try another way."})
        if operation == "vocabulary_hint":
            return json.dumps({"hint": "Think about too many tasks."})
        if operation == "vocabulary_card_prompt":
            return json.dumps({"prompt": "What does overwhelmed mean?" if data["direction"] == "word_to_meaning"
                               else "Which word means too much to manage?"})
        if operation == "vocabulary_card_grade":
            return json.dumps({"correct": "too much" in data["transcript"] or "overwhelmed" in data["transcript"],
                               "uncertain": False, "feedback": "Good."})
        raise AssertionError(operation)


@pytest.mark.asyncio
async def test_two_rounds_offer_optional_cards_without_mastered_status():
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
    assert "mastered" not in (await builder.list("tg-1"))["items"][0]
    for index in range(2):
        result = await builder.voice("tg-1", lesson_id, index + 1, b"voice")
        if index < 1:
            assert result["lesson"]["status"] == "active"
            duplicate = await builder.voice("tg-1", lesson_id, index + 1, b"voice")
            assert duplicate["status"] == "duplicate"
            assert duplicate["lesson"]["index"] == index + 1
    assert result["status"] == "offer"
    assert "word game" in result["text"]
    assert "overwhelmed" not in model.prompts[1].lower()
    card_turn = await builder.play("tg-1", lesson_id)
    assert card_turn["lesson"]["status"] == "cards"
    stt.text = "It means too much to manage."
    card_result = await builder.voice("tg-1", lesson_id, 3, b"voice")
    assert card_result["status"] == "finished"
    assert "word game: correct" in card_result["summary"]
    assert "Round 2: recalled on your own" in card_result["summary"]
    assert "mastered" not in (await builder.list("tg-1"))["items"][0]


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
    assert first["hintText"] and first["lesson"]["hints"] == 1
    assert "audio" in first and first["hintText"] not in first["text"]
    stt.text = "I was overwhelmed."
    second = await builder.voice("tg-2", lesson["id"], 2, b"voice")
    assert second["lesson"]["index"] == 1
    assert "mastered" not in (await builder.list("tg-2"))["items"][0]
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
async def test_requested_hint_does_not_consume_attempt_and_skip_preserves_result():
    builder = VocabularyBuilder(None, Model(), Stt(), Speech())
    await builder.propose("tg-hint", "overwhelmed")
    card = await builder.confirm("tg-hint")
    await builder.select("tg-hint", card["id"], True)
    lesson_id = (await builder.start("tg-hint"))["lesson"]["id"]
    hint = await builder.hint("tg-hint", lesson_id)
    assert hint["lesson"]["attempts"] == 0 and "audio" not in hint
    await builder.voice("tg-hint", lesson_id, 1, b"voice")
    await builder.voice("tg-hint", lesson_id, 2, b"voice")
    result = await builder.skip("tg-hint", lesson_id)
    assert "Round 2: recalled on your own" in result["summary"]
    assert "word game" not in result["summary"]


@pytest.mark.asyncio
async def test_five_word_limit_and_unclear_voice_do_not_advance():
    stt = Stt()
    builder = VocabularyBuilder(None, Model(), stt, Speech())
    for index in range(5):
        await builder.propose("tg-five", f"word{index}")
        card = await builder.confirm("tg-five")
        await builder.select("tg-five", card["id"], True)
    assert len((await builder.list("tg-five"))["selected"]) == 5
    await builder.propose("tg-five", "sixth")
    sixth = await builder.confirm("tg-five")
    with pytest.raises(ValueError, match="five words"):
        await builder.select("tg-five", sixth["id"], True)
    lesson = (await builder.start("tg-five"))["lesson"]
    assert lesson["total"] == 10
    stt.text = ""
    unclear = await builder.voice("tg-five", lesson["id"], 1, b"voice")
    assert unclear["status"] == "repeat"
    assert unclear["lesson"]["attempts"] == 0


@pytest.mark.asyncio
async def test_words_are_interleaved_and_recall_prompt_uses_earlier_context():
    stt, model = Stt(), Model()
    builder = VocabularyBuilder(None, model, stt, Speech())
    for word in ("overwhelmed", "postpone"):
        await builder.propose("tg-order", word)
        card = await builder.confirm("tg-order")
        await builder.select("tg-order", card["id"], True)
    lesson = (await builder.start("tg-order"))["lesson"]
    lesson_id = lesson["id"]
    stt.text = "I felt overwhelmed."
    first = await builder.voice("tg-order", lesson_id, 1, b"voice")
    assert first["lesson"]["word"]["word"] == "postpone"
    stt.text = "I will postpone it."
    second = await builder.voice("tg-order", lesson_id, 2, b"voice")
    assert second["lesson"]["round"] == "Recall"
    assert second["lesson"]["word"] is None
    assert "overwhelmed" not in second["lesson"]["prompt"]
    assert model.prompts[2] != model.prompts[0]


@pytest.mark.asyncio
async def test_legacy_three_round_lesson_no_longer_blocks_new_start():
    builder = VocabularyBuilder(None, Model(), Stt(), Speech())
    await builder.propose("tg-legacy", "overwhelmed")
    card = await builder.confirm("tg-legacy")
    await builder.select("tg-legacy", card["id"], True)
    builder.memory["tg-legacy"]["lesson"] = {
        "id": "old", "status": "active", "words": [card], "index": 0,
        "attempts": 0, "hints": 0, "results": {}, "prompts": [], "lastActivity": 1000.0,
        "reminded": False, "processed": [], "prompt": "Old three-round question."}
    state = await builder.list("tg-legacy")
    assert state["lesson"]["status"] == "finished"
    new_lesson = await builder.start("tg-legacy")
    assert new_lesson["lesson"]["status"] == "active"
    assert new_lesson["lesson"]["id"] != "old"


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


@pytest.mark.asyncio
async def test_kora_voice_and_subtitles_survive_service_restart():
    redis = FakeAsyncRedis(decode_responses=True)
    kora = KoraSpeech()
    builder = VocabularyBuilder(redis, Model(), Stt(), Speech(), kora_speech=kora)
    await builder.propose("tg-5", "overwhelmed")
    card = await builder.confirm("tg-5")
    await builder.select("tg-5", card["id"], True)
    turn = await builder.start("tg-5")
    assert turn["audio"] == "a29yYS1hdWRpbw=="
    assert kora.calls == [(turn["text"], 0.9, "plain")]
    await builder.save_subtitle("tg-5", 44, turn["text"])
    restarted = VocabularyBuilder(redis, Model(), Stt(), Speech(), kora_speech=kora)
    assert await restarted.subtitle("tg-5", 44) == turn["text"]
