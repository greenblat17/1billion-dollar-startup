from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.stt import GroqSpeechToText


@pytest.mark.asyncio
async def test_assemblyai_converts_ogg_before_first_request(monkeypatch):
    wav = b"RIFF\x00\x00\x00\x00WAVE" + b"audio"
    convert = AsyncMock(return_value=wav)
    monkeypatch.setattr("app.stt.to_wav_mono_16k", convert)
    create = AsyncMock(return_value={"text": "Hello.", "duration": 1.0})
    client = SimpleNamespace(audio=SimpleNamespace(transcriptions=SimpleNamespace(create=create)))

    result = await GroqSpeechToText(client, "assemblyai/universal-3-5-pro", "ffmpeg", requires_wav=True).transcribe(
        b"OggSvoice", "audio/ogg", "voice.ogg"
    )

    assert result.text == "Hello."
    convert.assert_awaited_once_with("ffmpeg", b"OggSvoice", suffix=".ogg")
    create.assert_awaited_once()
    assert create.await_args.kwargs["file"] == ("voice.wav", wav, "audio/wav")


@pytest.mark.asyncio
async def test_assemblyai_sends_existing_wav_without_conversion(monkeypatch):
    convert = AsyncMock()
    monkeypatch.setattr("app.stt.to_wav_mono_16k", convert)
    create = AsyncMock(return_value={"text": "Hello."})
    client = SimpleNamespace(audio=SimpleNamespace(transcriptions=SimpleNamespace(create=create)))
    wav = b"RIFF\x00\x00\x00\x00WAVE" + b"audio"

    await GroqSpeechToText(client, "assemblyai/universal-3-5-pro", "ffmpeg", requires_wav=True).transcribe(
        wav, "audio/wav", "voice.wav"
    )

    convert.assert_not_awaited()
    create.assert_awaited_once()
    assert create.await_args.kwargs["file"] == ("voice.wav", wav, "audio/wav")
