import json

import pytest

from app.llm import Correction, NOTES_MAX_TOKENS, NOTES_SYSTEM, OpenAiChatModel, parse_corrections, parse_reply
from app.review import parse_review


def decision(wrong="I am agree with you", better="I agree with you", kind="grammar", **overrides):
    return {
        "wrong": wrong, "better": better, "kind": kind,
        "reason": "Agree is a verb and does not take am.", "confidence": "high",
        "definitely_wrong": True, "is_spoken_language_artifact": False,
        "is_asr_uncertain": False, "worth_showing": True, "understandable_alone": True,
        **overrides,
    }


def parse(notes, transcript="I am agree with you."):
    return parse_corrections(json.dumps({"notes": notes}), transcript)


def test_parse_reply_reads_reply():
    assert parse_reply('{"reply":"Nice — what did you buy?"}') == "Nice — what did you buy?"


def test_correction_keeps_context_and_only_public_fields():
    correction = parse([decision()])[0]
    assert correction == Correction("I am agree with you", "I agree with you", "grammar")
    assert correction.to_json() == {
        "wrong": "I am agree with you", "better": "I agree with you", "kind": "grammar",
    }
    assert correction.note == "I am agree with you|||I agree with you"


@pytest.mark.parametrize("field,value", [
    ("confidence", "medium"), ("confidence", "low"), ("definitely_wrong", False),
    ("is_spoken_language_artifact", True), ("is_asr_uncertain", True),
    ("worth_showing", False), ("understandable_alone", False), ("reason", ""),
    ("worth_showing", "true"), ("is_asr_uncertain", 0), ("kind", "style"),
    ("wrong", 123), ("better", "I am agree with you"),
])
def test_uncertain_or_invalid_candidate_is_omitted(field, value):
    assert parse([decision(**{field: value})]) == []


@pytest.mark.parametrize("field", list(decision()))
def test_every_decision_field_is_required(field):
    note = decision()
    del note[field]
    assert parse([note]) == []


@pytest.mark.parametrize("raw", ['{}', '{"notes":[]}', '{"notes":"bad"}', '[]', '{bad',
                                      '{"notes":["I am agree|||I agree"]}'])
def test_malformed_or_legacy_output_does_not_bypass_checks(raw):
    assert parse_corrections(raw, "I am agree") == []


def test_exact_original_whole_words_and_contractions_are_required():
    assert parse([decision(wrong="am agree", better="agree")], "I am agreement.") == []
    assert parse([decision(wrong="can", better="could")], "I can't go.") == []
    assert parse([decision(wrong="can", better="could")], "I can’t go.") == []
    assert parse([decision()], "I agree with you.") == []


def test_duplicates_and_overlapping_fragments_are_removed():
    notes = [decision(), decision(), decision(wrong="am agree", better="agree")]
    assert len(parse(notes)) == 1


def test_limit_and_priority_apply_after_filtering():
    notes = [decision(wrong=f"fragment {i}", better=f"fixed {i}", kind=kind)
             for i, kind in enumerate(["natural", "word", "natural", "grammar"])]
    notes.insert(0, decision(worth_showing=False))
    corrections = parse(notes, "fragment 0. fragment 1. fragment 2. fragment 3.")
    assert [(item.wrong, item.kind) for item in corrections] == [
        ("fragment 3", "grammar"), ("fragment 1", "word"), ("fragment 0", "natural"),
    ]


@pytest.mark.asyncio
async def test_live_notes_path_uses_strict_parser_and_separate_output_budget():
    class Model(OpenAiChatModel):
        async def _complete(self, messages, temperature, max_tokens=None):
            assert messages[0]["content"] == NOTES_SYSTEM
            assert max_tokens == NOTES_MAX_TOKENS
            return json.dumps({"notes": [decision(), decision(wrong="absent", better="invented")]})

    model = Model(None, "test")
    assert await model.complete_notes("I am agree with you.") == [Correction("I am agree with you", "I agree with you")]
    assert await model.complete_notes("I agree with you.") == []


def test_parse_review_orders_grammar_then_vocabulary() -> None:
    raw = json.dumps(
        {
            "steps": [
                {
                    "metric": "Vocabulary",
                    "score": 84,
                    "lead": "Words",
                    "bullets": ["verbs"],
                    "examples": [],
                    "tip": "Tip v",
                },
                {
                    "metric": "Grammar",
                    "score": 78,
                    "lead": "Tenses",
                    "bullets": ["Past"],
                    "examples": [{"original": "I go", "improved": "I went"}],
                    "tip": "Tip g",
                },
            ]
        }
    )
    parsed = parse_review(raw)
    assert [step["metric"] for step in parsed["steps"]] == ["Grammar", "Vocabulary"]
    assert parsed["steps"][0]["score"] == 78
