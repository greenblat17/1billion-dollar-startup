import json

from app.llm import Correction, parse_corrections, parse_reply
from app.review import parse_review


def test_parse_reply_reads_reply() -> None:
    assert parse_reply('{"reply":"Nice — what did you buy?"}') == "Nice — what did you buy?"


def test_parse_corrections_reads_kind() -> None:
    corrections = parse_corrections(
        '{"notes":[{"wrong":"made a photo","better":"took a photo","kind":"word"}]}',
    )
    assert corrections == [Correction("made a photo", "took a photo", "word")]
    assert corrections[0].note == "made a photo|||took a photo"


def test_parse_corrections_defaults_unknown_kind_to_grammar() -> None:
    raw = json.dumps(
        {
            "notes": [
                {"wrong": "you is", "better": "you are"},
                {"wrong": "go to home", "better": "go home", "kind": "Style"},
                "I was|||I went",
            ]
        }
    )
    assert [item.kind for item in parse_corrections(raw)] == ["grammar", "grammar", "grammar"]


def test_parse_corrections_empty() -> None:
    assert parse_corrections('{"notes":[]}') == []


def test_parse_corrections_keeps_top_three_by_priority() -> None:
    payload = [
        {"wrong": "n1", "better": "b1", "kind": "natural"},
        {"wrong": "w1", "better": "b2", "kind": "word"},
        {"wrong": "n2", "better": "b3", "kind": "natural"},
        {"wrong": "g1", "better": "b4", "kind": "grammar"},
    ]
    corrections = parse_corrections(json.dumps({"notes": payload}))
    assert [(item.wrong, item.kind) for item in corrections] == [
        ("g1", "grammar"),
        ("w1", "word"),
        ("n1", "natural"),
    ]


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
