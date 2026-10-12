from __future__ import annotations

import asyncio
from types import SimpleNamespace

import httpx
import pytest

from app.dialogue import MemoryDialogueStore
from app.llm import OpenAiChatModel
from app.metrics import MemoryMetricsStore, MetricRates
from app.pipeline import ClipPipeline
from tests.conftest import FakeStt, FakeTts


def response(text: str | None = '{"notes":[]}'):
    return SimpleNamespace(
        choices=[SimpleNamespace(finish_reason="stop", message=SimpleNamespace(content=text))],
        usage=None,
    )


def http_error(status: int) -> httpx.HTTPStatusError:
    reply = httpx.Response(status, request=httpx.Request("POST", "https://example.test/chat"))
    return httpx.HTTPStatusError("provider failure", request=reply.request, response=reply)


class Completions:
    def __init__(self, results):
        self.results = list(results)
        self.calls = 0

    async def create(self, **kwargs):
        self.calls += 1
        result = self.results.pop(0)
        if isinstance(result, Exception):
            raise result
        if callable(result):
            return await result()
        return result


def service(results, timeout=8.0):
    completions = Completions(results)
    client = SimpleNamespace(chat=SimpleNamespace(completions=completions))
    metrics = MemoryMetricsStore(MetricRates())
    model = OpenAiChatModel(client, "test", metrics=metrics)
    pipeline = ClipPipeline(
        FakeStt([]), model, FakeTts(), MemoryDialogueStore(40, 86400),
        metrics, notes_timeout_seconds=timeout,
    )
    return pipeline, completions


@pytest.mark.asyncio
@pytest.mark.parametrize("first,second,outcome", [
    (http_error(429), response(), "empty"),
    (http_error(503), SimpleNamespace(choices=None, usage=None), "no_choices"),
    (response(None), response(), "empty"),
    (response("{bad"), response(), "empty"),
    (response("{bad"), response("{bad"), "invalid_json"),
])
async def test_retryable_correction_failures_have_two_total_attempts(monkeypatch, first, second, outcome):
    monkeypatch.setattr("app.llm.CORRECTION_RETRY_DELAY_SECONDS", 0.0)
    pipeline, completions = service([first, second])
    assert await pipeline.complete_live_notes("I am agree with you.") == []
    snapshot = (await pipeline.metrics.snapshot())["corrections"]
    assert completions.calls == 2
    assert {key: value["count"] for key, value in snapshot.items()} == {outcome: 1}
    assert snapshot[outcome]["secondAttempts"] == 1


@pytest.mark.asyncio
async def test_invalid_schema_does_not_retry(monkeypatch):
    monkeypatch.setattr("app.llm.CORRECTION_RETRY_DELAY_SECONDS", 0.0)
    pipeline, completions = service([response('{"notes":"not a list"}')])
    assert await pipeline.complete_live_notes("I am agree with you.") == []
    assert completions.calls == 1
    assert (await pipeline.metrics.snapshot())["corrections"]["invalid_schema"]["secondAttempts"] == 0


@pytest.mark.asyncio
async def test_no_second_attempt_when_deadline_is_too_near(monkeypatch):
    async def slow_failure():
        await asyncio.sleep(0.03)
        raise http_error(503)

    monkeypatch.setattr("app.llm.CORRECTION_RETRY_DELAY_SECONDS", 0.0)
    pipeline, completions = service([slow_failure], timeout=0.07)
    assert await pipeline.complete_live_notes("I am agree with you.") == []
    assert completions.calls == 1
    assert (await pipeline.metrics.snapshot())["corrections"]["provider_5xx"]["count"] == 1


@pytest.mark.asyncio
async def test_deadline_cancels_provider_and_records_only_one_result():
    cancelled = False

    async def hanging():
        nonlocal cancelled
        try:
            await asyncio.Event().wait()
        except asyncio.CancelledError:
            cancelled = True
            raise

    pipeline, completions = service([hanging], timeout=0.03)
    assert await pipeline.complete_live_notes("I am agree with you.") == []
    assert cancelled
    assert completions.calls == 1
    snapshot = (await pipeline.metrics.snapshot())["corrections"]
    assert list(snapshot) == ["deadline"]
    assert snapshot["deadline"]["count"] == 1
    assert snapshot["deadline"]["secondAttempts"] == 0
    assert 20 <= snapshot["deadline"]["elapsedMs"] < 100
