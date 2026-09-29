from app.onboarding_model import REVIEW_SYSTEM, parse_review
from app.onboarding_review import closing_lines, fluency_metrics, grounded_callback, select_examples
from app.stt import speech_words


def test_repeated_mistake_outranks_an_earlier_one_off():
    turns = [
        {"corrections": [
            {"wrong": "I work in startup", "better": "I work at a startup", "kind": "grammar"},
            {"wrong": "made a photo", "better": "took a photo", "kind": "word"},
        ]},
        {"corrections": [
            {"wrong": "I am agree", "better": "I agree", "kind": "grammar"},
            {"wrong": "very interesting", "better": "really fun", "kind": "natural"},
        ]},
        {"corrections": [{"wrong": "I am agree", "better": "I agree", "kind": "grammar"}]},
    ]
    picked = select_examples(turns)
    assert picked["grammar"][0] == {"wrong": "I am agree", "better": "I agree"}
    assert picked["grammar"][1]["wrong"] == "I work in startup"
    assert [item["wrong"] for item in picked["vocabulary"]] == ["made a photo", "very interesting"]


def test_callback_stays_inside_the_closing_frame():
    empty_subtitle, empty_spoken = closing_lines(None)
    assert empty_subtitle.startswith(
        "You know what, I really enjoyed talking with you. I feel like I know you a little better now. 😊"
    )
    assert "😊" not in empty_spoken
    assert empty_spoken.endswith("Let me show you what I noticed.")

    callback = grounded_callback(
        "And your startup sounds really interesting — I hope you’ll tell me more about it sometime.",
        ["I am building a startup with two friends."],
    )
    subtitle, spoken = closing_lines(callback)
    assert spoken.index("enjoyed talking with you") < spoken.index("startup") < spoken.index("I feel like I know you")
    assert spoken.startswith("You know what")
    assert spoken.endswith("Let me show you what I noticed.")
    assert grounded_callback(
        "And your startup sounds really interesting.",
        ["I work as a developer. I like movies. I need English for work."],
    ) is None
    assert "I like movies" in REVIEW_SYSTEM
    assert "I work as a developer" in REVIEW_SYSTEM
    assert "I need English for work" in REVIEW_SYSTEM


def test_fluency_uses_word_timings_inside_one_recording():
    words = [
        {"w": "I", "s": 0.0, "e": 0.2},
        {"w": "um", "s": 1.2, "e": 1.5},
        {"w": "build", "s": 1.6, "e": 2.0},
    ]
    later = [
        {"w": "Hello", "s": 0.0, "e": 0.5},
        {"w": "there", "s": 0.6, "e": 18.0},
    ]
    metrics = fluency_metrics([
        {"transcript": "I um build", "seconds": 2.0, "words": words},
        {"transcript": "Hello there", "seconds": 18.0, "words": later},
    ])
    assert metrics["longPauses"] == 1
    assert metrics["fillers"] == 1
    assert metrics["longestStretchSec"] == 18
    assert metrics["paceWpm"] == round(5 / 20 * 60)

    without_words = fluency_metrics([
        {"transcript": "I build software for clients", "seconds": 10.0, "words": []},
    ])
    assert without_words["paceWpm"] == round(5 / 10 * 60)
    assert without_words["longPauses"] is None
    assert without_words["fillers"] is None
    assert without_words["longestStretchSec"] is None

    almost = fluency_metrics([
        {"transcript": "I build", "seconds": 2.0, "words": [
            {"w": "I", "s": 0.0, "e": 0.2},
            {"w": "like", "s": 1.0, "e": 1.4},
        ]},
    ])
    assert almost["longPauses"] == 0
    assert almost["fillers"] == 0


def test_speech_words_keep_compact_timings():
    words = speech_words({
        "words": [
            {"word": "Hello", "start": 0.0, "end": 0.4},
            {"word": " ", "start": 0.4, "end": 0.5},
            {"text": "there", "start": 0.5, "end": 0.9},
            {"word": "nope", "start": "late", "end": 1.0},
        ]
    })
    assert words == [{"w": "Hello", "s": 0.0, "e": 0.4}, {"w": "there", "s": 0.5, "e": 0.9}]


def test_parse_review_rejects_a_score_outside_the_scale():
    raw = (
        '{"callback":null,"levelText":"You keep going.",'
        '"grammarScore":62,"grammarText":"A few patterns.","vocabularyScore":71,"vocabularyText":"Enough words.",'
        '"fluencyScore":68,"fluencyText":"You keep moving."}'
    )
    parsed = parse_review(raw)
    assert parsed["callback"] is None
    assert parsed["grammarScore"] == 62
    broken = raw.replace('"grammarScore":62', '"grammarScore":"B1"')
    try:
        parse_review(broken)
    except ValueError:
        return
    raise AssertionError("letter score was accepted")
