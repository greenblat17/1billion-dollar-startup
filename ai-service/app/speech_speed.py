"""Supported speech speeds for the retained voice runtime."""
import json
from pathlib import Path


SPEED_CHOICES = tuple(
    item['value']
    for item in json.loads(Path(__file__).with_name('legacy-speech-speeds-v1.json').read_text())
)


def validate_speech_speed(speed: float) -> None:
    if isinstance(speed, bool) or speed not in SPEED_CHOICES:
        raise ValueError("unsupported speech speed")
