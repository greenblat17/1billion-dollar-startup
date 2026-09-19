from __future__ import annotations

import re
from difflib import SequenceMatcher

from app.llm import NOTE_SEP

_TOKEN = re.compile(r"\S+")
_MAX_NOTES = 3
_MIN_RATIO = 0.5
_MIN_LEN_RATIO = 0.4


def notes_from_rewrite(original: str, corrected: str) -> list[str]:
    source = original.strip()
    target = corrected.strip()
    if not source or not target or source == target:
        return []

    a_tokens = _tokens(source)
    b_tokens = _tokens(target)
    if not a_tokens or not b_tokens:
        return []

    a_words = [token[0] for token in a_tokens]
    b_words = [token[0] for token in b_tokens]
    matcher = SequenceMatcher(a=a_words, b=b_words, autojunk=False)
    if matcher.ratio() < _MIN_RATIO:
        return []
    if min(len(a_words), len(b_words)) / max(len(a_words), len(b_words)) < _MIN_LEN_RATIO:
        return []

    notes: list[str] = []
    hunks = _merge_nearby(_merged_hunks(matcher.get_opcodes()))
    for i1, i2, j1, j2 in hunks:
        i1, i2, j1, j2 = _anchor_insert_or_delete(i1, i2, j1, j2, len(a_tokens), len(b_tokens))
        if i1 >= i2 or j1 >= j2:
            continue
        wrong = source[a_tokens[i1][1] : a_tokens[i2 - 1][2]]
        better = target[b_tokens[j1][1] : b_tokens[j2 - 1][2]]
        if not wrong or not better or wrong == better or wrong not in source:
            continue
        notes.append(f"{wrong}{NOTE_SEP}{better}")
        if len(notes) == _MAX_NOTES:
            break
    return notes


def _tokens(text: str) -> list[tuple[str, int, int]]:
    return [(match.group(), match.start(), match.end()) for match in _TOKEN.finditer(text)]


def _merged_hunks(opcodes: list[tuple[str, int, int, int, int]]) -> list[tuple[int, int, int, int]]:
    hunks: list[tuple[int, int, int, int]] = []
    for tag, i1, i2, j1, j2 in opcodes:
        if tag == "equal":
            continue
        if hunks and hunks[-1][1] == i1 and hunks[-1][3] == j1:
            start_i, _, start_j, _ = hunks[-1]
            hunks[-1] = (start_i, i2, start_j, j2)
        else:
            hunks.append((i1, i2, j1, j2))
    return hunks


def _merge_nearby(
    hunks: list[tuple[int, int, int, int]],
    max_gap: int = 1,
) -> list[tuple[int, int, int, int]]:
    if not hunks:
        return []
    merged = [hunks[0]]
    for hunk in hunks[1:]:
        prev = merged[-1]
        gap_a = hunk[0] - prev[1]
        gap_b = hunk[2] - prev[3]
        if 0 <= gap_a <= max_gap and 0 <= gap_b <= max_gap:
            merged[-1] = (prev[0], hunk[1], prev[2], hunk[3])
        else:
            merged.append(hunk)
    return merged


def _anchor_insert_or_delete(
    i1: int,
    i2: int,
    j1: int,
    j2: int,
    a_len: int,
    b_len: int,
) -> tuple[int, int, int, int]:
    if i1 == i2:
        if i1 > 0 and j1 > 0:
            return i1 - 1, i2, j1 - 1, j2
        if i1 < a_len and j2 < b_len:
            return i1, i2 + 1, j1, j2 + 1
        return i1, i2, j1, j2
    if j1 == j2:
        if i2 < a_len and j1 < b_len:
            return i1, i2 + 1, j1, j2 + 1
        if i1 > 0 and j1 > 0:
            return i1 - 1, i2, j1 - 1, j2
        return i1, i2, j1, j2
    return i1, i2, j1, j2
