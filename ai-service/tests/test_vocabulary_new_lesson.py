from __future__ import annotations

import json

import pytest

from app.tts import TtsAudio
from app.vocabulary_builder import VocabularyBuilder


class Speech:
    async def speed(self, session_id):
        return 1.0

    async def synthesize(self, session_id, text):
        return TtsAudio(text.encode(), "audio/ogg")


class NoStt:
    async def transcribe(self, *args, **kwargs):
        raise AssertionError("text answers must not use STT")


class TutorModel:
    def __init__(self):
        self.operations = []

    async def complete_json(self, system, payload, **kwargs):
        data = json.loads(payload)
        operation = kwargs["operation"]
        self.operations.append(operation)
        if operation == "vocabulary_card":
            word = data["input"]
            meaning = {"small talk": "a short friendly chat about everyday things",
                       "test": "to check whether something works",
                       "figure out": "to understand something after thinking"}.get(word, "to handle a situation")
            return json.dumps({"word": word, "meaning": meaning,
                               "translation": "значение", "example": f"I can {word} today."})
        if operation == "vocabulary_tutor":
            word = data["target"]["word"]
            phase = data["phase"]
            action = data["action"]
            if action == "hint":
                return json.dumps({"feedback": "Think about the situation.", "question": "",
                                   "category": "", "matchedPhrase": "", "reason": ""})
            transcript = data["transcript"]
            category = ("" if action == "opening" else
                        "conversation" if "what should i answer" in transcript.lower() else
                        "understood" if phase == "Understand" else
                        "used" if word in transcript else "natural_without_target")
            next_phase = (phase if action == "opening" or category not in ("understood", "used")
                          else data["nextPhase"])
            next_word = (data["nextTarget"] if category in ("understood", "used")
                         and data["nextTarget"] is not None else data["target"])
            if next_phase == "Understand":
                question = f"{next_word['word']} means {next_word['meaning']}. Is that familiar?"
            elif next_phase == "Use":
                question = ({"small talk": "What could you do to make the first minute less awkward?",
                             "test": "What would you do to check that the feature works?",
                             "figure out": "What would you do to understand why the app broke?"}
                            .get(next_word["word"], "What would you do in that situation?"))
            else:
                question = ""
            return json.dumps({"feedback": "That makes sense." if action != "opening" else "Let's begin.",
                               "question": question, "category": category,
                               "matchedPhrase": word if category == "used" else "",
                               "reason": "The response is relevant."})
        if operation == "vocabulary_card_prompt":
            word = data["target"]["word"]
            prompt = (f"What does {word} mean?" if data["direction"] == "word_to_meaning" else
                      f"Which expression means {data['target']['meaning']}?")
            return json.dumps({"prompt": prompt})
        if operation == "vocabulary_card_grade":
            transcript = data["transcript"].lower()
            word = data["target"]["word"].lower()
            direction = data["direction"]
            give_up = "don't know" in transcript
            correct = (word in transcript if direction == "meaning_to_word" else
                       "understand" in transcript or "check" in transcript or "friendly chat" in transcript) and not give_up
            return json.dumps({"correct": correct, "uncertain": transcript == "unclear", "giveUp": give_up,
                               "feedback": "That's right." if correct else "Think about today's situation.",
                               "reason": "The response matches." if correct else "The response does not match."})
        if operation == "vocabulary_hint":
            return json.dumps({"hint": f"Clue number {data['hintNumber']}: think of the earlier example."})
        raise AssertionError(operation)


async def start_lesson(builder, session_id, words):
    for word in words:
        await builder.propose(session_id, word)
        card = await builder.confirm(session_id)
        await builder.select(session_id, card["id"], True)
    return (await builder.start(session_id))["lesson"]["id"]


@pytest.mark.asyncio
async def test_new_lesson_uses_one_tutor_response_for_opening():
    class LongOpeningModel(TutorModel):
        def __init__(self):
            super().__init__()
            self.openings = 0

        async def complete_json(self, system, payload, **kwargs):
            if kwargs["operation"] == "vocabulary_tutor" and json.loads(payload)["action"] == "opening":
                self.openings += 1
                if self.openings == 1:
                    return json.dumps({"feedback": "word " * 60, "question": "Is that familiar?",
                                       "category": "", "matchedPhrase": "", "reason": ""})
            return await super().complete_json(system, payload, **kwargs)

    model = LongOpeningModel()
    builder = VocabularyBuilder(None, model, NoStt(), Speech())
    await builder.propose("short-opening", "figure out")
    card = await builder.confirm("short-opening")
    await builder.select("short-opening", card["id"], True)

    opening = await builder.start("short-opening")
    assert model.openings == 1
    assert opening["text"].endswith("Is that familiar?")


