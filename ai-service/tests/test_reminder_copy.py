import json
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from app.reminder_copy import personal_reminder
from tests.conftest import build_app


class FakeLlm:
    def __init__(self, response):
        self.response = response
        self.calls = 0

    async def complete_json(self, *_args, **_kwargs):
        self.calls += 1
        return json.dumps(self.response)


@pytest.mark.asyncio
async def test_personal_reminder_uses_verified_source():
    fact = "I have started a new job"
    memory = SimpleNamespace(prepare=lambda _session: async_value(
        'Context data:\n' + json.dumps({"person": {"facts": [fact]}, "proficiency": {}})))
    dialogue = SimpleNamespace(history=lambda _session: async_value([]))
    model = SimpleNamespace(llm=FakeLlm({
        "text": "How is your new job going? Tell me in English!",
        "openingQuestion": "What do you like about your new job?", "source": fact}))
    assert await personal_reminder("tg-1", memory, dialogue, model) == {
        "text": "How is your new job going? Tell me in English!",
        "openingQuestion": "What do you like about your new job?",
    }


@pytest.mark.asyncio
async def test_personal_reminder_falls_back_without_source():
    memory = SimpleNamespace(prepare=lambda _session: async_value(
        'Context data:\n' + json.dumps({"person": {"facts": []}, "proficiency": {}})))
    dialogue = SimpleNamespace(history=lambda _session: async_value([]))
    llm = FakeLlm({"text": "How is your job?", "openingQuestion": "What do you do?", "source": "invented"})
    assert await personal_reminder("tg-1", memory, dialogue, SimpleNamespace(llm=llm)) is None
    assert llm.calls == 0


@pytest.mark.asyncio
async def test_personal_reminder_rejects_unrelated_text_even_with_valid_source():
    fact = "I enjoy cooking pasta"
    memory = SimpleNamespace(prepare=lambda _session: async_value(
        'Context data:\n' + json.dumps({"person": {"facts": [fact]}, "proficiency": {}})))
    dialogue = SimpleNamespace(history=lambda _session: async_value([]))
    model = SimpleNamespace(llm=FakeLlm({"text": "How is your marathon going?",
                                         "openingQuestion": "What pasta do you cook?", "source": fact}))
    assert await personal_reminder("tg-1", memory, dialogue, model) is None


@pytest.mark.asyncio
async def test_personal_reminder_rejects_unrelated_spoken_opener():
    fact = "I enjoy cooking pasta"
    memory = SimpleNamespace(prepare=lambda _session: async_value(
        'Context data:\n' + json.dumps({"person": {"facts": [fact]}, "proficiency": {}})))
    dialogue = SimpleNamespace(history=lambda _session: async_value([]))
    model = SimpleNamespace(llm=FakeLlm({"text": "What pasta do you enjoy cooking?",
                                         "openingQuestion": "How was your marathon?", "source": fact}))
    assert await personal_reminder("tg-1", memory, dialogue, model) is None


@pytest.mark.asyncio
async def test_personal_reminder_rejects_two_different_topics_from_same_memory():
    fact = "I love music and cooking"
    memory = SimpleNamespace(prepare=lambda _session: async_value(
        'Context data:\n' + json.dumps({"person": {"facts": [fact]}, "proficiency": {}})))
    dialogue = SimpleNamespace(history=lambda _session: async_value([]))
    model = SimpleNamespace(llm=FakeLlm({"text": "What music do you like?",
                                         "openingQuestion": "What have you been cooking?", "source": fact}))
    assert await personal_reminder("tg-1", memory, dialogue, model) is None


async def async_value(value):
    return value


def test_personal_copy_endpoint_is_internal_and_falls_back_without_memory():
    app, *_ = build_app()
    with TestClient(app) as client:
        assert client.post("/internal/reminders/personal-copy", json={"sessionId": "tg-1"}).status_code == 401
        response = client.post("/internal/reminders/personal-copy", json={"sessionId": "tg-1"},
                               headers={"X-Internal-Token": "test-internal-token"})
        assert response.status_code == 200
        assert response.json() == {"text": None, "openingQuestion": None}
