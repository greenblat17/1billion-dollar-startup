import asyncio
import time

import pytest
from fakeredis import FakeAsyncRedis

from app.memory_jobs import MemoryJob, MemoryJobRunner, MemoryJobStore
from app.onboarding import OnboardingStore
from app.onboarding_model import PersonValidationError
from app.personalization import Personalization


class Streaks:
    async def shown(self, session):
        return 0


class Model:
    def __init__(self):
        self.calls = []
        self.fail_once = False

    async def update_person(self, person, transcripts):
        self.calls.append((dict(person), transcripts[0]))
        if self.fail_once:
            self.fail_once = False
            raise PersonValidationError("facts_missing")
        return {"name": None, "work": None, "leisure": None, "goal": None,
                "facts": [*person.get("facts", []), transcripts[0]]}


@pytest.mark.asyncio
async def test_redis_queue_survives_restart_and_applies_one_users_turns_in_order():
    redis = FakeAsyncRedis(decode_responses=True)
    queue = MemoryJobStore(redis)
    now = time.time()
    assert await queue.enqueue(MemoryJob("first", "tg-1", "I moved to Kazan", now))
    assert not await queue.enqueue(MemoryJob("first", "tg-1", "I moved to Kazan", now))
    assert await queue.enqueue(MemoryJob("second", "tg-1", "I now work in design", now + 1))
    model = Model()
    reopened = MemoryJobStore(redis)
    runner = MemoryJobRunner(reopened, Personalization(OnboardingStore(redis), model, Streaks()))
    await runner._process("tg-1")
    await runner._process("tg-1")
    learner = await OnboardingStore(redis).get_learner("tg-1")
    assert learner["person"]["facts"] == ["I moved to Kazan", "I now work in design"]
    assert [text for _, text in model.calls] == ["I moved to Kazan", "I now work in design"]
    assert await reopened.sessions() == []
    assert not await reopened.enqueue(MemoryJob("first", "tg-1", "duplicate", time.time()))
    await runner.personalization.record_contact("tg-1")
    assert (await OnboardingStore(redis).get_learner("tg-1"))["person"]["facts"] == [
        "I moved to Kazan", "I now work in design"]
    await redis.aclose()


@pytest.mark.asyncio
async def test_recovery_after_profile_save_does_not_call_model_again():
    redis = FakeAsyncRedis(decode_responses=True)
    queue = MemoryJobStore(redis)
    job = MemoryJob("saved", "tg-1", "I moved to Kazan", time.time())
    await queue.enqueue(job)
    model = Model()
    service = Personalization(OnboardingStore(redis), model, Streaks())
    await service.apply_background("tg-1", job.id, job.transcript)
    restarted = MemoryJobRunner(MemoryJobStore(redis), service)
    await restarted._process("tg-1")
    assert len(model.calls) == 1
    assert (await restarted.store.pending())[0] == 0
    await redis.aclose()


@pytest.mark.asyncio
async def test_invalid_schema_retries_then_saves_only_valid_result():
    model = Model()
    model.fail_once = True
    learner = OnboardingStore()
    queue = MemoryJobStore()
    runner = MemoryJobRunner(queue, Personalization(learner, model, Streaks()))
    await queue.enqueue(MemoryJob("retry", "tg-1", "I moved to Kazan", time.time()))
    await runner._process("tg-1")
    assert len(model.calls) == 2
    assert (await learner.get_learner("tg-1"))["person"]["facts"] == ["I moved to Kazan"]
    assert runner.metrics._counts["retried", "facts_missing"] == 1
    assert runner.metrics._counts["succeeded", "none"] == 1


@pytest.mark.asyncio
async def test_exhausted_invalid_output_keeps_existing_facts():
    class InvalidModel:
        calls = 0

        async def update_person(self, person, transcripts):
            self.calls += 1
            raise PersonValidationError("facts_too_long")

    model = InvalidModel()
    learner = OnboardingStore()
    await learner.save_learner("tg-1", {"person": {"facts": ["Lives in Moscow"]}, "proficiency": {"overall_cefr": "B1"}})
    queue = MemoryJobStore()
    runner = MemoryJobRunner(queue, Personalization(learner, model, Streaks()))
    await queue.enqueue(MemoryJob("bad", "tg-1", "I moved to Kazan", time.time()))
    await runner._process("tg-1")
    saved = await learner.get_learner("tg-1")
    assert saved["person"]["facts"] == ["Lives in Moscow"]
    assert saved["proficiency"]["overall_cefr"] == "B1"
    assert model.calls == 2
    assert runner.metrics._counts["failed", "facts_too_long"] == 1


@pytest.mark.asyncio
async def test_save_retry_reuses_valid_extraction():
    class FlakyStore(OnboardingStore):
        failures = 1

        async def save_learner_if_current(self, session, expected, updated):
            if "lastMemoryJobId" in updated and self.failures:
                self.failures -= 1
                raise RuntimeError("redis unavailable")
            return await super().save_learner_if_current(session, expected, updated)

    learner = FlakyStore()
    model = Model()
    queue = MemoryJobStore()
    runner = MemoryJobRunner(queue, Personalization(learner, model, Streaks()))
    await queue.enqueue(MemoryJob("save", "tg-1", "I moved to Kazan", time.time()))
    await runner._process("tg-1")
    assert len(model.calls) == 1
    pending = await queue.claim("tg-1", "inspect")
    assert pending.candidate["facts"] == ["I moved to Kazan"]
    await queue.update(MemoryJob(**{**pending.__dict__, "next_at": 0}))
    await queue.release("tg-1", "inspect")
    await runner._process("tg-1")
    assert len(model.calls) == 1
    assert (await learner.get_learner("tg-1"))["person"]["facts"] == ["I moved to Kazan"]


@pytest.mark.asyncio
async def test_different_sessions_can_process_while_one_model_call_waits():
    entered = asyncio.Event()
    release = asyncio.Event()

    class SlowModel(Model):
        async def update_person(self, person, transcripts):
            if transcripts[0] == "slow":
                entered.set()
                await release.wait()
            return await super().update_person(person, transcripts)

    queue = MemoryJobStore()
    learner = OnboardingStore()
    runner = MemoryJobRunner(queue, Personalization(learner, SlowModel(), Streaks()))
    await queue.enqueue(MemoryJob("one", "tg-1", "slow", time.time()))
    await queue.enqueue(MemoryJob("two", "tg-2", "fast", time.time()))
    slow = asyncio.create_task(runner._process("tg-1"))
    await entered.wait()
    await runner._process("tg-2")
    assert (await learner.get_learner("tg-2"))["person"]["facts"] == ["fast"]
    release.set()
    await slow


@pytest.mark.asyncio
async def test_slow_queue_write_has_bounded_voice_path_cost():
    class SlowStore(MemoryJobStore):
        async def enqueue(self, job):
            await asyncio.sleep(10)

    runner = MemoryJobRunner(SlowStore(), None)
    started = time.monotonic()
    await runner.enqueue("slow-enqueue", "tg-1", "I moved to Kazan")
    assert time.monotonic() - started < 0.6
    assert runner.metrics._counts["enqueue_failed", "enqueue_failed"] == 1
