from __future__ import annotations

import json

import pytest
from fakeredis import FakeAsyncRedis

from app.stt import SttResult
from app.tts import TtsAudio
from app.vocabulary_builder import GRADE_RESPONSE_FORMAT, VocabularyBuilder
from app.vocabulary_builder import _trace_key


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
            phase = data["phase"]
            number = len(self.prompts)
            word = data["target"]["word"]
            prompt = (f"{word} means too much to manage. Have you faced too many tasks in situation {number}?"
                      if phase == "Understand" else
                      f"Tell me about your own experience in situation {number}."
                      if phase == "Use" else
                      f"Which expression fits too many tasks in a new situation {number}?")
            self.prompts.append(prompt)
            return json.dumps({"scenario": prompt} if phase == "Use" else {"prompt": prompt})
        if operation == "vocabulary_grade":
            word = data["target"]["word"]
            matched = data["transcript"][data["transcript"].lower().index(word):][:len(word)] if word in data["transcript"].lower() else ""
            phase = data["phase"]
            category = ("understood" if "don't understand" not in data["transcript"].lower() else "confused") if phase == "Understand" else (
                "used" if matched and len(data["transcript"].split()) > 1 else "natural_without_target" if not matched else "misuse") if phase == "Use" else (
                "recalled" if matched else "not_recalled")
            return json.dumps({"category": category, "uncertain": False,
                               "matchedPhrase": matched, "reason": "The answer shows " + category,
                               "feedback": "That fits." if matched else "That makes sense."})
        if operation == "vocabulary_hint":
            return json.dumps({"hint": "Think about too many tasks."})
        if operation == "vocabulary_card_prompt":
            return json.dumps({"prompt": "What does overwhelmed mean?" if data["direction"] == "word_to_meaning"
                               else "Which word means too much to manage?"})
        if operation == "vocabulary_card_grade":
            return json.dumps({"correct": "too much" in data["transcript"] or "overwhelmed" in data["transcript"],
                               "uncertain": False, "feedback": "Good.", "reason": "Meaning fits."})
        raise AssertionError(operation)


@pytest.mark.asyncio
async def test_spoken_question_is_not_cut_mid_sentence():
    class LongPromptModel(Model):
        async def complete_json(self, system, payload, **kwargs):
            if kwargs["operation"] == "vocabulary_prompt":
                question = "Tell me about a party where you did not know anyone. " * 6
                return json.dumps({"prompt": question + "What did you talk about?"})
            return await super().complete_json(system, payload, **kwargs)

    builder = VocabularyBuilder(None, LongPromptModel(), Stt(), Speech())
    await builder.propose("tg-long-prompt", "small talk")
    card = await builder.confirm("tg-long-prompt")
    await builder.select("tg-long-prompt", card["id"], True)
    started = await builder.start("tg-long-prompt")
    assert started["text"].endswith("What did you talk about?")
    assert len(started["text"]) > 300


@pytest.mark.asyncio
async def test_grade_uses_strict_schema_and_invalid_json_is_logged_without_content(caplog):
    class InvalidGradeModel(Model):
        grade_format = None

        async def complete_json(self, system, payload, **kwargs):
            if kwargs["operation"] == "vocabulary_grade":
                self.grade_format = kwargs["response_format"]
                return "not a JSON response with private learner content"
            return await super().complete_json(system, payload, **kwargs)

    model = InvalidGradeModel()
    builder = VocabularyBuilder(None, model, Stt(), Speech())
    await builder.propose("tg-invalid-grade", "overwhelmed")
    card = await builder.confirm("tg-invalid-grade")
    await builder.select("tg-invalid-grade", card["id"], True)
    lesson_id = (await builder.start("tg-invalid-grade"))["lesson"]["id"]
    with caplog.at_level("WARNING", logger="app.vocabulary_builder"):
        with pytest.raises(ValueError, match="invalid vocabulary JSON for vocabulary_grade"):
            await builder.voice("tg-invalid-grade", lesson_id, 1, b"private audio")
    assert model.grade_format == GRADE_RESPONSE_FORMAT
    assert (await builder.list("tg-invalid-grade"))["lesson"]["attempts"] == 0
    assert "operation=vocabulary_grade parser_error=Expecting value" in caplog.text
    assert "private learner content" not in caplog.text
    assert "private audio" not in caplog.text