@pytest.mark.asyncio
async def test_one_word_moves_directly_from_understand_to_use_and_automatic_card():
    model = TutorModel()
    builder = VocabularyBuilder(None, model, NoStt(), Speech())
    lesson_id = await start_lesson(builder, "one", ["figure out"])
    first = await builder.text("one", lesson_id, 1, "Yes, that happened to me.")
    assert (first["lesson"]["phase"], first["lesson"]["index"]) == ("Use", 1)
    assert first["text"].count("Now let's try using it in your own sentence.") == 1
    assert "figure out" not in first["lesson"]["prompt"]
    assert "figure out" not in first["text"]
    assert "vocabulary_use_check" not in model.operations
    second = await builder.text("one", lesson_id, 2, "I would figure out the problem carefully.")
    assert (second["lesson"]["status"], second["lesson"]["index"]) == ("cards", 2)
    assert "quick word game" in second["text"]
    assert "What does figure out mean?" in second["text"]
    assert await builder.text("one", lesson_id, 2, "duplicate") == second
    finished = await builder.text("one", lesson_id, 3, "It means understanding something.")
    assert finished["lesson"]["status"] == "finished"
    assert finished["lesson"]["index"] == 3
    assert "word game: correct" in finished["summary"]
    trace = await builder.review("one", lesson_id)
    assert [event["phase"] for event in trace["events"] if event["type"] == "answer"] == [
        "Understand", "Use", "Word game"]
    assert [(event["fromIndex"], event["toIndex"]) for event in trace["events"]
            if event["type"] == "transition"] == [(0, 1), (1, 2), (2, 3)]


@pytest.mark.asyncio
async def test_two_words_run_understand_then_use_for_each_before_cards():
    builder = VocabularyBuilder(None, TutorModel(), NoStt(), Speech())
    lesson_id = await start_lesson(builder, "two", ["small talk", "test"])
    phases = []
    prompts = []
    spoken = []
    for number, answer in enumerate(("Yes", "I would make small talk about the event.",
                                     "Yes", "I would test the feature.",
                                     "small talk", "It means understanding."), 1):
        turn = await builder.text("two", lesson_id, number, answer)
        phases.append((turn["lesson"]["phase"], turn["lesson"]["index"]))
        prompts.append(turn["lesson"]["prompt"])
        spoken.append(turn["text"])
    assert phases == [("Use", 1), ("Understand", 2), ("Use", 3),
                      ("Word game", 4), ("Word game", 5), ("Word game", 6)]
    assert turn["lesson"]["status"] == "finished"
    assert "first minute" in prompts[0]
    assert "test means" in prompts[1]
    assert "feature works" in prompts[2]
    assert sum(text.count("Now let's try using it in your own sentence.")
               for text in spoken) == 2
    assert "Now let's try using it in your own sentence." in spoken[0]
    assert "Now let's try using it in your own sentence." in spoken[2]
    trace = await builder.review("two", lesson_id)
    first_id, second_id = (word["id"] for word in trace["words"])
    assert [(event["phase"], event["wordId"]) for event in trace["events"]
            if event["type"] == "answer"] == [
                ("Understand", first_id), ("Use", first_id),
                ("Understand", second_id), ("Use", second_id),
                ("Word game", first_id), ("Word game", second_id)]


@pytest.mark.asyncio
async def test_active_format_four_lesson_keeps_grouped_order_and_card_delivery_id():
    builder = VocabularyBuilder(None, TutorModel(), NoStt(), Speech())
    lesson_id = await start_lesson(builder, "format-four", ["small talk", "test"])
    builder.memory["format-four"]["lesson"]["format"] = 4
    first = await builder.text("format-four", lesson_id, 1, "Yes")
    assert (first["lesson"]["format"], first["lesson"]["phase"]) == (4, "Understand")
    second = await builder.text("format-four", lesson_id, 2, "Yes")
    assert (second["lesson"]["format"], second["lesson"]["phase"]) == (4, "Use")
    await builder.text("format-four", lesson_id, 3, "I would make small talk about the event.")
    fourth = await builder.text("format-four", lesson_id, 4, "I would test the feature.")
    assert (fourth["lesson"]["format"], fourth["lesson"]["phase"]) == (4, "Word game")
    assert fourth.get("turnId")
    await builder.text("format-four", lesson_id, 5, "small talk")
    finished = await builder.text("format-four", lesson_id, 6, "I understand the meaning.")
    assert (finished["lesson"]["format"], finished["lesson"]["status"]) == (4, "finished")


