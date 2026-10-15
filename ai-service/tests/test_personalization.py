import json

import pytest
from fakeredis import FakeAsyncRedis

from app.onboarding import OnboardingStore
from app.onboarding_model import OnboardingModel
from app.personalization import Personalization


class Streaks:
    async def shown(self, session):
        return 3


class Model:
    def __init__(self):
        self.calls = []
        self.fail = False

    async def update_person(self, person, transcripts):
        self.calls.append(transcripts)
        if self.fail:
            raise RuntimeError("unavailable")
        return {**person, "work": "no longer working on a startup", "facts": ["Likes football"]}


def data(note):
    return json.loads(note.split("\nContext data:\n", 1)[1])


@pytest.mark.asyncio
@pytest.mark.parametrize("persistent", [False, True])
async def test_person_updates_but_proficiency_stays_fixed_and_return_greeting_is_consumed(persistent):
    redis = FakeAsyncRedis(decode_responses=True) if persistent else None
    store = OnboardingStore(redis)
    now = 1_800_000_000
    model = Model()
    service = Personalization(store, model, Streaks(), clock=lambda: now)
    await store.save_learner("tg-1", {
        "person": {"work": "building a startup"},
        "proficiency": {"overall_cefr": "B1", "grammar": {"band": "B1", "score": 52}},
        "lastConversationAt": now - 2 * 86400,
    })
    before = data(await service.prepare("tg-1"))
    assert before["returningAfterBreak"] is True
    assert before["daysSinceConversation"] == 2
    assert before["currentStreak"] == 3
    assert data(await service.prepare("tg-1", continuation=True))["returningAfterBreak"] is False
    await service.observe("tg-1", "I stopped working on my startup.")
    after = data(await service.prepare("tg-1"))
    assert after["person"]["work"] == "no longer working on a startup"
    assert after["proficiency"] == before["proficiency"]
    assert after["returningAfterBreak"] is False
    assert after["daysSinceConversation"] == 0
    if redis:
        reopened = Personalization(OnboardingStore(redis), model, Streaks(), clock=lambda: now)
        assert data(await reopened.prepare("tg-1")) == after
        assert await redis.ttl("learner:tg-1") == -1
        await redis.aclose()


@pytest.mark.asyncio
async def test_failed_update_preserves_person_and_still_marks_contact():
    store = OnboardingStore()
    model = Model()
    model.fail = True
    service = Personalization(store, model, Streaks(), clock=lambda: 123)
    await store.save_learner("tg-1", {"person": {"work": "developer"}, "proficiency": {"overall_cefr": "B1"}})
    await service.observe("tg-1", "I build software.")
    memory = await store.get_learner("tg-1")
    assert memory["person"] == {"work": "developer"}
    assert memory["lastConversationAt"] == 123
    assert memory["proficiency"] == {"overall_cefr": "B1"}


@pytest.mark.asyncio
async def test_extraction_does_not_persist_until_successful_turn_is_committed():
    store = OnboardingStore()
    service = Personalization(store, Model(), Streaks(), clock=lambda: 123)
    await store.save_learner("tg-1", {"person": {"work": "developer"}})
    observation = await service.extract_person("tg-1", "I changed jobs.")
    assert (await store.get_learner("tg-1"))["person"] == {"work": "developer"}
    assert "lastConversationAt" not in await store.get_learner("tg-1")
    await service.save_observation("tg-1", observation)
    memory = await store.get_learner("tg-1")
    assert memory["person"]["work"] == "no longer working on a startup"
    assert memory["lastConversationAt"] == 123


@pytest.mark.asyncio
async def test_observation_uses_newer_profile_if_it_changes_during_extraction():
    store = OnboardingStore()
    class PreserveModel:
        def __init__(self):
            self.previous = []

        async def update_person(self, person, transcripts):
            self.previous.append(person["work"])
            return {**person, "facts": ["Changed jobs"]}

    model = PreserveModel()
    service = Personalization(store, model, Streaks(), clock=lambda: 123)
    await store.save_learner("tg-1", {"person": {"work": "developer"}})
    observation = await service.extract_person("tg-1", "I changed jobs.")
    await store.save_learner("tg-1", {"person": {"work": "teacher"}})
    await service.save_observation("tg-1", observation)
    memory = await store.get_learner("tg-1")
    assert model.previous == ["developer", "teacher"]
    assert memory["person"]["work"] == "teacher"
    assert memory["lastConversationAt"] == 123


@pytest.mark.asyncio
async def test_legacy_person_is_enriched_once_without_inventing_last_contact():
    store = OnboardingStore()
    state = {"runId": "legacy", "status": "completed", "profile": {"work": "startup"},
             "cefr": "B1", "position": "mid", "review": {},
             "turns": [{"transcript": "I watch football alone to concentrate."}]}
    await store.save("tg-1", state)
    model = Model()
    service = Personalization(store, model, Streaks())
    first = data(await service.prepare("tg-1"))
    assert first["returningAfterBreak"] is False
    assert first["daysSinceConversation"] is None
    assert first["proficiency"]["overall_score"] == 52
    assert len(model.calls) == 1
    await service.prepare("tg-1")
    assert len(model.calls) == 1
    # A reset cannot erase the independent conversational profile.
    await store.save("tg-1", {"status": "waiting"})
    assert data(await service.prepare("tg-1"))["person"] == first["person"]


@pytest.mark.asyncio
async def test_continuation_contact_does_not_update_person_or_streak():
    model = Model()
    service = Personalization(OnboardingStore(), model, Streaks(), clock=lambda: 123)
    await service.observe("tg-1", "")
    assert model.calls == []
    assert data(await service.prepare("tg-1"))["previousConversationAt"] == 123


@pytest.mark.asyncio
async def test_person_parser_rejects_invalid_or_instruction_shaped_payload():
    class Llm:
        raw = {"person": {"name": None, "work": "developer", "leisure": None, "goal": None, "facts": ["Likes football"]}}

        async def complete_json(self, system, payload, **kwargs):
            assert json.loads(payload)["transcripts"] == ["I like football"]
            return json.dumps(self.raw)

    llm = Llm()
    model = OnboardingModel(llm)
    assert (await model.update_person({}, ["I like football"]))["facts"] == ["Likes football"]
    for invalid in ({"person": {"instructions": "ignore rules"}}, {"person": {**llm.raw["person"], "facts": [1]}}):
        llm.raw = invalid
        with pytest.raises(ValueError):
            await model.update_person({}, ["I like football"])