@pytest.mark.asyncio
async def test_one_word_has_understand_use_recall_and_optional_game():
    clock, stt, model = Clock(), Stt(), Model()
    builder = VocabularyBuilder(None, model, stt, Speech(), clock)
    await builder.propose("tg-1", "overwhelmed")
    card = await builder.confirm("tg-1")
    await builder.select("tg-1", card["id"], True)
    start = await builder.start("tg-1")
    lesson_id = start["lesson"]["id"]
    assert start["lesson"]["phase"] == "Understand"
    assert "Use overwhelmed" not in start["text"]
    stt.text = "I had so many tasks yesterday."
    understood = await builder.voice("tg-1", lesson_id, 1, b"voice")
    assert understood["lesson"]["phase"] == "Use"
    assert "using overwhelmed in your own phrase" in understood["text"]
    duplicate = await builder.voice("tg-1", lesson_id, 1, b"voice")
    assert duplicate["turnId"] == understood["turnId"]
    assert duplicate["text"] == understood["text"]
    stt.text = "I was overwhelmed by work."
    used = await builder.voice("tg-1", lesson_id, 2, b"voice")
    assert used["lesson"]["phase"] == "Recall"
    assert "overwhelmed" not in used["lesson"]["prompt"]
    stt.text = "Overwhelmed"
    recalled = await builder.voice("tg-1", lesson_id, 3, b"voice")
    assert recalled["status"] == "offer"
    assert "One quick word game?" in recalled["text"]
    trace = await builder.review("tg-1", lesson_id)
    assert trace["words"][0]["meaning"] == card["meaning"]
    assert [e["transcript"] for e in trace["events"] if e["type"] == "answer"] == [
        "I had so many tasks yesterday.", "I was overwhelmed by work.", "Overwhelmed"]
    assert all(e["reason"] for e in trace["events"] if e["type"] == "answer")
    assert "mastered" not in (await builder.list("tg-1"))["items"][0]
    assert (await builder.list("tg-1"))["items"][0]["reviewDue"] is False
    card_turn = await builder.play("tg-1", lesson_id)
    assert card_turn["lesson"]["status"] == "cards"
    stt.text = "It means too much to manage."
    result = await builder.voice("tg-1", lesson_id, 4, b"voice")
    assert result["status"] == "finished"
    assert "own phrase: used" in result["summary"]
    assert "immediate recall" in result["summary"]
    clock.now += 24 * 3600
    assert (await builder.list("tg-1"))["items"][0]["reviewDue"] is True


@pytest.mark.asyncio
async def test_natural_answer_without_word_gets_second_attempt_not_english_error():
    stt = Stt()
    builder = VocabularyBuilder(None, Model(), stt, Speech())
    await builder.propose("tg-natural", "overwhelmed")
    card = await builder.confirm("tg-natural")
    await builder.select("tg-natural", card["id"], True)
    lesson_id = (await builder.start("tg-natural"))["lesson"]["id"]
    stt.text = "I had too many tasks yesterday."
    await builder.voice("tg-natural", lesson_id, 1, b"voice")
    stt.text = "I took a break."
    first = await builder.voice("tg-natural", lesson_id, 2, b"voice")
    assert first["lesson"]["phase"] == "Use"
    assert first["lesson"]["attempts"] == 1
    assert "using overwhelmed" in first["text"]
    assert "wrong" not in first["text"].lower()
    stt.text = "I felt overwhelmed after the meeting."
    second = await builder.voice("tg-natural", lesson_id, 3, b"voice")
    assert second["lesson"]["phase"] == "Recall"
    trace = await builder.review("tg-natural", lesson_id)
    answers = [e for e in trace["events"] if e["type"] == "answer"]
    assert answers[1]["category"] == "natural_without_target"
    assert answers[2]["category"] == "used"


@pytest.mark.asyncio
async def test_confusion_gets_new_explanation_then_advances_after_second_answer():
    stt, model = Stt(), Model()
    builder = VocabularyBuilder(None, model, stt, Speech())
    await builder.propose("tg-confused", "overwhelmed")
    card = await builder.confirm("tg-confused")
    await builder.select("tg-confused", card["id"], True)
    lesson_id = (await builder.start("tg-confused"))["lesson"]["id"]
    stt.text = "I don't understand."
    first = await builder.voice("tg-confused", lesson_id, 1, b"voice")
    assert first["lesson"]["phase"] == "Understand"
    assert first["lesson"]["attempts"] == 1
    assert model.prompts[1] != model.prompts[0]
    stt.text = "I still don't understand."
    second = await builder.voice("tg-confused", lesson_id, 2, b"voice")
    assert second["lesson"]["phase"] == "Use"
    assert "means too much to manage" in second["text"]
    trace = await builder.review("tg-confused", lesson_id)
    assert [event["category"] for event in trace["events"] if event["type"] == "answer"] == [
        "confused", "confused"]


