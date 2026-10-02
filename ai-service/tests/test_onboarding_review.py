from pathlib import Path

import json

import pytest

from app.onboarding_model import REVIEW_MAX_TOKENS, REVIEW_SYSTEM, SYSTEM, OnboardingModel, parse_review
from app.onboarding_review import closing_lines, correction_candidates, explained_examples, fluency_metrics, grounded_callback, select_examples
from app.vocabulary_suggestions import select_vocabulary_suggestions
from app.onboarding_score import (
    SCORE_TABLE, apply_skill, band_start, overall_progress, skill_confidence, skill_score,
)
from app.stt import speech_words


def test_repeated_mistake_outranks_an_earlier_one_off():
    turns = [
        {"transcript": "I work in startup. I made a photo.", "corrections": [
            {"wrong": "I work in startup", "better": "I work at a startup", "kind": "grammar"},
            {"wrong": "made a photo", "better": "took a photo", "kind": "word"},
        ]},
        {"transcript": "I am agree. It is very interesting.", "corrections": [
            {"wrong": "I am agree", "better": "I agree", "kind": "grammar"},
            {"wrong": "very interesting", "better": "really fun", "kind": "natural"},
        ]},
        {"transcript": "I am agree.", "corrections": [{"wrong": "I am agree", "better": "I agree", "kind": "grammar"}]},
    ]
    candidates = correction_candidates(turns)
    picked = select_examples(candidates, {item["id"] for item in candidates})
    assert picked["grammar"][0] == {"wrong": "I am agree", "better": "I agree"}
    assert picked["grammar"][1]["wrong"] == "I work in startup"
    assert [item["wrong"] for item in picked["vocabulary"]] == ["made a photo", "very interesting"]


def test_callback_stays_inside_the_closing_frame():
    empty_subtitle, empty_spoken = closing_lines(None)
    assert empty_subtitle.startswith(
        "Thanks for sharing that with me. I really enjoyed talking with you. I feel like I know you a little better now. 😊"
    )
    assert "😊" not in empty_spoken
    assert empty_spoken.endswith("Let me show you what I noticed.")

    callback = grounded_callback(
        "Building a startup with your friends sounds exciting.",
        "I am building a startup with two friends.",
    )
    subtitle, spoken = closing_lines(callback)
    assert spoken.index("startup") < spoken.index("enjoyed talking with you") < spoken.index("I feel like I know you")
    assert spoken.startswith("Building a startup")
    assert spoken.endswith("Let me show you what I noticed.")
    assert grounded_callback(
        "And your startup sounds really interesting.",
        "I work as a developer. I like movies. I need English for work.",
    ) is None
    assert grounded_callback(
        "Building a startup sounds exciting.",
        "I need English for work.",
    ) is None
    assert grounded_callback(
        "I can see why English matters for your work.",
        "I need English for work.",
    ) == "I can see why English matters for your work."
    assert "I like movies" in REVIEW_SYSTEM
    assert "I work as a developer" in REVIEW_SYSTEM
    assert "I need English for work" in REVIEW_SYSTEM
    assert "exactly two concise diagnostic sentences" in REVIEW_SYSTEM
    assert "Absence of evidence is not evidence of inability." in REVIEW_SYSTEM
    assert "Absence of evidence is not evidence of inability." in SYSTEM
    assert "illustrative, not a checklist" in REVIEW_SYSTEM
    assert "not thresholds" in REVIEW_SYSTEM
    assert "0 to 100" not in REVIEW_SYSTEM.split("Do not return a score from 0 to 100.", 1)[1]
    prompt = " ".join(REVIEW_SYSTEM.split())
    assert 'Use "0" by default.' in prompt
    assert "Do not use shade merely to create variation between scores." in prompt
    assert "same story as that skill's band, position, and shade." in prompt
    assert '"shade"' not in SYSTEM


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
    assert almost["fillers"] is None


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