@pytest.mark.asyncio
async def test_card_has_unlimited_wrong_answers_and_second_give_up_reveals():
    builder = VocabularyBuilder(None, TutorModel(), NoStt(), Speech())
    lesson_id = await start_lesson(builder, "card", ["test"])
    await builder.text("card", lesson_id, 1, "Yes")
    await builder.text("card", lesson_id, 2, "I would test the problem.")
    for number in range(3, 7):
        wrong = await builder.text("card", lesson_id, number, "A different idea")
        assert wrong["lesson"]["status"] == "cards"
        assert wrong["lesson"]["index"] == 2
    first = await builder.text("card", lesson_id, 7, "I don't know")
    assert first["lesson"]["status"] == "cards"
    assert "Clue number" in first["text"]
    second = await builder.text("card", lesson_id, 8, "I don't know")
    assert second["lesson"]["status"] == "finished"
    assert "word game: not recalled" in second["summary"]
    assert second["lesson"]["index"] == 3


@pytest.mark.asyncio
async def test_use_question_with_ordinary_word_from_expression_is_delivered_once():
    class TalkingQuestionModel(TutorModel):
        def __init__(self):
            super().__init__()
            self.answer_calls = 0

        async def complete_json(self, system, payload, **kwargs):
            result = await super().complete_json(system, payload, **kwargs)
            if kwargs["operation"] == "vocabulary_tutor" and json.loads(payload)["action"] == "answer":
                self.answer_calls += 1
                candidate = json.loads(result)
                candidate["question"] = "How could you ease the wait by talking to someone?"
                return json.dumps(candidate)
            return result

    model = TalkingQuestionModel()
    builder = VocabularyBuilder(None, model, NoStt(), Speech())
    lesson_id = await start_lesson(builder, "check", ["small talk"])
    turn = await builder.text("check", lesson_id, 1, "Yeah")
    assert turn["lesson"]["phase"] == "Use"
    assert turn["lesson"]["prompt"] == "How could you ease the wait by talking to someone?"
    assert model.answer_calls == 1


@pytest.mark.asyncio
async def test_isolated_target_does_not_count_as_own_phrase():
    builder = VocabularyBuilder(None, TutorModel(), NoStt(), Speech())
    lesson_id = await start_lesson(builder, "isolated", ["test"])
    await builder.text("isolated", lesson_id, 1, "Yes")
    with pytest.raises(ValueError, match="unsupported vocabulary match"):
        await builder.text("isolated", lesson_id, 2, "test")
    assert (await builder.list("isolated"))["lesson"]["index"] == 1


@pytest.mark.asyncio
async def test_full_answer_can_be_the_supporting_phrase():
    class WholeAnswerMatchModel(TutorModel):
        async def complete_json(self, system, payload, **kwargs):
            result = await super().complete_json(system, payload, **kwargs)
            data = json.loads(payload)
            if kwargs["operation"] == "vocabulary_tutor" and data["phase"] == "Use":
                turn = json.loads(result)
                turn["matchedPhrase"] = data["transcript"]
                return json.dumps(turn)
            return result

    builder = VocabularyBuilder(None, WholeAnswerMatchModel(), NoStt(), Speech())
    lesson_id = await start_lesson(builder, "diagnostic", ["small talk"])
    await builder.text("diagnostic", lesson_id, 1, "Yes")
    answer = await builder.text("diagnostic", lesson_id, 2,
                                "We made small talk before the meeting.")
    assert answer["lesson"]["status"] == "cards"


