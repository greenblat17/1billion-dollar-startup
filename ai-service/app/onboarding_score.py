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


_BANDS = ("A1", "A2", "B1", "B2", "C1")
_POSITIONS = ("low", "mid", "high")
SHADES = {"--": -2, "-": -1, "0": 0, "+": 1, "++": 2}
_CELLS = tuple(
    (band, position, SCORE_TABLE[band][position])
    for band in _BANDS
    for position in _POSITIONS
)
_CELL_INDEX = {(band, position): index for index, (band, position, _) in enumerate(_CELLS)}


def normalize_shade(value: str | None) -> str:
    """A missing shade is the cell center. The model never supplies the integer."""
    return value if value in SHADES else "0"


def _bounds(index: int) -> tuple[int, int]:
    """Stay strictly closer to this anchor than to either neighbor. A midpoint belongs to neither."""
    anchor = _CELLS[index][2]
    lower = anchor - 2
    upper = anchor + 2
    if index > 0:
        previous = _CELLS[index - 1][2]
        lower = max(lower, (previous + anchor) // 2 + 1)
    if index + 1 < len(_CELLS):
        following = _CELLS[index + 1][2]
        upper = min(upper, (anchor + following + 1) // 2 - 1)
    return lower, upper


def owning_cell(score: int) -> tuple[str, str, int, int, int]:
    """The band, position, anchor, and inclusive bounds that already contain this score."""
    contained = []
    for index, (band, position, anchor) in enumerate(_CELLS):
        lower, upper = _bounds(index)
        if lower <= score <= upper:
            contained.append((band, position, anchor, lower, upper))
    if not contained:
        index = min(range(len(_CELLS)), key=lambda item: abs(_CELLS[item][2] - score))
        band, position, anchor = _CELLS[index]
        lower, upper = _bounds(index)
        return band, position, anchor, lower, upper
    return min(contained, key=lambda item: abs(item[2] - score))


def step_score(previous: int | None, move: int) -> int | None:
    """Move a stored score by at most two points and keep it inside its current cell."""
    if previous is None:
        return None
    try:
        current = int(previous)
    except (TypeError, ValueError):
        return None
    step = max(-2, min(2, int(move)))
    _band, _position, _anchor, lower, upper = owning_cell(current)
    return min(max(current + step, lower), upper)


def placed_level(score: int) -> dict:
    """Band and shade implied by a score that already sits inside one cell."""
    band, position, anchor, _lower, _upper = owning_cell(score)
    shade = next(name for name, value in SHADES.items() if value == max(-2, min(2, score - anchor)))
    return {"cefr": band, "position": position, "shade": shade, **overall_progress(band, position, shade)}


def skill_score(band: str | None, position: str | None, shade: str = "0") -> int | None:
    if band is None:
        return None
    index = _CELL_INDEX[(band, position)]
    lower, upper = _bounds(index)
    return min(max(_CELLS[index][2] + SHADES[normalize_shade(shade)], lower), upper)


def band_start(band: str) -> int:
    """The beginning of a band is the lowest score its low cell can reach."""
    return skill_score(band, "low", "--")


def overall_progress(cefr: str | None, position: str | None, shade: str = "0") -> dict:
    """Holistic level only. Skill scores are never averaged into this."""
    if cefr not in SCORE_TABLE or position not in SCORE_TABLE[cefr]:
        return {"overallScore": None, "nextBand": None, "pointsToNext": None}
    score = skill_score(cefr, position, shade)
    index = _BANDS.index(cefr)
    if index + 1 >= len(_BANDS):
        return {"overallScore": score, "nextBand": None, "pointsToNext": None}
    next_band = _BANDS[index + 1]
    return {
        "overallScore": score,
        "nextBand": next_band,
        "pointsToNext": band_start(next_band) - score,
    }


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
    shade = "0" if raw["band"] is None else normalize_shade(raw.get("shade"))
    return {
        "score": skill_score(raw["band"], raw["position"], shade),
        "text": raw["text"],
        "band": raw["band"],
        "position": raw["position"],
        "shade": shade,
        "notes": raw["notes"],
        "flags": [flag for flag in flags if flag in allowed],
        "confidence": skill_confidence(flags, allowed, seconds),
    }
