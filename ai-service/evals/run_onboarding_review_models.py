"""Opt-in live comparison of onboarding review models on synthetic examples."""

import argparse
import asyncio
import json
import os
import statistics
import time
from pathlib import Path

from openai import AsyncOpenAI

from app.onboarding_model import (
    REVIEW_MAX_TOKENS, REVIEW_RESPONSE_FORMAT, REVIEW_SYSTEM, parse_review, validate_review_response,
)


MODELS = (
    "openai/gpt-4o-mini",
    "openai/gpt-5.6-luna",
    "google/gemini-2.5-flash",
    "google/gemini-3.5-flash-lite",
    "google/gemini-3.8-flash",
)

CASES = {
    "thin": {
        "transcripts": ["Hello. I like football.", "I work in an office."],
        "profile": {"work": "office worker", "leisure": "football", "goal": None},
        "cefr": None, "position": None,
        "grammarExamples": [], "vocabularyExamples": [],
        "fluency": {"paceWpm": None, "longPauses": None, "fillers": None, "longestStretchSec": None},
    },
    "connected": {
        "transcripts": [
            "I work at a small company where we make apps for schools. I enjoy it because I can see how teachers use our work.",
            "Last year I start a project with my friend. We built a simple app, but we had to change it after talking to students.",
            "I want to speak English more confidently because some of our clients live abroad. When I explain a problem, I sometimes need time to find the right word.",
        ],
        "profile": {"work": "builds apps for schools", "leisure": None, "goal": "speak with international clients"},
        "cefr": "B1", "position": "mid",
        "grammarExamples": [{"wrong": "Last year I start a project", "better": "Last year I started a project", "kind": "grammar"}],
        "vocabularyExamples": [],
        "fluency": {"paceWpm": 104, "longPauses": 2, "fillers": None, "longestStretchSec": 18.4},
    },
    "mixed": {
        "transcripts": [
            "I am studying design, and I often make posters for local events. The work is interesting because every project has a different audience.",
            "If I have more time next month, I will try to make a website for my sister. I want to learn how people choose what to read first.",
            "English is useful for me because many design articles are in English, but sometimes I cannot explain my ideas quickly.",
        ],
        "profile": {"work": "design student", "leisure": None, "goal": "read design articles and explain ideas"},
        "cefr": "B1", "position": "high",
        "grammarExamples": [], "vocabularyExamples": [],
        "fluency": {"paceWpm": 91, "longPauses": None, "fillers": None, "longestStretchSec": None},
    },
    "self_repair": {
        "transcripts": [
            "I is, sorry, I am a student. I study English because I want to travel.",
            "Yesterday I went to the museum with my friend. We saw old photographs and talked about our city.",
        ],
        "profile": {"work": "student", "leisure": "visiting museums", "goal": "travel"},
        "cefr": "A2", "position": "mid",
        "grammarExamples": [], "vocabularyExamples": [],
        "fluency": {"paceWpm": None, "longPauses": None, "fillers": None, "longestStretchSec": None},
    },
    "garbled": {
        "transcripts": ["I am, uh, the blue somebody work...", "Yes, hello. The app? Maybe tomorrow."],
        "profile": {}, "cefr": None, "position": None,
        "grammarExamples": [], "vocabularyExamples": [],
        "fluency": {"paceWpm": None, "longPauses": None, "fillers": None, "longestStretchSec": None},
    },
    "simple_connected": {
        "transcripts": [
            "I live with my family. I work in a shop and I help customers every day.",
            "After work I cook dinner. I like cooking because my family eats together.",
            "I learn English because I want to talk with people when I travel.",
        ],
        "profile": {"work": "shop worker", "leisure": "cooking", "goal": "travel"},
        "cefr": "A2", "position": "high",
        "grammarExamples": [], "vocabularyExamples": [],
        "fluency": {"paceWpm": 82, "longPauses": 1, "fillers": None, "longestStretchSec": 12.0},
    },
    "complex": {
        "transcripts": [
            "Although our first design looked attractive, users struggled to find the main action, so we tested a simpler layout.",
            "Had we asked them earlier, we might have avoided two weeks of revisions. The experience changed how I plan research.",
            "I now present the trade-offs to the team before we commit to a direction, because a polished prototype can hide basic problems.",
        ],
        "profile": {"work": "product designer", "leisure": None, "goal": None},
        "cefr": "B2", "position": "high",
        "grammarExamples": [], "vocabularyExamples": [],
        "fluency": {"paceWpm": 116, "longPauses": 1, "fillers": 2, "longestStretchSec": 27.5},
    },
    "uncertain_example": {
        "transcripts": [
            "My team made a small website for a local club. We changed the home page after people told us it was confusing.",
            "I like to talk with customers because their questions show what is missing. Next month we will test another version.",
        ],
        "profile": {"work": "builds websites", "leisure": None, "goal": None},
        "cefr": "B1", "position": "low",
        "grammarExamples": [], "vocabularyExamples": [],
        "fluency": {"paceWpm": None, "longPauses": None, "fillers": None, "longestStretchSec": None},
    },
}


