import json

from app.llm import parse_llm_turn


def test_parse_llm_turn_reads_reply_and_notes() -> None:
    turn = parse_llm_turn(
        '{"reply":"Nice — what did you buy?","notes":[{"wrong":"I was in Turkey","better":"I went to Turkey"}]}',
    )
    assert turn.reply_text == "Nice — what did you buy?"
    assert turn.notes == ["I was in Turkey|||I went to Turkey"]


def test_parse_llm_turn_empty_notes() -> None:
    turn = parse_llm_turn('{"reply":"Sounds good. What next?","notes":[]}')
    assert turn.reply_text == "Sounds good. What next?"
    assert turn.notes == []


def test_parse_llm_turn_caps_notes_at_three() -> None:
    notes = [{"wrong": f"w{i}", "better": f"b{i}"} for i in range(4)]
    turn = parse_llm_turn(json.dumps({"reply": "Ok", "notes": notes}))
    assert turn.notes == ["w0|||b0", "w1|||b1", "w2|||b2"]
