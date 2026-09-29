from __future__ import annotations

SCORE_TABLE = {
    "A1": {"low": 12, "mid": 20, "high": 27},
    "A2": {"low": 32, "mid": 38, "high": 43},
    "B1": {"low": 47, "mid": 52, "high": 57},
    "B2": {"low": 63, "mid": 70, "high": 77},
    "C1": {"low": 83, "mid": 90, "high": 94},
}

GRAMMAR_FLAGS = frozenset({
    "simple_clauses",
    "tense_contrast",
    "linked_clauses",
    "complex_clause",
    "complex_repeated",
})
VOCABULARY_FLAGS = frozenset({
    "concrete_lexis",
    "topic_spread",
    "precise_choice",
    "natural_collocation",
    "paraphrase",
})
FLUENCY_FLAGS = frozenset({
    "completed_turns",
    "linked_ideas",
    "reformulation",
    "timings_present",
})
# The model never decides that word timings exist. The code adds this flag.
MODEL_FLUENCY_FLAGS = FLUENCY_FLAGS - {"timings_present"}
SKILL_FLAGS = {
    "grammar": GRAMMAR_FLAGS,
    "vocabulary": VOCABULARY_FLAGS,
    "fluency": MODEL_FLUENCY_FLAGS,
}


def skill_score(band: str | None, position: str | None) -> int | None:
    if band is None:
        return None
    return SCORE_TABLE[band][position]


def skill_confidence(flags: list[str], allowed: frozenset[str], seconds: float) -> float:
    """Coverage matters more than minutes. Two minutes cannot pass 0.6."""
    observed = {flag for flag in flags if flag in allowed}
    coverage = len(observed) / len(allowed) if allowed else 0.0
    volume = min(max(seconds, 0.0) / 60.0, 1.0)
    raw = (0.15 + 0.45 * coverage) * (0.4 + 0.6 * volume)
    return round(min(raw, 0.6), 2)


def apply_skill(raw: dict, seconds: float, kind: str, timings: bool = False) -> dict:
    allowed = FLUENCY_FLAGS if kind == "fluency" else SKILL_FLAGS[kind]
    flags = list(raw["flags"])
    if kind == "fluency" and timings and "timings_present" not in flags:
        flags.append("timings_present")
    return {
        "score": skill_score(raw["band"], raw["position"]),
        "text": raw["text"],
        "band": raw["band"],
        "position": raw["position"],
        "notes": raw["notes"],
        "flags": [flag for flag in flags if flag in allowed],
        "confidence": skill_confidence(flags, allowed, seconds),
    }