def load_key(path: Path) -> str:
    for line in path.read_text().splitlines():
        if line.startswith("OPENAI_API_KEY="):
            return line.split("=", 1)[1].strip().strip('"\'')
    raise RuntimeError("OPENAI_API_KEY missing from env file")


async def run_case(client: AsyncOpenAI, model: str, case_name: str, payload: dict,
                   max_tokens: int, strict_schema: bool) -> dict:
    started = time.perf_counter()
    row = {"model": model, "case": case_name}
    try:
        response = await client.chat.completions.create(
            model=model,
            messages=[
                {"role": "system", "content": REVIEW_SYSTEM},
                {"role": "user", "content": json.dumps(payload, ensure_ascii=False)},
            ],
            temperature=0.0,
            max_completion_tokens=max_tokens,
            response_format=REVIEW_RESPONSE_FORMAT if strict_schema else {"type": "json_object"},
            extra_body={"provider": {"require_parameters": True}} if strict_schema else None,
        )
        row["seconds"] = round(time.perf_counter() - started, 3)
        row["finish_reason"] = response.choices[0].finish_reason if response.choices else None
        row["usage"] = response.usage.model_dump() if response.usage else None
        raw = response.choices[0].message.content if response.choices else None
        try:
            if strict_schema:
                validate_review_response(raw or "")
            parsed = parse_review(raw or "")
            row["valid"] = True
            row["review"] = parsed
        except (ValueError, TypeError) as exc:
            row["valid"] = False
            row["error"] = str(exc)
            row["raw"] = raw
    except Exception as exc:
        row["seconds"] = round(time.perf_counter() - started, 3)
        row["valid"] = False
        row["error"] = type(exc).__name__
    return row


async def evaluate(repeats: int, output: Path, env_file: Path, models: tuple[str, ...],
                   cases: tuple[str, ...], max_tokens: int, strict_schema: bool) -> None:
    key = load_key(env_file)
    rows = []
    async with AsyncOpenAI(api_key=key, base_url="https://openrouter.ai/api/v1", max_retries=0, timeout=90) as client:
        for repeat in range(repeats):
            for name in cases:
                payload = CASES[name]
                for model in models:
                    row = await run_case(client, model, name, payload, max_tokens, strict_schema)
                    row["repeat"] = repeat + 1
                    row["max_tokens"] = max_tokens
                    row["strict_schema"] = strict_schema
                    rows.append(row)
                    print(f'{model} {name} #{repeat + 1}: {"valid" if row["valid"] else row["error"]} ({row["seconds"]}s)', flush=True)
                    output.write_text(json.dumps(rows, ensure_ascii=False, indent=2))
    for model in models:
        subset = [row for row in rows if row["model"] == model]
        seconds = sorted(row["seconds"] for row in subset)
        print(f'{model}: {sum(row["valid"] for row in subset)}/{len(subset)} valid; median {statistics.median(seconds):.2f}s; max {seconds[-1]:.2f}s')


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--live", action="store_true", required=True)
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--env-file", type=Path, default=Path(".env"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--model", choices=MODELS, action="append", dest="models")
    parser.add_argument("--case", choices=CASES, action="append", dest="cases")
    parser.add_argument("--max-tokens", type=int, default=REVIEW_MAX_TOKENS)
    parser.add_argument("--strict-schema", action="store_true")
    args = parser.parse_args()
    if args.repeats < 1:
        parser.error("--repeats must be positive")
    asyncio.run(evaluate(args.repeats, args.output, args.env_file, tuple(args.models or MODELS),
                         tuple(args.cases or CASES), args.max_tokens, args.strict_schema))
