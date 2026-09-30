"""Opt-in live evaluation: PYTHONPATH=. python evals/run_spoken_corrections.py --live."""
import argparse
import asyncio
import json
from pathlib import Path

from openai import AsyncOpenAI

from app.config import Settings
from app.llm import OpenAiChatModel


def normalized(text):
    return text.strip().rstrip(".!?").casefold()


def word_edit(original, corrected):
    before, after = original.split(), corrected.split()
    prefix = 0
    while prefix < min(len(before), len(after)) and before[prefix] == after[prefix]:
        prefix += 1
    suffix = 0
    while suffix < min(len(before), len(after)) - prefix and before[-1 - suffix] == after[-1 - suffix]:
        suffix += 1
    return {"before": " ".join(before[prefix:len(before) - suffix]),
            "after": " ".join(after[prefix:len(after) - suffix])}


async def evaluate():
    settings = Settings.from_env()
    if not settings.openai_api_key:
        raise SystemExit("OPENAI_API_KEY is required; no requests sent.")
    cases = json.loads(Path(__file__).with_name("spoken_corrections.json").read_text())
    false_corrections = missed = missing_edits = edits_for_review = contexts_for_review = 0
    async with AsyncOpenAI(api_key=settings.openai_api_key, base_url=settings.openai_base_url) as client:
        model = OpenAiChatModel(client, settings.llm_model, notes_model=settings.notes_model)
        for case in cases:
            notes = await model.complete_notes(case["transcript"])
            if case["expect"] == "omit":
                false_corrections += bool(notes)
            else:
                missed += not notes
                live_edits = [
                    {key: normalized(value) for key, value in word_edit(note.wrong, note.better).items()}
                    for note in notes
                ]
                gold_edits = [
                    {key: normalized(value) for key, value in edit.items()}
                    for edit in case["expected_edits"]
                ]
                missing_edits += sum(edit not in live_edits for edit in gold_edits)
                edits_for_review += sum(edit not in gold_edits for edit in live_edits)
                if notes and not any(normalized(note.wrong) == normalized(case["expected_context"]) for note in notes):
                    contexts_for_review += 1
            row = {"case": case["id"],
                   "live": [note.to_json() for note in notes]}
            if case.get("worry"):
                row["worry"] = case["worry"]
            print(json.dumps(row, ensure_ascii=False))
    print(json.dumps({"falseCorrectionCases": false_corrections, "missedPositiveCases": missed,
                      "missingExpectedEdits": missing_edits,
                      "editsNeedingHumanReview": edits_for_review,
                      "contextsNeedingHumanReview": contexts_for_review}))
    # Exact edit/context mismatches can still be valid alternatives; inspect them before release.
    return 1 if false_corrections or missed else 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--live", action="store_true", required=True, help="Send synthetic cases to the configured model")
    parser.parse_args()
    raise SystemExit(asyncio.run(evaluate()))