@pytest.mark.asyncio
async def test_recall_hint_is_text_and_does_not_show_word():
    stt = Stt()
    builder = VocabularyBuilder(None, Model(), stt, Speech())
    await builder.propose("tg-recall-hint", "overwhelmed")
    card = await builder.confirm("tg-recall-hint")
    await builder.select("tg-recall-hint", card["id"], True)
    lesson_id = (await builder.start("tg-recall-hint"))["lesson"]["id"]
    stt.text = "My workload was too high."
    await builder.voice("tg-recall-hint", lesson_id, 1, b"voice")
    stt.text = "I was overwhelmed."
    await builder.voice("tg-recall-hint", lesson_id, 2, b"voice")
    hint = await builder.hint("tg-recall-hint", lesson_id)
    assert "audio" not in hint
    assert "overwhelmed" not in hint["text"].lower()
    stt.text = "Overwhelmed"
    result = await builder.voice("tg-recall-hint", lesson_id, 3, b"voice")
    assert result["status"] == "offer"
    summary = (await builder.skip("tg-recall-hint", lesson_id))["summary"]
    assert "recalled with a hint" in summary


@pytest.mark.asyncio
async def test_hint_is_text_only_and_timeout_has_partial_summary():
    clock, stt = Clock(), Stt()
    builder = VocabularyBuilder(None, Model(), stt, Speech(), clock)
    await builder.propose("tg-hint", "overwhelmed")
    card = await builder.confirm("tg-hint")
    await builder.select("tg-hint", card["id"], True)
    lesson_id = (await builder.start("tg-hint"))["lesson"]["id"]
    hint = await builder.hint("tg-hint", lesson_id)
    assert hint["lesson"]["attempts"] == 0 and "audio" not in hint
    assert any(e["type"] == "hint" for e in (await builder.review("tg-hint", lesson_id))["events"])
    clock.now += 301
    due = await builder.due()
    assert len(due) == 1 and due[0]["kind"] == "reminder"
    clock.now += 3300
    due = await builder.due()
    assert len(due) == 1 and due[0]["kind"] == "ended"
    assert "not completed" in due[0]["text"]


@pytest.mark.asyncio
async def test_two_words_complete_a_b_before_delayed_recall():
    stt, model = Stt(), Model()
    builder = VocabularyBuilder(None, model, stt, Speech())
    for word in ("overwhelmed", "postpone"):
        await builder.propose("tg-order", word)
        card = await builder.confirm("tg-order")
        await builder.select("tg-order", card["id"], True)
    lesson_id = (await builder.start("tg-order"))["lesson"]["id"]
    stt.text = "I had too much work."
    a1 = await builder.voice("tg-order", lesson_id, 1, b"voice")
    assert a1["lesson"]["phase"] == "Use"
    assert a1["lesson"]["word"]["word"] == "overwhelmed"
    stt.text = "I was overwhelmed."
    b1 = await builder.voice("tg-order", lesson_id, 2, b"voice")
    assert b1["lesson"]["phase"] == "Understand"
    assert b1["lesson"]["word"]["word"] == "postpone"
    stt.text = "The meeting could wait."
    await builder.voice("tg-order", lesson_id, 3, b"voice")
    stt.text = "I will postpone it."
    b2 = await builder.voice("tg-order", lesson_id, 4, b"voice")
    assert b2["lesson"]["phase"] == "Recall"
    assert b2["lesson"]["word"] is None
    assert "overwhelmed" not in b2["lesson"]["prompt"]
    assert model.prompts[4] != model.prompts[0]


@pytest.mark.asyncio
async def test_resume_unclear_voice_and_legacy_lesson():
    clock, speech, stt = Clock(), KoraSpeech(), Stt()
    builder = VocabularyBuilder(None, Model(), stt, Speech(), clock, kora_speech=speech)
    await builder.propose("tg-resume", "overwhelmed")
    card = await builder.confirm("tg-resume")
    await builder.select("tg-resume", card["id"], True)
    started = await builder.start("tg-resume")
    lesson_id = started["lesson"]["id"]
    resumed = await builder.resume("tg-resume", lesson_id)
    assert resumed["audio"] == started["audio"]
    assert resumed["lesson"]["attempts"] == 0
    stt.text = ""
    unclear = await builder.voice("tg-resume", lesson_id, 1, b"voice")
    assert unclear["status"] == "repeat" and unclear["lesson"]["attempts"] == 0
    builder.memory["tg-resume"]["lesson"] = {
        "id": "old", "status": "active", "words": [card], "index": 0,
        "attempts": 0, "hints": 0, "results": {}, "prompts": [], "lastActivity": 1000.0,
        "reminded": False, "processed": [], "prompt": "Old question."}
    assert (await builder.list("tg-resume"))["lesson"]["status"] == "finished"
    assert (await builder.start("tg-resume"))["lesson"]["id"] != "old"


