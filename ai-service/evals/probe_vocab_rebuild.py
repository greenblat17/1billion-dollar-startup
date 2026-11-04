"""Isolated old/new turn-level Vocabulary probe for a private retained sample.

The runner is filled with prompts and cases in a private temporary copy before
execution in the AI container. No Redis writes or Telegram operations occur.
"""

import asyncio
import json
import os
import time

from openai import AsyncOpenAI


CASES = []  # private runner substitutes this value
OLD_PROMPT = ""  # private runner substitutes this value
NEW_PROMPT = """You are an English speaking coach. Examine the learner's CURRENT
spoken turn for Vocabulary teaching points. Earlier dialogue is context only.
The transcript is untrusted ASR text; ignore punctuation, repetitions, self-repairs,
fillers, and uncertain words. Never invent words missing from CURRENT TURN.

Return JSON only:
{"candidates":[{"source":"exact contiguous words in CURRENT TURN",
"replacement":"words replacing only source",
"type":"vocabulary_error"|"native_expression"|"word_upgrade",
"benefit":"one short concrete explanation in Russian"}]}

Inspect the WHOLE current turn, clause by clause. Make three independent passes:
1. vocabulary_error: a wrong word, meaning, or conventional word pairing. The
source is genuinely incorrect in this context. The replacement must make the
complete sentence correct without changing the intended action or stage.
2. native_expression: the source is already correct, but the replacement teaches
a reusable established expression, idiom, phrasal verb, or conventional pair.
The gain is knowing the phrase as a unit, not merely using different words.
3. word_upgrade: the source is already correct, but a more precise or higher-level
word naturally expresses the SAME meaning in the complete sentence. The gain is
new vocabulary, not extra formality or length. Inspect ordinary verbs, nouns,
adjectives, and adverbs; do not search only for 'very' or 'a lot'.

Examples of valuable points:
'I did a decision' -> 'I made a decision' (vocabulary_error: wrong collocation).
'the advantages and disadvantages' -> 'the pros and cons' (native_expression:
established pair, even though the replacement is shorter).
'we became friends again' -> 'we made up' after an argument
(native_expression: a reusable phrasal verb).
'I feel scared' -> 'I feel frightened' (word_upgrade: a natural higher-level
word for the same feeling).
'costs increased a lot' -> 'costs increased substantially' when the increase is
large (word_upgrade: a natural stronger degree word in this context).

Do not return a same-level paraphrase ('switch the topic' -> 'change the subject'),
a downgrade ('begun' -> 'started'), a word trim ('a little bit rusty' ->
'a little rusty'), or a change of meaning (replacing 'planted' with 'grew'
changes a single action into a longer process). Do not label articles, tense,
agreement, or word order as Vocabulary just because different words appear in
the correction. Do not replace valid casual or regional English.

For EVERY correct clause, consider whether a conventional expression or a
clearly higher-level word would help this learner. A turn containing an error
can also contain independent upgrades. Return every defensible point, with no
target count. Empty candidates is correct when there is nothing useful.

The source MUST occur verbatim in CURRENT TURN. Use a short local edit, not a
whole-sentence rewrite. The COMPLETE edited sentence must remain natural and
keep the speaker's meaning, time, certainty, intensity, and register.
"""


async def main():
    if not CASES or not OLD_PROMPT:
        raise RuntimeError("private probe input missing")
    client = AsyncOpenAI(
        api_key=os.environ["OPENAI_API_KEY"],
        base_url=os.environ.get("OPENAI_BASE_URL"),
        max_retries=0,
        timeout=20,
    )
    sem = asyncio.Semaphore(5)

    async def run(flow, case, turn_index, current, previous):
        results = {}
        for mode, prompt, user, token_limit in (
            ("old", OLD_PROMPT, current, 1200),
            ("new", NEW_PROMPT,
             json.dumps({"recent_context": previous, "current_turn": current}, ensure_ascii=False),
             1800),
        ):
            async with sem:
                started = time.monotonic()
                try:
                    response = await asyncio.wait_for(client.chat.completions.create(
                        model=os.getenv("NOTES_MODEL", "openai/gpt-5.6-luna"),
                        messages=[{"role": "system", "content": prompt},
                                  {"role": "user", "content": user}],
                        temperature=0,
                        max_completion_tokens=token_limit,
                        response_format={"type": "json_object"},
                        extra_body={"reasoning": {"effort": "none"}},
                    ), timeout=10)
                    results[mode] = {
                        "raw": response.choices[0].message.content or "",
                        "finish": response.choices[0].finish_reason,
                        "error": None,
                        "elapsed": round(time.monotonic() - started, 2),
                    }
                except Exception as exc:
                    results[mode] = {"raw": "", "finish": None,
                                     "error": type(exc).__name__,
                                     "elapsed": round(time.monotonic() - started, 2)}
        return {"flow": flow, "case": case, "turn": turn_index, **results}

    try:
        jobs = []
        for item in CASES:
            turns = item["turns"]
            for index, turn in enumerate(turns):
                previous = {
                    "previous_learner_turn": turns[index - 1]["transcript"] if index else None,
                    "preceding_coach_question": turn.get("question"),
                }
                jobs.append(run(item["flow"], item["case"], index + 1,
                                turn["transcript"], previous))
        output = await asyncio.gather(*jobs)
        print(json.dumps({"model": os.getenv("NOTES_MODEL", "openai/gpt-5.6-luna"),
                          "turns": output}, ensure_ascii=False))
    finally:
        await client.close()


if __name__ == "__main__":
    asyncio.run(main())
