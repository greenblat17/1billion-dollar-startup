import pytest

from app.gemini_speech import GeminiKoraSpeech


class AudioResponse:
    def __init__(self, data):
        self.data = data

    async def aread(self):
        return self.data


class SpeechEndpoint:
    def __init__(self, data):
        self.data = data
        self.calls = []

    async def create(self, **kwargs):
        self.calls.append(kwargs)
        return AudioResponse(self.data)


class Client:
    def __init__(self, data):
        self.endpoint = SpeechEndpoint(data)
        self.audio = type("Audio", (), {"speech": self.endpoint})()


@pytest.mark.asyncio
@pytest.mark.parametrize("speed", [0.8, 0.9, 1.0])
async def test_gemini_kora_uses_pcm_exact_text_and_encodes_telegram_ogg(monkeypatch, speed):
    client = Client(b"\0\0" * 24000)
    speech = GeminiKoraSpeech(client)
    conversions = []

    async def convert(pcm, selected):
        conversions.append((pcm, selected))
        return b"OggSvoice"

    monkeypatch.setattr(speech, "_ffmpeg", convert)
    result = await speech.synthesize("Hey, how are you?", speed=speed)
    assert result.data == b"OggSvoice"
    assert result.content_type == "audio/ogg"
    assert result.spoken_text is None
    assert client.endpoint.calls == [{
        "model": "google/gemini-3.1-flash-tts-preview", "voice": "Leda",
        "input": "Hey, how are you?", "response_format": "pcm",
    }]
    assert conversions == [(b"\0\0" * 24000, speed)]


@pytest.mark.asyncio
@pytest.mark.parametrize("pcm", [b"", b"x", b"x" * (24 * 1024 * 1024 + 2)])
async def test_gemini_kora_rejects_bad_pcm_without_encoding(pcm):
    speech = GeminiKoraSpeech(Client(pcm))
    with pytest.raises(ValueError, match="gemini_invalid_pcm"):
        await speech.synthesize("Hello.")


@pytest.mark.asyncio
async def test_gemini_kora_rejects_invalid_speed_before_provider_call():
    client = Client(b"\0\0")
    speech = GeminiKoraSpeech(client)
    with pytest.raises(ValueError, match="unsupported speech speed"):
        await speech.synthesize("Hello.", speed=1.5)
    assert client.endpoint.calls == []