def test_spoken_correction_fixtures_record_edits_and_context():
    path = Path(__file__).parents[1] / "evals" / "spoken_corrections.json"
    cases = {case["id"]: case for case in json.loads(path.read_text())}
    assert len(cases) == 100
    positives = [case for case in cases.values() if case["expect"] == "correct"]
    assert len(positives) == 47
    assert all(case["expected_context"] in case["transcript"] for case in positives)
    assert all(case["expected_edits"] and all(
        edit["before"] != edit["after"] for edit in case["expected_edits"]
    ) for case in positives)
    assert len(cases["two-errors-two-phrases"]["expected_edits"]) == 2
    assert cases["pause-cuts-yesterday"]["expected_context"].startswith("Yesterday I go")
    assert cases["pause-cuts-if"]["expected_context"].startswith("If it will rain")
    assert cases["no-pause-two-sentences"]["expected_context"].startswith("Yesterday")
    assert len(cases["long-breath"]["expected_context"].split()) < 30


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
        '"grammarExplanations":["Use the base verb with I."],'
        '"vocabularyExplanations":[],'
        f'"grammar":{_skill_json()},'
        f'"vocabulary":{_skill_json("B1", "mid", ["concrete_lexis", "invented"])},'
        f'"fluency":{_skill_json("B2", "low", ["completed_turns", "timings_present"])}}}'
    )
    parsed = parse_review(raw)
    assert parsed["callback"] is None
    assert parsed["grammarExplanations"] == ["Use the base verb with I."]
    assert parsed["vocabularyExplanations"] == []
    assert parsed["grammar"]["band"] == "B1"
    assert parsed["grammar"]["position"] == "high"
    assert parsed["vocabulary"]["flags"] == ["concrete_lexis"]
    assert parsed["fluency"]["flags"] == ["completed_turns"]
    assert parsed["shade"] == "0"
    assert parsed["grammar"]["shade"] == "0"
    with pytest.raises(ValueError):
        parse_review(raw.replace('"levelText":"You keep going."', '"levelText":"You keep going.","shade":"high"', 1))
    with pytest.raises(ValueError):
        parse_review(raw.replace('"band":"B1"', '"band":62', 1))
    with pytest.raises(ValueError):
        parse_review(raw.replace('"position":"high"', '"position":"high","band":null', 1))


def test_overall_progress_uses_the_same_table_and_not_an_average():
    assert overall_progress("B1", "high") == {"overallScore": 57, "nextBand": "B2", "pointsToNext": 4}
    assert overall_progress("B1", "mid")["pointsToNext"] == 9
    assert overall_progress("B1", "mid", "++") == {"overallScore": 54, "nextBand": "B2", "pointsToNext": 7}
    assert overall_progress("C1", "high") == {"overallScore": 94, "nextBand": None, "pointsToNext": None}
    assert overall_progress("C2", None)["overallScore"] is None
    assert overall_progress(None, None)["overallScore"] is None
    grammar = skill_score("B1", "mid", "-")
    vocabulary = skill_score("B1", "mid", "++")
    fluency = skill_score("B1", "high", "-")
    assert (grammar, vocabulary, fluency) == (51, 54, 56)
    assert overall_progress("B1", "mid", "++")["overallScore"] != (grammar + vocabulary + fluency) / 3


def test_shade_stays_inside_its_cell_and_bands_start_at_the_lowest_score():
    assert [skill_score("B1", "mid", shade) for shade in ("--", "-", "0", "+", "++")] == [50, 51, 52, 53, 54]
    assert skill_score("A2", "high", "+") == skill_score("A2", "high", "++") == 44
    assert skill_score("B1", "low", "--") == skill_score("B1", "low", "-") == 46
    assert skill_score("C1", "mid", "+") == skill_score("C1", "mid", "++") == 91
    assert skill_score("C1", "high", "--") == skill_score("C1", "high", "-") == 93
    assert [band_start(band) for band in ("A1", "A2", "B1", "B2", "C1")] == [10, 30, 46, 61, 81]
    previous = None
    for band, positions in SCORE_TABLE.items():
        for position in ("low", "mid", "high"):
            scores = [skill_score(band, position, shade) for shade in ("--", "-", "0", "+", "++")]
            assert scores == sorted(scores)
            assert scores[2] == positions[position]
            if previous is not None:
                assert min(scores) > previous
            previous = max(scores)
    placed = apply_skill(
        {
            "band": "B1",
            "position": "mid",
            "shade": "++",
            "text": "You have enough words.",
            "notes": "Precise choices repeat.",
            "flags": ["concrete_lexis"],
        },
        120,
        "vocabulary",
    )
    assert placed["score"] == 54
    assert placed["shade"] == "++"


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
    assert scored["shade"] == "0"
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
    assert parsed["grammar"]["shade"] == "0"
    assert parsed["vocabulary"]["band"] is None
    shaded = parse_review(
        '{"callback":null,"levelText":"You keep going.","shade":"++",'
        '"grammar":{"band":"B1","position":"mid","shade":"-","text":"A few patterns.","notes":"Mostly steady."},'
        '"vocabulary":{"band":null,"position":null,"shade":"++","text":"Not enough.","notes":""},'
        '"fluency":{"band":"B1","position":"high","shade":"-","text":"You keep going.","notes":"Few long pauses."}}'
    )
    assert shaded["shade"] == "++"
    assert shaded["grammar"]["shade"] == "-"
    assert shaded["vocabulary"]["shade"] == "0"
    assert shaded["fluency"]["shade"] == "-"


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
    assert llm.max_tokens == REVIEW_MAX_TOKENS == 2400
    assert parsed["grammar"]["position"] == "high"


