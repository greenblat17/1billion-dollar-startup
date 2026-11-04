"""Allowlisted per-turn delivery cues for Gemini 3.1 speech."""

DELIVERY_TAGS = {
    "plain": "",
    "warm": "[warmly] ",
    "curious": "[curious] ",
    "surprised": "[amazed] ",
    "excited": "[excited] ",
    "gentle": "[softly] ",
    "thoughtful": "[thoughtfully] ",
    "playful": "[mischievously] ",
}


def normalize_delivery_tone(value: str) -> str:
    tone = value.strip().lower()
    return tone if tone in DELIVERY_TAGS else "plain"
