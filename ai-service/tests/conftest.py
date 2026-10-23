from __future__ import annotations

from app.config import Settings
from app.dialogue import MemoryDialogueStore
from app.llm import ChatModel, Correction, CorrectionRun
from app.main import create_app
from app.pipeline import ClipPipeline
from app.stt import SpeechToText, SttResult
from app.tts import TextToSpeech, TtsAudio


def test_settings() -> Settings:
    return Settings(
        groq_api_key=None,
        openai_api_key=None,
        openai_base_url="https://api.openai.com/v1",
        groq_base_url="https://api.groq.com/openai/v1",
        stt_model="whisper-large-v3",
        llm_model="gpt-5.6-luna",
        tts_model="gpt-4o-mini-tts",
        tts_voice="coral",
        tts_response_format="opus",
        ffmpeg_bin="ffmpeg",
        redis_url=None,
        dialogue_ttl_seconds=86400,
        dialogue_max_messages=40,
        job_ttl_seconds=600,
        pipeline_timeout_seconds=60,
        log_level="INFO",
        ai_internal_token="test-internal-token",
        openai_realtime_api_key=None,
    )


class FakeStt(SpeechToText):
    def __init__(self, texts: list[str]) -> None:
        self._texts = list(texts)
        self.calls = 0

    async def transcribe(self, audio: bytes, content_type: str, filename: str, language: str | None = "en") -> SttResult:
        self.calls += 1
        text = self._texts.pop(0) if self._texts else ""
        return SttResult(text=text, no_speech=not text.strip())


class FakeLlm(ChatModel):
    def __init__(self, notes: list[Correction] | None = None) -> None:
        self.calls: list[tuple[list[str], str]] = []
        self.notes_calls: list[str] = []
        self.notes = list(notes or [])
        self.profile_notes: list[str | None] = []

    async def complete_reply(self, history, user_text: str, profile_note: str | None = None) -> str:
        self.calls.append(([item.content for item in history], user_text))
        self.profile_notes.append(profile_note)
        return f"Got it: {user_text}"

    async def complete_notes(self, user_text: str) -> list[Correction]:
        self.notes_calls.append(user_text)
        return list(self.notes)

    async def complete_notes_result(self, user_text: str, *, deadline_at=None, attempt_started=None) -> CorrectionRun:
        if attempt_started is not None:
            attempt_started()
        notes = await self.complete_notes(user_text)
        return CorrectionRun(notes, "shown" if notes else "empty", 1)


class FakeTts(TextToSpeech):
    def __init__(self, content_type: str = "audio/ogg") -> None:
        self.texts: list[str] = []
        self.speeds: list[float | None] = []
        self.content_type = content_type

    async def synthesize(self, text: str, speed: float | None = None) -> TtsAudio:
        self.texts.append(text)
        self.speeds.append(speed)
        prefix = b"OggS" if self.content_type == "audio/ogg" else b"ID3"
        return TtsAudio(prefix + text.encode("utf-8"), self.content_type)


class FakeReviewer:
    def __init__(self, payload: dict | None = None) -> None:
        self.calls: list[list[dict[str, str]]] = []
        self.payload = payload or {
            "steps": [
                {
                    "metric": "Grammar",
                    "score": 78,
                    "lead": "Tenses",
                    "bullets": ["Past Simple"],
                    "examples": [
                        {
                            "original": "I work here since 2023.",
                            "improved": "I have worked here since 2023.",
                        }
                    ],
                    "tip": "Watch verb tense.",
                },
                {
                    "metric": "Vocabulary",
                    "score": 84,
                    "lead": "Word choice",
                    "bullets": ["Stronger verbs"],
                    "examples": [],
                    "tip": "Swap filler words.",
                },
            ]
        }

    async def review(self, turns: list[dict[str, str]]) -> dict:
        self.calls.append(turns)
        return self.payload


class FakeRealtime:
    def __init__(self) -> None:
        self.calls: list[tuple[str, str, str]] = []

    async def start_call(self, sdp: str, topic: str, voice: str) -> tuple[str, str | None]:
        self.calls.append((sdp, topic, voice))
        return "v=0 answer", "rtc_test"


def build_app(
    stt: FakeStt | None = None,
    llm: FakeLlm | None = None,
    tts: FakeTts | None = None,
    dialogue: MemoryDialogueStore | None = None,
    realtime: FakeRealtime | None = None,
    reviewer: FakeReviewer | None = None,
):
    stt = stt or FakeStt(["hello"])
    llm = llm or FakeLlm()
    tts = tts or FakeTts()
    dialogue = dialogue or MemoryDialogueStore(max_messages=40, ttl_seconds=86400)
    pipeline = ClipPipeline(stt=stt, llm=llm, tts=tts, dialogue=dialogue)
    realtime = realtime or FakeRealtime()
    reviewer = reviewer or FakeReviewer()
    app = create_app(
        settings=test_settings(),
        pipeline=pipeline,
        realtime=realtime,
        reviewer=reviewer,
    )
    return app, stt, llm, tts
