"""Replay a fixed conversation through the real call reply model and local reaction gate.

Run from ai-service: PYTHONPATH=. python evals/simulate_call_reactions.py --env-file /path/to/key.env
The env file must contain OPENAI_API_KEY. Never print or save its value.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
from pathlib import Path

from openai import AsyncOpenAI

from app.calls import CallStore
from app.dialogue import ChatMessage
from app.llm import OpenAiChatModel


USER_TURNS = [
    "I'm preparing a small photo exhibition at a local café next month.",
    "Yes, it is my first one. I'm excited, but I feel nervous too.",
    "I showed the photos to the café owner today, and she loved them!",
    "She especially liked a photo of an old man feeding birds in the rain.",
    "I nearly dropped my camera into a puddle when I took that photo. My friend still laughs about it.",
    "Yeah, I was lucky. I want the exhibition to feel warm, not perfect.",
    "I decided to print ten photos and write one short story next to each one.",
    "My dad used to take photos with me. He died last year, so this exhibition means a lot to me.",
    "Thank you. I found one photo he took, and I may put it near the entrance.",
    "I'm not sure what to write under it. Maybe just his name and the year.",
    "Today I finished the first print. It looked even better than on my screen.",
    "I think I will invite my friends this weekend. I'm still scared, but I want to do it.",
]


def load_key(path: Path | None) -> str:
    if path is not None:
        for line in path.read_text().splitlines():
            key, separator, value = line.partition("=")
            if separator and key.strip() == "OPENAI_API_KEY":
                return value.strip().strip('"\'')
    return os.getenv("OPENAI_API_KEY", "")


async def run(args: argparse.Namespace) -> None:
    api_key = load_key(args.env_file)
    if not api_key:
        raise SystemExit("OPENAI_API_KEY is required; no model requests sent")
    history = [ChatMessage("assistant", "Hi! How are you? What have you been thinking about lately?")]
    calls = CallStore()
    call_id = (await calls.open("reaction-simulation"))["callId"]
    counts = {"proposed": 0, "sent": 0}
    async with AsyncOpenAI(api_key=api_key, base_url=args.base_url, max_retries=0, timeout=45) as client:
        model = OpenAiChatModel(client, args.model)
        for index, user_text in enumerate(USER_TURNS[:args.turns], start=1):
            decision = await model.complete_call_reply(history, user_text)
            gate = await calls.claim_telegram_reaction("reaction-simulation", call_id, index, decision.reaction)
            counts["proposed"] += decision.reaction is not None
            counts["sent"] += gate["reaction"] is not None
            print(json.dumps({"turn": index, "user": user_text, "reply": decision.reply,
                              "proposedReaction": decision.reaction, "shownReaction": gate["reaction"],
                              "gate": gate["reason"]}, ensure_ascii=False), flush=True)
            history.extend((ChatMessage("user", user_text), ChatMessage("assistant", decision.reply)))
    print(json.dumps({"model": args.model, "turns": min(args.turns, len(USER_TURNS)), **counts}, ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path)
    parser.add_argument("--base-url", default="https://openrouter.ai/api/v1")
    parser.add_argument("--model", default="openai/gpt-5.6-luna")
    parser.add_argument("--turns", type=int, default=len(USER_TURNS), choices=range(1, len(USER_TURNS) + 1))
    asyncio.run(run(parser.parse_args()))
