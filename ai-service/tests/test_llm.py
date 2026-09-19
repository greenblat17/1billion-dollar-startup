from app.llm import parse_corrected, parse_reply


def test_parse_reply_reads_reply() -> None:
    assert parse_reply('{"reply":"Nice — what did you buy?"}') == "Nice — what did you buy?"


def test_parse_corrected_reads_text() -> None:
    assert parse_corrected('{"corrected":"How did I celebrate it?"}') == "How did I celebrate it?"
