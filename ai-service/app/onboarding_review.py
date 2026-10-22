from __future__ import annotations

import re
from typing import Any

PAUSE_SECONDS = 1.0
EXAMPLE_LIMIT = 5
FILLERS = frozenset({"um", "uh", "uhm", "umm", "er", "erm", "ah", "ahh", "hmm", "mm", "mhm"})
CLOSING_OPEN = "I really enjoyed talking with you."
CLOSING_FALLBACK = "Thanks for sharing that with me."
CLOSING_MID = "I feel like I know you a little better now."
CLOSING_TAIL = "And I can already hear your English a little. Let me show you what I noticed."
CLOSING_SMILE = "😊"
_CONTENT = re.compile(r"[a-z0-9']+")
REVIEW_COPY_POLICY = """
The assessment is diagnostic: do not ask questions or add social praise.
Use warm, direct English addressed to "you". Do not recap topics in the assessment text.
Ground claims in demonstrated English. Ignore garbled fragments and probable transcription
artifacts. Do not treat an immediate self-correction as an error; judge the completed phrase.
Do not infer missing abilities from constructions the learner did not use or pronunciation
from transcripts. Only describe speaking pace or hesitation when supplied timings support it.
Grammar and vocabulary "text" must each be exactly two concise diagnostic sentences:
describe observed ability and, only when supported, its main limitation. Give concrete
evidence without repeating correction examples or adding advice.
Fluency "text" is two concise sentences about demonstrated flow.
Do not include scores, CEFR letters, or internal low/mid/high labels in these texts.
The supplied grammar/vocabulary examples have passed a conservative verification step.
An empty list means no sufficiently reliable correction was selected, not error-free speech.
Do not invent mistake examples or counts. Claims about Grammar mistakes must
use only the supplied Grammar examples. Vocabulary naturalness examples are verified mistakes. Claims about Vocabulary
mistakes must use only those examples. Optional final Vocabulary upgrades are
teaching points, not mistakes and not evidence for lowering the Vocabulary score.
Judge Vocabulary from the demonstrated range, precision, and natural use of words.
The examples already carry their conversation-time explanations. Do not write new
explanations or change the supplied correction pairs.
When examples are empty, describe supported ability or insufficient evidence; do not invent
a weakness to fill the sentence.
Fluency text may refer to supplied pace, pauses, and fillers in words, not with a new number.
Fillers are only detections in ASR output; null or zero never proves their absence.
"""
_STOP = frozenset({
    "about", "and", "are", "been", "from", "have", "hope", "into", "just", "like",
    "more", "really", "some", "sometime", "sounds", "tell", "that", "them", "then",
    "there", "they", "this", "very", "what", "when", "with", "would", "your",
    "you", "the", "for", "got", "know", "feel", "little", "better", "now",
})


def select_examples(candidates: list[dict], accepted: set[str], limit: int = EXAMPLE_LIMIT) -> dict[str, list[dict[str, str]]]:
    """Rank only verified candidates; the model cannot supply replacement pairs."""
    notes = [note for note in candidates if note["id"] in accepted]
    grammar = _ranked([note for note in notes if note["kind"] == "grammar"])[:limit]
    vocabulary = _ranked([note for note in notes if note["kind"] == "natural"])[:limit]
    return {"grammar": _pairs(grammar), "vocabulary": _pairs(vocabulary)}


def fluency_metrics(turns: list[dict]) -> dict[str, int | None | bool]:
    """Pace from words or transcript. Pauses and fillers only when word timings exist."""
    recognized = [
        turn for turn in turns
        if str(turn.get("transcript") or "").strip() and _seconds(turn) > 0
    ]
    total_seconds = sum(_seconds(turn) for turn in recognized)
    timed = [turn for turn in recognized if _timed_words(turn)]
    word_count = 0
    for turn in recognized:
        words = _timed_words(turn)
        word_count += len(words) if words else len(str(turn.get("transcript") or "").split())
    pace = round(word_count / total_seconds * 60) if total_seconds > 0 and word_count else None
    if not timed:
        return {
            "paceWpm": pace,
            "longPauses": None,
            "fillers": None,
            "longestStretchSec": None,
            "hasWords": False,
        }
    longest = max(_longest_stretch(words) for words in (_timed_words(turn) for turn in timed))
    return {
        "paceWpm": pace,
        "longPauses": sum(_pause_count(words) for words in (_timed_words(turn) for turn in timed)),
        "fillers": sum(_filler_count(words) for words in (_timed_words(turn) for turn in timed)) or None,
        "longestStretchSec": round(longest),
        "hasWords": True,
    }