@pytest.mark.asyncio
async def test_rejected_use_assessment_logs_the_exact_failed_check_without_transcript(caplog):
    class WrongMatchModel(TutorModel):
        async def complete_json(self, system, payload, **kwargs):
            result = await super().complete_json(system, payload, **kwargs)
            data = json.loads(payload)
            if kwargs["operation"] == "vocabulary_tutor" and data["phase"] == "Use":
                turn = json.loads(result)
                turn["matchedPhrase"] = "not in the learner's answer"
                return json.dumps(turn)
            return result

    builder = VocabularyBuilder(None, WrongMatchModel(), NoStt(), Speech())
    lesson_id = await start_lesson(builder, "diagnostic-reject", ["small talk"])
    await builder.text("diagnostic-reject", lesson_id, 1, "Yes")
    with caplog.at_level("WARNING", logger="app.vocabulary_builder"):
        with pytest.raises(ValueError, match="unsupported vocabulary match"):
            await builder.text("diagnostic-reject", lesson_id, 2,
                               "We made small talk before the meeting.")
    assert "matched_in_answer=False" in caplog.text
    assert "matched_has_target=False" in caplog.text
    assert "We made small talk before the meeting." not in caplog.text


@pytest.mark.asyncio
async def test_learner_agreement_reaches_use_without_a_second_model_check():
    model = TutorModel()
    builder = VocabularyBuilder(None, model, NoStt(), Speech())
    lesson_id = await start_lesson(builder, "question-check-context", ["figure out"])
    response = await builder.text("question-check-context", lesson_id, 1, "yeah sure")
    assert (response["lesson"]["phase"], response["lesson"]["index"]) == ("Use", 1)
    assert "vocabulary_use_check" not in model.operations


@pytest.mark.asyncio
async def test_clarification_and_natural_answer_without_target_stay_on_use():
    builder = VocabularyBuilder(None, TutorModel(), NoStt(), Speech())
    lesson_id = await start_lesson(builder, "clarify", ["small talk"])
    await builder.text("clarify", lesson_id, 1, "Yes")
    unclear = await builder.text("clarify", lesson_id, 2, "What should I answer?")
    assert (unclear["lesson"]["phase"], unclear["lesson"]["index"]) == ("Use", 1)
    natural = await builder.text("clarify", lesson_id, 3, "They could ask about the weekend.")
    assert (natural["lesson"]["phase"], natural["lesson"]["index"]) == ("Use", 1)
    used = await builder.text("clarify", lesson_id, 4,
                              "They could make small talk about the weekend, although he go early.")
    assert (used["lesson"]["status"], used["lesson"]["index"]) == ("cards", 2)


@pytest.mark.asyncio
async def test_five_words_complete_each_understand_use_pair_before_cards():
    builder = VocabularyBuilder(None, TutorModel(), NoStt(), Speech())
    words = ["small talk", "test", "figure out", "postpone", "reflect"]
    lesson_id = await start_lesson(builder, "five", words)
    assert all("reviewDueAt" not in item for item in builder.memory["five"]["items"])
    for word_number, word in enumerate(words):
        understood = await builder.text("five", lesson_id, 2 * word_number + 1, "Yes")
        assert (understood["lesson"]["index"], understood["lesson"]["phase"]) == (
            2 * word_number + 1, "Use")
        used = await builder.text("five", lesson_id, 2 * word_number + 2, f"I would try to {word} it.")
        assert used["lesson"]["index"] == 2 * word_number + 2
        assert used["lesson"]["phase"] == ("Word game" if word_number == 4 else "Understand")
    assert used["lesson"]["status"] == "cards"
    assert all("reviewDueAt" not in item for item in builder.memory["five"]["items"])
    for number, word in enumerate(words, 11):
        answer = word if (number - 11) % 2 == 0 else "I understand the meaning."
        turn = await builder.text("five", lesson_id, number, answer)
        assert turn["lesson"]["index"] == number
    assert turn["lesson"]["status"] == "finished"
    assert all(item.get("reviewDueAt") for item in builder.memory["five"]["items"])


@pytest.mark.asyncio
async def test_card_hint_can_repeat_unclear_answer_does_not_advance_and_end_is_partial():
    builder = VocabularyBuilder(None, TutorModel(), NoStt(), Speech())
    lesson_id = await start_lesson(builder, "partial", ["test"])
    await builder.text("partial", lesson_id, 1, "Yes")
    await builder.text("partial", lesson_id, 2, "I would test the feature.")
    for number in range(2):
        hint = await builder.hint("partial", lesson_id)
        assert hint["lesson"]["hints"] == number + 1
    unclear = await builder.text("partial", lesson_id, 3, "unclear")
    assert unclear["status"] == "repeat"
    assert unclear["lesson"]["index"] == 2
    partial = await builder.end("partial", lesson_id)
    assert partial["status"] == "finished"
    assert "word game: not completed" in partial["summary"]
    assert "reviewDueAt" not in builder.memory["partial"]["items"][0]