@pytest.mark.asyncio
async def test_five_word_limit():
    builder = VocabularyBuilder(None, Model(), Stt(), Speech())
    for index in range(5):
        await builder.propose("tg-five", f"word{index}")
        card = await builder.confirm("tg-five")
        await builder.select("tg-five", card["id"], True)
    await builder.propose("tg-five", "sixth")
    sixth = await builder.confirm("tg-five")
    with pytest.raises(ValueError, match="five words"):
        await builder.select("tg-five", sixth["id"], True)
    assert (await builder.start("tg-five"))["lesson"]["total"] == 15


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
    review = await restarted.review("tg-4", lesson_id)
    assert [event for event in review["events"] if event["type"] == "answer"][-1]["transcript"] == stt.text
    assert await redis.ttl(_trace_key("tg-4", lesson_id)) > 0


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


@pytest.mark.asyncio
async def test_every_core_model_call_sees_current_lesson_history_and_delivery():
    class RecordingModel(Model):
        def __init__(self):
            super().__init__()
            self.calls = []

        async def complete_json(self, system, payload, **kwargs):
            if kwargs["operation"] in ("vocabulary_prompt", "vocabulary_grade", "vocabulary_hint"):
                self.calls.append((kwargs["operation"], json.loads(payload)))
            return await super().complete_json(system, payload, **kwargs)

    redis = FakeAsyncRedis(decode_responses=True)
    model, stt = RecordingModel(), Stt()
    builder = VocabularyBuilder(redis, model, stt, Speech())
    await builder.propose("tg-context", "overwhelmed")
    card = await builder.confirm("tg-context")
    await builder.select("tg-context", card["id"], True)
    start = await builder.start("tg-context")
    lesson_id = start["lesson"]["id"]
    await builder.delivery("tg-context", lesson_id, start["turnId"], 100)
    hint = await builder.hint("tg-context", lesson_id)
    await builder.delivery("tg-context", lesson_id, hint["turnId"], 101)
    stt.text = "I had too many tasks yesterday."
    first = await builder.voice("tg-context", lesson_id, 1, b"voice")
    grade = next(data for operation, data in model.calls if operation == "vocabulary_grade")
    assert [event["type"] for event in grade["lessonHistory"]] == ["spoken", "hint", "answer"]
    assert grade["lessonHistory"][0]["delivery"] == "delivered"
    assert grade["lessonHistory"][1]["text"] == hint["text"]
    next_prompt = [data for operation, data in model.calls if operation == "vocabulary_prompt"][-1]
    assert next_prompt["lessonHistory"][-1]["transcript"] == stt.text
    assert next_prompt["lessonHistory"][-1]["reason"]
    restarted = VocabularyBuilder(redis, model, stt, Speech())
    assert (await restarted.voice("tg-context", lesson_id, 1, b"voice"))["turnId"] == first["turnId"]
    await restarted.delivery("tg-context", lesson_id, first["turnId"], 102)
    replay = await restarted.voice("tg-context", lesson_id, 1, b"voice")
    assert replay["deliveredMessageId"] == 102
    trace = await restarted.review("tg-context", lesson_id)
    assert [event["eventId"] for event in trace["events"]] == list(range(1, len(trace["events"]) + 1))
    assert len([event for event in trace["events"] if event["type"] == "answer"]) == 1


