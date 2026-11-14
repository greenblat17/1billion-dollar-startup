from pathlib import Path

import json

import pytest

from app.llm import CompletionTokenLimitError
from app.learner_model import (
    ALTERNATIVE_REVIEW_SYSTEM,
    LearnerModel,
)
from app.review_content import correction_candidates, fluency_metrics, select_examples
from app.vocabulary_suggestions import discover_vocabulary_suggestions
from app.review_score import (
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
    assert picked["grammar"][0] == {"wrong": "I am agree", "better": "I agree", "explanation": ""}
    assert picked["grammar"][1]["wrong"] == "I work in startup"
    assert [item["wrong"] for item in picked["grammar"]] == [
        "I am agree", "I work in startup", "made a photo", "very interesting"]
    assert picked["vocabulary"] == []




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
        + ',"shade":"0","text":"A few patterns.","notes":"Linked clauses hold.",'
        + '"flags":[' + ",".join(f'"{flag}"' for flag in flags) + "]}"
    )




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


def test_verified_examples_reuse_saved_explanations_and_keep_legacy_pairs():
    candidates = correction_candidates([{
        "transcript": "I builds. He work.",
        "corrections": [
            {"wrong": "I builds", "better": "I build", "kind": "grammar", "explanation": "После I нужен build."},
            {"wrong": "He work", "better": "He works", "kind": "grammar"},
        ],
    }])
    assert select_examples(candidates, {item["id"] for item in candidates})["grammar"] == [
        {"wrong": "I builds", "better": "I build", "explanation": "После I нужен build."},
        {"wrong": "He work", "better": "He works", "explanation": ""},
    ]


def test_repeated_pair_uses_saved_explanation_when_first_copy_has_none():
    candidates = correction_candidates([
        {"transcript": "I builds.", "corrections": [
            {"wrong": "I builds", "better": "I build", "kind": "grammar"},
        ]},
        {"transcript": "I builds.", "corrections": [
            {"wrong": "I builds", "better": "I build", "kind": "grammar", "explanation": "После I нужен build."},
        ]},
    ])
    assert select_examples(candidates, {item["id"] for item in candidates})["grammar"] == [
        {"wrong": "I builds", "better": "I build", "explanation": "После I нужен build."},
    ]


def test_final_grammar_examples_include_legacy_word_errors_and_ignore_alternatives():
    candidates = correction_candidates([{
        "transcript": "I did a decision. We saw a big increase.",
        "corrections": [
            {"wrong": "I did a decision", "better": "I made a decision", "kind": "natural",
             "vocabularyCategory": "naturalness_error", "explanation": "Здесь говорят make a decision."},
            {"wrong": "big", "better": "substantial", "kind": "alternative"},
        ],
    }])
    assert len(candidates) == 1
    assert select_examples(candidates, {"0:0"})["grammar"] == [
        {"wrong": "I did a decision", "better": "I made a decision",
         "explanation": "Здесь говорят make a decision."},
    ]


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
            assert kwargs["model"] == "openai/gpt-4o-mini"
            return json.dumps({"acceptedIds": selection})

    model = LearnerModel(Llm())
    if selection is None or selection in (["invented"], [1]):
        with pytest.raises(ValueError):
            await model.verify_corrections(candidates)
    else:
        assert await model.verify_corrections(candidates) == set(selection)


@pytest.mark.asyncio
async def test_verifier_model_can_be_configured():
    class Llm:
        async def complete_json(self, system, data, **kwargs):
            assert kwargs["model"] == "custom/verifier"
            return '{"acceptedIds":[]}'

    assert await LearnerModel(Llm(), correction_verification_model="custom/verifier").verify_corrections([
        {"id": "0:0", "transcript": "I builds tools.", "wrong": "I builds", "better": "I build", "kind": "grammar"},
    ]) == set()


@pytest.mark.asyncio
async def test_legacy_lexical_error_uses_grammar_verifier():
    class Llm:
        async def complete_json(self, system, data, **kwargs):
            assert kwargs["model"] == "openai/gpt-4o-mini"
            assert json.loads(data)["candidates"][0]["kind"] == "grammar"
            return '{"acceptedIds":["0:0"]}'

    candidate = {"id": "0:0", "transcript": "I did a decision.",
                 "wrong": "I did a decision", "better": "I made a decision", "kind": "grammar",
                 "vocabularyCategory": "naturalness_error"}
    assert await LearnerModel(Llm()).verify_corrections([candidate]) == {"0:0"}


@pytest.mark.asyncio
async def test_final_upgrade_verifier_is_separate_from_error_verifier():
    class Llm:
        async def complete_json(self, system, data, **kwargs):
            assert system == ALTERNATIVE_REVIEW_SYSTEM
            assert json.loads(data)["candidates"][0]["category"] == "word_upgrade"
            return '{"decisions":[{"id":"0:0","sourceCorrect":true,"meaningPreserved":true,"usefulToLearn":true,"reason":"Используй substantial для заметного роста затрат."}]}'

    candidate = {"id": "0:0", "transcript": "We saw a big increase in costs.",
                 "original": "big", "alternative": "substantial", "category": "word_upgrade"}
    assert await LearnerModel(Llm()).verify_vocabulary_upgrades([candidate]) == {
        "0:0": {"accepted": True, "reason": "Используй substantial для заметного роста затрат.",
                "sourceCorrect": True, "meaningPreserved": True, "usefulToLearn": True}}


@pytest.mark.asyncio
async def test_upgrade_verifier_requires_reason_for_every_decision():
    class Llm:
        async def complete_json(self, *args, **kwargs):
            return '{"decisions":[{"id":"0:0","sourceCorrect":false,"meaningPreserved":true,"usefulToLearn":true,"reason":""}]}'

    candidate = {"id": "0:0", "transcript": "We saw a big increase in costs.",
                 "original": "big", "alternative": "substantial", "category": "word_upgrade"}
    with pytest.raises(ValueError, match="invalid vocabulary decision"):
        await LearnerModel(Llm()).verify_vocabulary_upgrades([candidate])


@pytest.mark.asyncio
async def test_upgrade_verifier_rejects_error_repair_even_when_replacement_is_useful():
    class Llm:
        async def complete_json(self, *args, **kwargs):
            return json.dumps({"decisions": [{
                "id": "0:0", "sourceCorrect": False,
                "meaningPreserved": True, "usefulToLearn": True,
                "reason": "Step здесь ошибочно описывает остановку в пути.",
            }]})

    candidate = {"id": "0:0", "transcript": "The flight had one step.",
                 "original": "step", "alternative": "stop", "category": "word_upgrade"}
    decision = await LearnerModel(Llm()).verify_vocabulary_upgrades([candidate])
    assert decision["0:0"]["accepted"] is False
    assert decision["0:0"]["sourceCorrect"] is False
