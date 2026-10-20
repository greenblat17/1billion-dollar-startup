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
        "text": "How is your new job going? Tell me in English!", "source": fact}))
    assert await personal_reminder("tg-1", memory, dialogue, model) == (
        "How is your new job going? Tell me in English!")


@pytest.mark.asyncio
async def test_personal_reminder_falls_back_without_source():
    memory = SimpleNamespace(prepare=lambda _session: async_value(
        'Context data:\n' + json.dumps({"person": {"facts": []}, "proficiency": {}})))
    dialogue = SimpleNamespace(history=lambda _session: async_value([]))
    llm = FakeLlm({"text": "How is your job?", "source": "invented"})
    assert await personal_reminder("tg-1", memory, dialogue, SimpleNamespace(llm=llm)) is None
    assert llm.calls == 0


@pytest.mark.asyncio
async def test_personal_reminder_rejects_unrelated_text_even_with_valid_source():
    fact = "I enjoy cooking pasta"
    memory = SimpleNamespace(prepare=lambda _session: async_value(
        'Context data:\n' + json.dumps({"person": {"facts": [fact]}, "proficiency": {}})))
    dialogue = SimpleNamespace(history=lambda _session: async_value([]))
    model = SimpleNamespace(llm=FakeLlm({"text": "How is your marathon going?", "source": fact}))
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
        assert response.json() == {"text": None}