@pytest.mark.asyncio
async def test_long_lesson_keeps_originals_and_supplies_summary():
    class SummaryModel(Model):
        def __init__(self):
            super().__init__()
            self.contexts = []
            self.summary_sources = []

        async def complete_json(self, system, payload, **kwargs):
            data = json.loads(payload)
            if kwargs["operation"] == "vocabulary_context_summary":
                self.summary_sources.append(data)
                return json.dumps({"summary": "Earlier conversation and reasons are in events 1 through 5."})
            if kwargs["operation"] in ("vocabulary_prompt", "vocabulary_grade"):
                self.contexts.append(data)
            return await super().complete_json(system, payload, **kwargs)

    model, stt = SummaryModel(), Stt()
    builder = VocabularyBuilder(None, model, stt, Speech(), context_window_tokens=6000)
    for word in ("overwhelmed", "postpone", "small talk"):
        await builder.propose("tg-long", word)
        card = await builder.confirm("tg-long")
        await builder.select("tg-long", card["id"], True)
    lesson_id = (await builder.start("tg-long"))["lesson"]["id"]
    for message_id, transcript in enumerate((
            "My work calendar was full of tasks from three different teams yesterday.",
            "I was overwhelmed by work.",
            "We could hold the meeting next week because our client is away.",
            "I will postpone it.",
            "My work calendar was full before that event.",
            "I enjoy small talk before an event."), 1):
        stt.text = transcript
        await builder.voice("tg-long", lesson_id, message_id, b"voice")
    assert any("earlierLessonSummary" in data for data in model.contexts)
    assert all(len(json.dumps(data).encode()) < 6000 for data in model.summary_sources)
    recall_context = next(data for data in model.contexts
                          if data.get("phase") == "Recall" and "earlierLessonSummary" in data)
    assert any("overwhelmed" in event.get("transcript", "").lower()
               for event in recall_context["lessonHistory"])
    reference_context = next(data for data in model.contexts
                             if data.get("transcript") == "My work calendar was full before that event.")
    assert any("work calendar was full of tasks" in event.get("transcript", "")
               for event in reference_context["lessonHistory"])
    trace = await builder.review("tg-long", lesson_id)
    assert [event["transcript"] for event in trace["events"] if event["type"] == "answer"][:6] == [
        "My work calendar was full of tasks from three different teams yesterday.",
        "I was overwhelmed by work.",
        "We could hold the meeting next week because our client is away.",
        "I will postpone it.",
        "My work calendar was full before that event.",
        "I enjoy small talk before an event.",
    ]


@pytest.mark.asyncio
async def test_context_summary_failure_does_not_count_the_voice_attempt():
    class FailingSummaryModel(Model):
        async def complete_json(self, system, payload, **kwargs):
            if kwargs["operation"] == "vocabulary_context_summary":
                raise RuntimeError("summary provider unavailable")
            return await super().complete_json(system, payload, **kwargs)

    stt = Stt()
    builder = VocabularyBuilder(None, FailingSummaryModel(), stt, Speech(),
                                context_window_tokens=6000)
    for word in ("overwhelmed", "postpone", "small talk"):
        await builder.propose("tg-summary-fail", word)
        card = await builder.confirm("tg-summary-fail")
        await builder.select("tg-summary-fail", card["id"], True)
    lesson_id = (await builder.start("tg-summary-fail"))["lesson"]["id"]
    transcripts = ("I had too many tasks yesterday.", "I was overwhelmed at work.",
                   "The meeting can wait.", "I will postpone it.",
                   "People chatted before the event.", "I enjoy small talk.")
    for message_id, transcript in enumerate(transcripts, 1):
        stt.text = transcript
        before = (await builder.list("tg-summary-fail"))["lesson"]
        try:
            await builder.voice("tg-summary-fail", lesson_id, message_id, b"voice")
        except RuntimeError as error:
            assert str(error) == "summary provider unavailable"
            after = (await builder.list("tg-summary-fail"))["lesson"]
            assert (after["index"], after["attempts"]) == (before["index"], before["attempts"])
            assert all(event.get("transcript") != transcript for event in
                       (await builder.review("tg-summary-fail", lesson_id))["events"])
            break
    else:
        pytest.fail("the reduced context window did not exercise summary failure")


@pytest.mark.asyncio
async def test_active_previous_context_format_can_finish_without_forced_close():
    redis = FakeAsyncRedis(decode_responses=True)
    stt = Stt()
    builder = VocabularyBuilder(redis, Model(), stt, Speech())
    await builder.propose("tg-old-context", "overwhelmed")
    card = await builder.confirm("tg-old-context")
    await builder.select("tg-old-context", card["id"], True)
    lesson_id = (await builder.start("tg-old-context"))["lesson"]["id"]
    state = await builder._get("tg-old-context")
    state["lesson"].pop("contextVersion")
    await builder._save("tg-old-context", state)
    resumed = VocabularyBuilder(redis, Model(), stt, Speech())
    assert (await resumed.list("tg-old-context"))["lesson"]["status"] == "active"
    stt.text = "I had too much work."
    assert (await resumed.voice("tg-old-context", lesson_id, 1, b"voice"))["lesson"]["phase"] == "Use"
