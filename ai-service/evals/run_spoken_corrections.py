"""Opt-in live evaluation: PYTHONPATH=. python evals/run_spoken_corrections.py --live."""
import argparse
import asyncio
import json
from pathlib import Path

from openai import AsyncOpenAI

from app.config import Settings
from app.llm import OpenAiChatModel
from app.onboarding_model import OnboardingModel
from app.onboarding_review import correction_candidates, select_examples


def normalized(text):
    return text.strip().rstrip(".!?").casefold()


async def evaluate():
    settings = Settings.from_env()
    if not settings.openai_api_key:
        raise SystemExit("OPENAI_API_KEY is required; no requests sent.")
    cases = json.loads(Path(__file__).with_name("spoken_corrections.json").read_text())
    false_corrections = missed = mismatched = 0
    async with AsyncOpenAI(api_key=settings.openai_api_key, base_url=settings.openai_base_url) as client:
        model = OpenAiChatModel(client, settings.llm_model)
        reviewer = OnboardingModel(model)
        for case in cases:
            notes = await model.complete_notes(case["transcript"])
            candidates = correction_candidates([{
                "transcript": case["transcript"], "corrections": [note.to_json() for note in notes],
            }])
            accepted = await reviewer.verify_corrections(candidates) if candidates else set()
            final = select_examples(candidates, accepted)
            if case["expect"] == "omit":
                false_corrections += bool(notes)
            else:
                missed += not notes
                if notes and not any(
                    normalized(note.wrong) == normalized(case["original"])
                    and normalized(note.better) == normalized(case["correction"]) for note in notes
                ):
                    mismatched += 1
            print(json.dumps({"case": case["id"], "transcript": case["transcript"],
                              "live": [note.to_json() for note in notes], "assessment": final}, ensure_ascii=False))
    print(json.dumps({"falseCorrectionCases": false_corrections, "missedPositiveCases": missed,
                      "positivePairsNeedingHumanReview": mismatched}))
    # Semantic quality still needs human inspection; matching fixtures is not a calibrated guarantee.
    return 1 if false_corrections or missed or mismatched else 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--live", action="store_true", required=True, help="Send synthetic cases to the configured model")
    parser.parse_args()
    raise SystemExit(asyncio.run(evaluate()))
