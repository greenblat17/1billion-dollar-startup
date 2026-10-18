"""Paired, human-guided live comparison of ordinary and Friendly Buddy reply prompts."""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import random
import secrets
import time
from datetime import datetime, timezone
from pathlib import Path

from openai import AsyncOpenAI

from app.config import Settings
from app.dialogue import ChatMessage
from app.llm import OpenAiChatModel
from app.personalization import context_note

SCENARIOS = Path(__file__).with_name("free_call_persona_scenarios.json")


class CaptureMetrics:
    def __init__(self) -> None:
        self.attempts: list[dict] = []

    async def record_llm(self, prompt_tokens, completion_tokens, elapsed_ms, *, purpose, success) -> None:
        if purpose == "reply":
            self.attempts.append({
                "prompt_tokens": prompt_tokens or None,
                "completion_tokens": completion_tokens or None,
                "elapsed_ms": elapsed_ms,
                "success": success,
            })


def read_scenarios() -> dict[str, dict]:
    cases = json.loads(SCENARIOS.read_text(encoding="utf-8"))
    if not isinstance(cases, list) or len(cases) != 12:
        raise ValueError("expected 12 scenario cards")
    result = {case["id"]: case for case in cases}
    if len(result) != len(cases):
        raise ValueError("scenario ids must be unique")
    return result


def profile_note(case: dict) -> str:
    # Synthetic profile only. Both variants receive identical context and no real user data.
    return context_note({
        "person": case["person"],
        "proficiency": {"overall_cefr": case["level"]},
    }, streak=0, now=time.time())


def initial_history(case: dict) -> list[ChatMessage]:
    prior = [ChatMessage(item["role"], item["content"]) for item in case.get("prior_turns", [])]
    return prior + [
        ChatMessage("user", "Let's start a practice conversation."),
        ChatMessage("assistant", case["opening"]),
    ]


def save_private(path: Path, payload: dict) -> None:
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w", encoding="utf-8") as file:
        json.dump(payload, file, ensure_ascii=False, indent=2)
        file.write("\n")


async def run(case: dict, output_dir: Path, turns: int, seed: int) -> tuple[Path, Path]:
    settings = Settings.from_env()
    if not settings.openai_api_key:
        raise SystemExit("OPENAI_API_KEY is required; no requests sent.")

    order = ["ordinary", "free_call"]
    random.Random(seed).shuffle(order)
    labels = dict(zip(("A", "B"), order))
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + f"-{secrets.token_hex(3)}"
    output_dir.mkdir(parents=True, exist_ok=True)
    blind_path = output_dir / f"{case['id']}-{run_id}-blind.json"
    key_path = output_dir / f"{case['id']}-{run_id}-key.json"
    result = {
        "scenario": {key: case[key] for key in ("id", "title", "level", "brief", "person")},
        "model": settings.llm_model,
        "base_url": settings.openai_base_url,
        "temperature": 0.7,
        "turn_limit": turns,
        "variants": [],
    }
    result["scenario"]["prior_turns"] = case.get("prior_turns", [])
    context = profile_note(case)
    metrics = CaptureMetrics()
    headers = ({
        "HTTP-Referer": "https://github.com/greenblat17/sber500xdisrupt-speaking-coach-application",
        "X-Title": "Speaky",
    } if "openrouter.ai" in settings.openai_base_url else None)

    try:
        async with AsyncOpenAI(api_key=settings.openai_api_key, base_url=settings.openai_base_url,
                               default_headers=headers, max_retries=0) as client:
            model = OpenAiChatModel(client, settings.llm_model, metrics=metrics)
            for label, mode in labels.items():
                history = initial_history(case)
                variant = {"label": label, "opening": case["opening"], "turns": [], "status": "incomplete"}
                result["variants"].append(variant)
                print(f"\n=== Variant {label}: {case['title']} ===")
                print(case["brief"])
                for index in range(turns):
                    if index == 0:
                        user_text = case["initial_user"]
                        print(f"User: {user_text}")
                    else:
                        user_text = input("Your next English reply: ").strip()
                        if not user_text:
                            print("Please enter a reply, or press Ctrl+C to stop and save the partial run.")
                            user_text = input("Your next English reply: ").strip()
                        if not user_text:
                            raise KeyboardInterrupt
                    before = len(metrics.attempts)
                    started = time.perf_counter()
                    try:
                        reply = await model.complete_reply(history, user_text, context, mode=mode)
                    except Exception as error:
                        variant["turns"].append({"user": user_text, "error_type": type(error).__name__})
                        variant["status"] = "model_error"
                        print(f"Model error: {type(error).__name__}")
                        break
                    attempts = metrics.attempts[before:]
                    variant["turns"].append({
                        "user": user_text,
                        "speaky": reply,
                        "wall_ms": round((time.perf_counter() - started) * 1000),
                        "attempts": attempts,
                    })
                    history.extend((ChatMessage("user", user_text), ChatMessage("assistant", reply)))
                    print(f"Speaky: {reply}")
                else:
                    variant["status"] = "complete"
    except (KeyboardInterrupt, EOFError):
        print("\nInterrupted; saving the partial comparison.")
    finally:
        save_private(blind_path, result)
        save_private(key_path, {"scenario_id": case["id"], "labels": labels, "seed": seed})
    return blind_path, key_path


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--list", action="store_true", help="List the synthetic scenario cards without calling a model")
    parser.add_argument("--live", action="store_true", help="Allow real provider requests and charges")
    parser.add_argument("--scenario", help="One scenario id from --list")
    parser.add_argument("--output-dir", type=Path, help="Directory for blind transcripts and the separate answer key")
    parser.add_argument("--turns", type=int, default=5, help="User turns per variant, 2–8; default: 5")
    parser.add_argument("--seed", type=int, help="Optional seed for reproducible A/B ordering")
    args = parser.parse_args()
    cases = read_scenarios()
    if args.list:
        for case in cases.values():
            print(f"{case['id']:18} {case['level']:2}  {case['title']}")
        return
    if not args.live or not args.scenario or args.output_dir is None:
        parser.error("--live, --scenario, and --output-dir are required to run a comparison")
    if args.scenario not in cases:
        parser.error(f"unknown scenario: {args.scenario}; use --list")
    if not 2 <= args.turns <= 8:
        parser.error("--turns must be between 2 and 8")
    print("Use fictional details only; do not type real personal information.")
    print("You will run the same scenario twice in a hidden random order. Reply naturally to each Speaky turn.")
    blind, key = asyncio.run(run(cases[args.scenario], args.output_dir, args.turns,
                                 args.seed if args.seed is not None else secrets.randbits(64)))
    print(f"\nBlind transcript: {blind}\nAnswer key (keep hidden during scoring): {key}")


if __name__ == "__main__":
    main()
