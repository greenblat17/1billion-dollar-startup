"""Optional, transcript-grounded ways to express an already correct idea."""

from __future__ import annotations

import re
from typing import Any

SUGGESTION_LIMIT = 3

VOCABULARY_SUGGESTION_POLICY = """When vocabularyExamples is empty, you may add up to three vocabularySuggestions.
Each original must be a short, exact, contiguous phrase from one supplied transcript that is
already correct AND idiomatic in this context. Mere comprehensibility is not enough. A phrase
with an awkward collocation or nonstandard wording is ineligible even if the correction
pipeline missed it. The alternative is another natural spoken-English way to express the
SAME idea in this context. Offer it only when it teaches a genuinely useful idiom, more
precise expression, or clearly smoother conversational phrasing. A native speaker could
naturally choose either version. Do not upgrade vocabulary for its own sake, swap ordinary
synonyms, make a tiny stylistic edit, change tone or meaning, or repair a grammar mistake
under this heading. Do not use garbled ASR, false starts, or phrases overlapping a correction
candidate. Explain the specific benefit in one short English sentence without calling the
original wrong. If none clearly meet this bar, return []. If vocabularyExamples is nonempty,
return []. There is no minimum and three is a cap, not a target. Usually zero or one is enough.
Preserve the action and its stage, time, certainty, and intensity; a more specific-sounding
verb can change what happened. Do not replace the main action just to sound advanced.
Do not suggest "in a perfect case scenario" -> "ideally": the original is awkward.
Do not suggest "I use the same words every single time" -> "I keep using the same words":
the benefit is too small. Do not suggest "build my own startup" -> "launch my own startup":
building a company and launching it are different stages. A useful option could be "I had
to use what I had" -> "I had to make do with what I had" when the surrounding transcript
supports the same meaning. Never copy these examples unless they occur exactly in the
supplied transcripts and pass all checks in their actual context.
These are optional learning opportunities, not corrections or evidence of a weakness.
A phrase can be fully correct and still offer a reusable everyday spoken expression.
When such an expression carries precisely the same meaning and is genuinely useful to learn,
include one rather than defaulting to []. For example, "I work on my hobby outside my day job"
can become "I work on my hobby on the side" when that same context is explicit. Do not infer
that context when it is absent. Still return [] for trivial synonym swaps, errors, or meaning shifts.
"""


def select_vocabulary_suggestions(
    proposed: Any,
    transcripts: list[str],
    correction_candidates: list[dict],
    vocabulary_examples: list[dict],
) -> list[dict[str, str]]:
    """Keep optional alternatives separate from corrections, failing closed on bad data."""
    if vocabulary_examples or not isinstance(proposed, list):
        return []
    selected: list[dict[str, str]] = []
    seen: set[str] = set()
    for item in proposed:
        if not isinstance(item, dict):
            continue
        fields = (item.get("original"), item.get("alternative"), item.get("explanation"))
        if any(not isinstance(value, str) or not value.strip() for value in fields):
            continue
        original, alternative, explanation = (value.strip() for value in fields)
        key = " ".join(original.lower().split())
        if key in seen or key == " ".join(alternative.lower().split()):
            continue
        if not 3 <= len(original.split()) <= 30 or not 2 <= len(alternative.split()) <= 35:
            continue
        if len(explanation.split()) > 30 or len(explanation) > 200 or "\n" in explanation:
            continue
        if any(mark in original + alternative for mark in ("\n", "...", "…")):
            continue
        if not any(_exact_span(original, transcript) for transcript in transcripts):
            continue
        if any(_overlaps_correction(original, candidate.get("wrong", "")) for candidate in correction_candidates):
            continue
        selected.append({"original": original, "alternative": alternative, "explanation": explanation})
        seen.add(key)
        if len(selected) == SUGGESTION_LIMIT:
            break
    return selected


def _exact_span(phrase: str, transcript: str) -> bool:
    return bool(re.search(r"(?<!\w)" + re.escape(phrase) + r"(?!\w)", transcript))


def _overlaps_correction(phrase: str, corrected_context: str) -> bool:
    return bool(phrase and corrected_context and (phrase in corrected_context or corrected_context in phrase))
