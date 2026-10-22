"""Isolated, synthetic end-to-end correction/review probe against installed app code.

Run inside an AI service container with its usual provider environment:
    python - < evals/replay_final_assessment.py

This makes provider calls but never opens Redis, creates a chat, or stores an assessment.
"""

import asyncio
import json
import sys
import time

from openai import AsyncOpenAI

from app.call_review import CallReviews
from app.config import Settings
from app.llm import OpenAiChatModel
from app.onboarding import OnboardingService, assessment_summary
from app.onboarding_model import OnboardingModel
from app.onboarding_review import correction_candidates
from app.pipeline import NOTES_TIMEOUT_SECONDS


SCENARIO = [
    ("What changed at work last week?", "I work as a designer at a small company. Last week I did a decision to change our website because customers couldn't find the prices.", ["did a decision"]),
    ("How did you prepare the design?", "We took a photo of the old page. Then I made a photo of the new design, and my manager gave me an advice about the colors.", ["made a photo", "an advice"]),
    ("Who showed it to the team?", "I borrowed my laptop to a colleague so she could show the design at a meeting.", ["borrowed my laptop to"]),
    ("What did she think?", "She was very interesting in the idea, but she said the menu is not clear enough. I suggested her to move the button.", ["interesting in", "suggested her to"]),
    ("What happened on Friday?", "On Friday I lost the train to work, so I arrived late. I explained the problem to my team and they understood.", ["lost the train"]),
    ("How did the launch go?", "I have been working on this project for six months. The launch went smoothly, and our first customers found what they needed.", []),
    ("Would you change anything?", "I, I, uh, think the the new page is easier to use. We did a mistake with the first version, but we fixed it.", ["did a mistake"]),
    ("What comes next?", "If we get more feedback next month, we will improve the page again. I hope this helps customers choose the right plan.", []),
]


class RecordingModel:
    def __init__(self, delegate):
        self.delegate = delegate
        self.verifications = []
        self.review_requests = []

    async def verify_corrections(self, candidates):
        accepted = await self.delegate.verify_corrections(candidates)
        self.verifications.append({"candidateIds": [item["id"] for item in candidates], "acceptedIds": sorted(accepted)})
        return accepted

    async def compose_review(self, payload):
        self.review_requests.append(payload)
        return await self.delegate.compose_review(payload)

    async def compose_call_review(self, payload):
        self.review_requests.append(payload)
        return await self.delegate.compose_call_review(payload)


class NoWritePipeline:
    async def record_partial(self, *args):
        pass


async def main():
    settings = Settings.from_env()
    client = AsyncOpenAI(api_key=settings.openai_api_key, base_url=settings.openai_base_url, max_retries=0)
    llm = OpenAiChatModel(client, settings.llm_model, notes_model=settings.notes_model)
    model = OnboardingModel(llm, settings.onboarding_review_model, settings.onboarding_review_fallback_model)
    report = {"models": {"notes": settings.notes_model,
                         "review": settings.onboarding_review_model or settings.llm_model,
                         "reviewFallback": settings.onboarding_review_fallback_model}, "turns": []}
    turns = []
    try:
        for question, transcript, expected in SCENARIO:
            started = time.monotonic()
            try:
                result = await asyncio.wait_for(
                    llm.complete_notes_result(transcript, deadline_at=started + NOTES_TIMEOUT_SECONDS),
                    timeout=NOTES_TIMEOUT_SECONDS,
                )
                row = {"question": question, "transcript": transcript, "expectedSpans": expected,
                       "outcome": result.outcome, "attempts": result.attempts,
                       "corrections": [item.to_json() for item in result.corrections]}
            except asyncio.TimeoutError:
                row = {"question": question, "transcript": transcript, "expectedSpans": expected,
                       "outcome": "deadline", "attempts": 1, "corrections": []}
            row["elapsedSeconds"] = round(time.monotonic() - started, 2)
            report["turns"].append(row)
            turns.append({"question": question, "transcript": transcript,
                          "corrections": row["corrections"], "seconds": 14.0, "words": []})
            print(f"turn {len(turns)}: {row['outcome']}, {len(row['corrections'])} candidates", file=sys.stderr, flush=True)

        report["candidates"] = correction_candidates(turns)
        state = {"ask": "followup", "seconds": 112.0,
                 "profile": {"work": "designer", "leisure": "", "goal": "improve English at work"},
                 "turns": turns}
        placement = await model.assess(state)
        report["placement"] = placement
        state.update(cefr=placement.get("cefr"), position=placement.get("position"))

        onboarding_model = RecordingModel(model)
        onboarding = OnboardingService.__new__(OnboardingService)
        onboarding.model = onboarding_model
        onboarding.pipeline = NoWritePipeline()
        first_review = await onboarding._compose_review(state)
        report["onboarding"] = {"verification": onboarding_model.verifications,
                                "requestExamples": [{name: request.get(name) for name in ("grammarExamples", "vocabularyExamples")}
                                                    for request in onboarding_model.review_requests],
                                "review": first_review}

        baseline = assessment_summary({**state, "status": "completed", "runId": "synthetic",
                                       "review": first_review})
        report["baseline"] = baseline
        call_model = RecordingModel(model)
        calls = CallReviews.__new__(CallReviews)
        calls.model = call_model
        call = await calls._compose({"turns": turns}, baseline)
        report["call"] = {"verification": call_model.verifications,
                          "requestExamples": [{name: request.get(name) for name in ("grammarExamples", "vocabularyExamples")}
                                              for request in call_model.review_requests],
                          "review": call["public"]}
    finally:
        await client.close()
        print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    asyncio.run(main())
