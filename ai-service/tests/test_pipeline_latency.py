import asyncio

import pytest

from app.dialogue import MemoryDialogueStore
from app.calls import CallStore
from app.llm import CorrectionRun
from app.jobs import ClipJob
from app.main import _run_job
from app.memory_jobs import MemoryJobRunner, MemoryJobStore
from app.pipeline import NOTES_TIMEOUT_SECONDS, ClipPipeline
from app.stt import SttResult
from app.tts import TtsAudio


class Stt:
    async def transcribe(self, audio, content_type, filename):
        return SttResult(text="I work in design.", duration_seconds=2)


class Llm:
    def __init__(self):
        self.notes_release = asyncio.Event()
        self.notes_cancelled = False
        self.reply_contexts = []
        self.reply_modes = []

    async def complete_reply(self, history, user_text, profile_note=None, *, mode="ordinary"):
        self.reply_contexts.append((history, profile_note))
        self.reply_modes.append(mode)
        return "Tell me more about your work."

    async def complete_notes(self, user_text):
        try:
            await self.notes_release.wait()
        except asyncio.CancelledError:
            self.notes_cancelled = True
            raise
        return []

    async def complete_notes_result(self, user_text, *, deadline_at=None, attempt_started=None):
        if attempt_started is not None:
            attempt_started()
        notes = await self.complete_notes(user_text)
        return CorrectionRun(notes, "shown" if notes else "empty", 1)


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
        return TtsAudio(b"OggS", "audio/ogg")


class Personalization:
    def __init__(self):
        self.extracted = asyncio.Event()
        self.saved = False
        self.contact_recorded = False

    async def prepare(self, session):
        return "context"

    async def extract_person(self, session, transcript):
        self.extracted.set()
        return {"work": "design"}

    async def save_observation(self, session, observation):
        self.saved = True

    async def record_contact(self, session):
        self.contact_recorded = True


def pipeline(llm, tts, personalization, notes_timeout_seconds=NOTES_TIMEOUT_SECONDS):
    result = ClipPipeline(
        stt=Stt(), llm=llm, tts=tts,
        dialogue=MemoryDialogueStore(max_messages=40, ttl_seconds=86400),
        notes_timeout_seconds=notes_timeout_seconds,
    )
    result.personalization = personalization
    return result


@pytest.mark.asyncio
async def test_voice_finishes_without_waiting_for_personal_memory():
    llm, tts, memory = Llm(), Tts(), Personalization()
    turn = asyncio.create_task(pipeline(llm, tts, memory).run("tg-1", b"voice", "audio/ogg", "voice.ogg"))
    await asyncio.wait_for(tts.started.wait(), 1)
    assert not llm.notes_release.is_set()
    assert not memory.saved
    llm.notes_release.set()
    tts.release.set()
    result = await asyncio.wait_for(turn, 1)
    assert not memory.extracted.is_set()
    assert not memory.saved
    assert result.memory_eligible
    assert result.audio == TtsAudio(b"OggS", "audio/ogg")
    assert {"stt", "context", "reply", "notes", "tts", "finalize", "total"} <= result.timings_ms.keys()
    assert "memoryExtract" not in result.timings_ms
    assert result.timings_ms["total"] >= result.timings_ms["tts"]


@pytest.mark.asyncio
async def test_next_turn_uses_previous_words_before_memory_worker_runs():
    llm, tts, memory = Llm(), Tts(), Personalization()
    service = pipeline(llm, tts, memory)
    llm.notes_release.set()
    tts.release.set()
    await service.run("tg-1", b"voice", "audio/ogg", "voice.ogg")
    await service.run("tg-1", b"voice", "audio/ogg", "voice.ogg")
    assert [message.content for message in llm.reply_contexts[1][0]] == [
        "I work in design.", "Tell me more about your work.",
    ]
    assert not memory.extracted.is_set()