def test_candidates_require_exact_whole_phrase_and_changed_correction():
    candidates = correction_candidates([{
        "transcript": "I builds tools.",
        "corrections": [
            {"wrong": "build", "better": "builds", "kind": "grammar"},
            {"wrong": "I builds", "better": "I builds", "kind": "grammar"},
            {"wrong": "not spoken", "better": "invented", "kind": "word"},
            {"wrong": "I builds", "better": "I build", "kind": "grammar"},
        ],
    }])
    assert len(candidates) == 1
    assert candidates[0]["id"] == "0:3"
    assert select_examples(candidates, {"invented"}) == {"grammar": [], "vocabulary": []}


def test_verification_precedes_ranking_and_caps_each_skill_at_five():
    turns = []
    for phrase in ("the Rodri", "the Rodri", "I builds", "he work", "she go", "they goes", "we is", "you was"):
        turns.append({"transcript": phrase, "corrections": [
            {"wrong": phrase, "better": phrase + " corrected", "kind": "grammar"},
        ]})
    candidates = correction_candidates(turns)
    picked = select_examples(candidates, {item["id"] for item in candidates if item["wrong"] != "the Rodri"})
    assert [item["wrong"] for item in picked["grammar"]] == ["I builds", "he work", "she go", "they goes", "we is"]


def test_only_verified_examples_with_explanations_are_shown():
    examples = [{"wrong": "I builds", "better": "I build"}, {"wrong": "he work", "better": "he works"}]
    assert explained_examples(examples, ["Use the base verb with I.", ""]) == [
        {"wrong": "I builds", "better": "I build", "explanation": "Use the base verb with I."},
    ]


def test_vocabulary_suggestions_require_grounded_useful_pairs_and_no_vocabulary_correction():
    transcripts = ["I just use the same words every single time. I build software."]
    useful = {
        "original": "I just use the same words every single time",
        "alternative": "I tend to fall back on the same words",
        "explanation": "Fall back on describes relying on familiar words out of habit.",
    }
    proposed = [
        {"original": "I speak perfectly", "alternative": "I speak fluently", "explanation": "More precise."},
        {"original": "I build software", "alternative": "I develop software", "explanation": "Another verb."},
        useful, useful,
    ]
    grammar = [{"wrong": "I build software", "better": "I am building software"}]
    assert select_vocabulary_suggestions(proposed, transcripts, grammar, []) == [useful]
    assert select_vocabulary_suggestions([useful], transcripts, [{"wrong": useful["original"], "kind": "natural"}], []) == []
    assert select_vocabulary_suggestions([useful], transcripts, [], [{"wrong": "bad", "better": "good"}]) == []
    assert select_vocabulary_suggestions([useful], transcripts, [], []) == [useful]


@pytest.mark.asyncio
@pytest.mark.parametrize("selection", [[], ["0:0"], ["0:0", "0:0"], ["invented"], [1], None])
async def test_verifier_accepts_only_existing_ids(selection):
    candidates = correction_candidates([{
        "transcript": "I builds tools.",
        "corrections": [{"wrong": "I builds", "better": "I build", "kind": "grammar"}],
    }])

    class Llm:
        async def complete_json(self, system, data, **kwargs):
            sent = json.loads(data)["candidates"]
            assert sent[0]["transcript"] == "I builds tools."
            assert sent[0]["better"] == "I build"
            return json.dumps({"acceptedIds": selection})

    model = OnboardingModel(Llm())
    if selection is None or selection in (["invented"], [1]):
        with pytest.raises(ValueError):
            await model.verify_corrections(candidates)
    else:
        assert await model.verify_corrections(candidates) == set(selection)