def grounded_callback(callback: Any, last_transcript: str) -> str | None:
    """Keep one sentence that responds to the last answer."""
    if not isinstance(callback, str):
        return None
    text = " ".join(callback.split()).strip()
    if not text or text.lower() == "null":
        return None
    if sum(text.count(mark) for mark in ".!?") > 1:
        return None
    if len(text) > 220:
        return None
    lowered = text.lower()
    if "let me show you" in lowered or "i really enjoyed talking" in lowered:
        return None
    spoken = set(_CONTENT.findall(last_transcript.lower()))
    content = [token for token in _CONTENT.findall(lowered) if len(token) >= 4 and token not in _STOP]
    if not content or not any(token in spoken for token in content):
        return None
    if text[-1] not in ".!?":
        text += "."
    return text


def closing_lines(callback: str | None) -> tuple[str, str]:
    """Subtitle keeps the smile. The spoken line does not, so TTS will not read it."""
    head = f"{callback or CLOSING_FALLBACK} {CLOSING_OPEN} {CLOSING_MID}"
    subtitle = f"{head} {CLOSING_SMILE}\n{CLOSING_TAIL}"
    spoken = f"{head}\n{CLOSING_TAIL}"
    return subtitle, spoken


def correction_candidates(turns: list[dict]) -> list[dict]:
    collected = []
    for turn_index, turn in enumerate(turns):
        for order, note in enumerate(turn.get("corrections") or []):
            if not isinstance(note, dict):
                continue
            wrong = str(note.get("wrong") or "").strip()
            better = str(note.get("better") or "").strip()
            kind = str(note.get("kind") or "")
            transcript = str(turn.get("transcript") or "")
            if (
                not wrong or not better or wrong == better
                or kind not in {"grammar", "word", "natural"}
                or not re.search(r"(?<!\w)" + re.escape(wrong) + r"(?!\w)", transcript)
            ):
                continue
            collected.append({
                "id": f"{turn_index}:{order}",
                "transcript": transcript,
                "wrong": wrong,
                "better": better,
                "explanation": note["explanation"].strip() if isinstance(note.get("explanation"), str) else "",
                "kind": "natural" if kind == "word" else kind,
                "vocabularyCategory": note.get("vocabularyCategory"),
                "turn": turn_index,
                "order": order,
                "key": wrong.lower(),
            })
    return collected


def _ranked(notes: list[dict]) -> list[dict]:
    groups: dict[str, list[dict]] = {}
    for note in notes:
        groups.setdefault(note["key"], []).append(note)
    ranked = []
    for group in groups.values():
        group.sort(key=lambda item: (item["turn"], item["order"]))
        first = group[0]
        explanation = first.get("explanation") or next(
            (item["explanation"] for item in group
             if item["better"] == first["better"] and item.get("explanation")),
            "",
        )
        ranked.append({**first, "explanation": explanation, "count": len(group)})
    ranked.sort(key=lambda item: (-item["count"], item["turn"], item["order"]))
    return ranked


def _pairs(notes: list[dict]) -> list[dict[str, str]]:
    return [
        {"wrong": note["wrong"], "better": note["better"], "explanation": note.get("explanation") or ""}
        for note in notes
    ]


def _timed_words(turn: dict) -> list[dict]:
    words = turn.get("words")
    if not isinstance(words, list):
        return []
    timed = []
    for word in words:
        if not isinstance(word, dict):
            continue
        text = str(word.get("w") or "").strip()
        start = word.get("s")
        end = word.get("e")
        if not text or not isinstance(start, (int, float)) or isinstance(start, bool):
            continue
        if not isinstance(end, (int, float)) or isinstance(end, bool) or float(end) < float(start):
            continue
        timed.append({"w": text, "s": float(start), "e": float(end)})
    return timed


def _seconds(turn: dict) -> float:
    raw = turn.get("seconds") or 0
    try:
        value = float(raw)
    except (TypeError, ValueError):
        return 0.0
    return value if value > 0 else 0.0


def _pause_count(words: list[dict]) -> int:
    return sum(1 for left, right in zip(words, words[1:]) if right["s"] - left["e"] >= PAUSE_SECONDS)


def _longest_stretch(words: list[dict]) -> float:
    if not words:
        return 0.0
    best = 0.0
    start = words[0]["s"]
    previous_end = words[0]["e"]
    for word in words[1:]:
        if word["s"] - previous_end >= PAUSE_SECONDS:
            best = max(best, previous_end - start)
            start = word["s"]
        previous_end = word["e"]
    return max(best, previous_end - start)


def _filler_count(words: list[dict]) -> int:
    return sum(1 for word in words if _token(word["w"]) in FILLERS)


def _token(text: str) -> str:
    match = _CONTENT.search(text.lower())
    return match.group(0) if match else ""