@pytest.mark.asyncio
async def test_successful_clip_enqueues_memory_without_running_extraction():
    llm, tts, memory = Llm(), Tts(), Personalization()
    service = pipeline(llm, tts, memory)
    jobs = MemoryJobStore()
    runner = MemoryJobRunner(jobs, memory)
    llm.notes_release.set()
    tts.release.set()
    job = ClipJob("job-1", "tg-1", attempt_id="attempt-1")
    await _run_job(job, b"voice", "audio/ogg", "voice.ogg", service, 2,
                   memory_jobs=runner)
    assert job.status == "ok"
    assert memory.contact_recorded
    assert not memory.extracted.is_set()
    pending = await jobs.claim("tg-1", "inspect")
    assert pending.id == "attempt-1"
    assert pending.transcript == "I work in design."
    await jobs.release("tg-1", "inspect")


@pytest.mark.asyncio
async def test_failed_tts_does_not_enqueue_memory():
    llm, tts, memory = Llm(), Tts(fail=True), Personalization()
    service = pipeline(llm, tts, memory)
    runner = MemoryJobRunner(MemoryJobStore(), memory)
    llm.notes_release.set()
    tts.release.set()
    job = ClipJob("job-2", "tg-1")
    await _run_job(job, b"voice", "audio/ogg", "voice.ogg", service, 2,
                   memory_jobs=runner)
    assert job.status == "error"
    assert not memory.contact_recorded
    assert (await runner.store.pending())[0] == 0


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
    service = pipeline(llm, tts, memory)
    with pytest.raises(asyncio.TimeoutError):
        await asyncio.wait_for(service.run("tg-1", b"voice", "audio/ogg", "voice.ogg"), 0.05)
    assert llm.notes_cancelled
    assert not memory.saved
    assert (await service.metrics.snapshot())["corrections"] == {}


@pytest.mark.asyncio
async def test_notes_deadline_returns_ready_audio_and_cancels_correction_request():
    llm, tts, memory = Llm(), Tts(), Personalization()
    service = pipeline(llm, tts, memory, notes_timeout_seconds=0.03)
    turn = asyncio.create_task(service.run("tg-1", b"voice", "audio/ogg", "voice.ogg"))
    await asyncio.wait_for(tts.started.wait(), 1)
    tts.release.set()
    result = await asyncio.wait_for(turn, 1)
    assert result.audio == TtsAudio(b"OggS", "audio/ogg")
    assert result.corrections == []
    assert llm.notes_cancelled
    assert result.timings_ms["notes"] < 200
    assert not memory.saved
    snapshot = await service.metrics.snapshot()
    assert snapshot["corrections"]["deadline"]["count"] == 1


@pytest.mark.asyncio
async def test_scenario_turns_stay_in_role_and_out_of_personal_memory():
    llm, tts, memory = Llm(), Tts(), Personalization()
    service = pipeline(llm, tts, memory)
    calls = CallStore()
    service.calls = calls
    summary = await calls.open("tg-role", {"kind": "job", "description": ""})
    await calls.save_opening(summary["callId"], "Tell me about yourself?")
    await calls.mark_opening_delivered(summary["callId"])
    llm.notes_release.set()
    tts.release.set()

    await service.run("tg-role", b"voice", "audio/ogg", "voice.ogg")
    await service.run("tg-role", b"voice", "audio/ogg", "voice.ogg")

    assert [item.content for item in llm.reply_contexts[0][0]] == ["Tell me about yourself?"]
    assert [item.content for item in llm.reply_contexts[1][0]] == [
        "Tell me about yourself?", "I work in design.", "Tell me more about your work.",
    ]
    assert llm.reply_contexts[0][1].startswith("Role-play context data:\n")
    assert llm.reply_modes == ["roleplay", "roleplay"]
    assert not memory.extracted.is_set()
    assert not memory.saved
    assert await service.dialogue.history("tg-role") == []


@pytest.mark.asyncio
async def test_free_call_persona_starts_with_first_direct_voice_and_stays_outside_legacy_voice():
    llm, tts, memory = Llm(), Tts(), Personalization()
    service = pipeline(llm, tts, memory)
    calls = CallStore()
    service.calls = calls
    await calls.open("tg-free")  # No starter has been delivered and no turn has been saved yet.
    llm.notes_release.set()
    tts.release.set()

    await service.run("tg-free", b"voice", "audio/ogg", "voice.ogg")
    await service.run("tg-legacy", b"voice", "audio/ogg", "voice.ogg")

    assert llm.reply_modes == ["free_call", "ordinary"]
    assert llm.reply_contexts[0][1] == "context"
