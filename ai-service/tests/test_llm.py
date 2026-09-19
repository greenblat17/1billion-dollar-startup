import json

from app.llm import parse_notes, parse_reply


def test_parse_reply_reads_reply() -> None:
    assert parse_reply('{"reply":"Nice — what did you buy?"}') == "Nice — what did you buy?"


def test_parse_notes_reads_pairs() -> None:
    notes = parse_notes(
        '{"notes":[{"wrong":"I was in Turkey","better":"I went to Turkey"}]}',
    )
    assert notes == ["I was in Turkey|||I went to Turkey"]


def test_parse_notes_empty() -> None:
    assert parse_notes('{"notes":[]}') == []


def test_parse_notes_caps_at_three() -> None:
    payload = [{"wrong": f"w{i}", "better": f"b{i}"} for i in range(4)]
    assert parse_notes(json.dumps({"notes": payload})) == ["w0|||b0", "w1|||b1", "w2|||b2"]
