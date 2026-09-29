import pytest

from app.onboarding_model import REVIEW_MAX_TOKENS, REVIEW_SYSTEM, SYSTEM, OnboardingModel, parse_review
from app.onboarding_review import closing_lines, fluency_metrics, grounded_callback, select_examples
from app.onboarding_score import SCORE_TABLE, apply_skill, skill_confidence, skill_score
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
    assert "Absence of evidence is not evidence of inability." in REVIEW_SYSTEM
    assert "Absence of evidence is not evidence of inability." in SYSTEM
    assert "illustrative, not a checklist" in REVIEW_SYSTEM
    assert "not thresholds" in REVIEW_SYSTEM
    assert "0 to 100" not in REVIEW_SYSTEM.split("Do not return a score from 0 to 100.", 1)[1]


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


def _skill_json(band="B1", position="high", flags=None):
    if flags is None:
        flags = ["simple_clauses"]
    return (
        '{"band":' + ("null" if band is None else f'"{band}"')
        + ',"position":' + ("null" if position is None else f'"{position}"')
        + ',"text":"A few patterns.","notes":"Linked clauses hold.",'
        + '"flags":[' + ",".join(f'"{flag}"' for flag in flags) + "]}"
    )


def test_parse_review_maps_bands_and_rejects_a_raw_score():
    raw = (
        '{"callback":null,"levelText":"You keep going.",'
        f'"grammar":{_skill_json()},'
        f'"vocabulary":{_skill_json("B1", "mid", ["concrete_lexis", "invented"])},'
        f'"fluency":{_skill_json("B2", "low", ["completed_turns", "timings_present"])}}}'
    )
    parsed = parse_review(raw)
    assert parsed["callback"] is None
    assert parsed["grammar"]["band"] == "B1"
    assert parsed["grammar"]["position"] == "high"
    assert parsed["vocabulary"]["flags"] == ["concrete_lexis"]
    assert parsed["fluency"]["flags"] == ["completed_turns"]
    with pytest.raises(ValueError):
        parse_review(raw.replace('"band":"B1"', '"band":62', 1))
    with pytest.raises(ValueError):
        parse_review(raw.replace('"position":"high"', '"position":"high","band":null', 1))


def test_score_table_is_closed_and_null_stays_empty():
    assert skill_score("B1", "high") == 57
    assert skill_score("B2", "low") == 63
    assert skill_score("C1", "high") == 94
    assert skill_score(None, None) is None
    for band, positions in SCORE_TABLE.items():
        for position, score in positions.items():
            assert skill_score(band, position) == score


def test_confidence_caps_at_two_minutes_and_ignores_bare_duration():
    grammar = ["simple_clauses", "tense_contrast", "linked_clauses", "complex_clause", "complex_repeated"]
    assert skill_confidence(grammar, set(grammar), 120) == 0.6
    assert skill_confidence([], set(grammar), 120) == 0.15
    scored = apply_skill(
        {
            "band": None,
            "position": None,
            "text": "Not much speech yet.",
            "notes": "Only isolated words.",
            "flags": [],
        },
        120,
        "fluency",
        timings=True,
    )
    assert scored["score"] is None
    assert scored["flags"] == ["timings_present"]
    assert scored["confidence"] < 0.6


def test_parse_review_accepts_a_skill_without_flags_or_notes():
    raw = (
        '{"callback":null,"levelText":"You keep going.",'
        '"grammar":{"band":"b1","position":"High","text":"A few patterns."},'
        f'"vocabulary":{_skill_json(None, None, [])},'
        f'"fluency":{_skill_json("B2", "low", ["completed_turns"])}}}'
    )
    parsed = parse_review(raw)
    assert parsed["grammar"]["band"] == "B1"
    assert parsed["grammar"]["position"] == "high"
    assert parsed["grammar"]["flags"] == []
    assert parsed["grammar"]["notes"] == ""
    assert parsed["vocabulary"]["band"] is None


@pytest.mark.asyncio
async def test_compose_review_reserves_room_for_the_skill_json():
    class Llm:
        def __init__(self):
            self.max_tokens = None

        async def complete_json(self, system, data, temperature=0.0, max_tokens=None):
            self.max_tokens = max_tokens
            return (
                '{"callback":null,"levelText":"You keep going.",'
                f'"grammar":{_skill_json()},'
                f'"vocabulary":{_skill_json("B1", "mid", ["concrete_lexis"])},'
                f'"fluency":{_skill_json("B2", "low", ["completed_turns"])}}}'
            )

    llm = Llm()
    parsed = await OnboardingModel(llm).compose_review({})
    assert llm.max_tokens == REVIEW_MAX_TOKENS == 1200
    assert parsed["grammar"]["position"] == "high"
