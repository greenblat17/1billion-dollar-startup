import asyncio

import pytest

from app.dialogue import MemoryDialogueStore
from app.pipeline import ClipPipeline
from app.stt import SttResult


class Stt:
    async def transcribe(self, audio, content_type, filename):
        return SttResult(text="I work in design.", duration_seconds=2)


class Llm:
    def __init__(self):
        self.notes_release = asyncio.Event()
        self.notes_cancelled = False

    async def complete_reply(self, history, user_text, profile_note=None):
        return "Tell me more about your work."

    async def complete_notes(self, user_text):
        try:
            await self.notes_release.wait()
        except asyncio.CancelledError:
            self.notes_cancelled = True
            raise
        return []


class Tts:
    def __init__(self, fail=False):
        self.started = asyncio.Event()
        self.release = asyncio.Event()
        self.fail = fail

    async def synthesize(self, text):
        self.started.set()
        await self.release.wait()
        if self.fail:
            raise RuntimeError("tts failed")
        return b"OggS"


class Personalization:
    def __init__(self):
        self.extracted = asyncio.Event()
        self.saved = False

    async def prepare(self, session):
        return "context"

    async def extract_person(self, session, transcript):
        self.extracted.set()
        return {"work": "design"}

    async def save_observation(self, session, observation):
        self.saved = True


def pipeline(llm, tts, personalization):
    result = ClipPipeline(
        stt=Stt(), llm=llm, tts=tts,
        dialogue=MemoryDialogueStore(max_messages=40, ttl_seconds=86400),
    )
    result.personalization = personalization
    return result


@pytest.mark.asyncio
async def test_tts_starts_before_notes_and_memory_is_saved_only_after_success():
    llm, tts, memory = Llm(), Tts(), Personalization()
    turn = asyncio.create_task(pipeline(llm, tts, memory).run("tg-1", b"voice", "audio/ogg", "voice.ogg"))
    await asyncio.wait_for(tts.started.wait(), 1)
    await asyncio.wait_for(memory.extracted.wait(), 1)
    assert not llm.notes_release.is_set()
    assert not memory.saved
    llm.notes_release.set()
    tts.release.set()
    result = await asyncio.wait_for(turn, 1)
    assert memory.saved
    assert result.audio == b"OggS"
    assert {"stt", "context", "reply", "notes", "memoryExtract", "tts", "memorySave", "finalize", "total"} <= result.timings_ms.keys()
    assert result.timings_ms["total"] >= result.timings_ms["tts"]


@pytest.mark.asyncio
async def test_failed_tts_does_not_save_memory_and_cancels_notes():
    llm, tts, memory = Llm(), Tts(fail=True), Personalization()
    turn = asyncio.create_task(pipeline(llm, tts, memory).run("tg-1", b"voice", "audio/ogg", "voice.ogg"))
    await asyncio.wait_for(tts.started.wait(), 1)
    tts.release.set()
    with pytest.raises(RuntimeError, match="tts failed"):
        await asyncio.wait_for(turn, 1)
    assert not memory.saved
    assert llm.notes_cancelled


@pytest.mark.asyncio
async def test_pipeline_timeout_cancels_parallel_work_without_saving_memory():
    llm, tts, memory = Llm(), Tts(), Personalization()
    with pytest.raises(asyncio.TimeoutError):
        await asyncio.wait_for(pipeline(llm, tts, memory).run("tg-1", b"voice", "audio/ogg", "voice.ogg"), 0.05)
    assert llm.notes_cancelled
    assert not memory.saved
