import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.context_builder import TutorContext
from app.roadmap import RoadmapPlanner, RoadmapResult


@pytest.mark.asyncio
async def test_roadmap_uses_strict_schema_and_shared_evidence_without_token_cap():
    result = {'decision': 'revise', 'reason': 'Work goal and trial difficulty',
              'steps': [{'id': 'reasons', 'outcome': 'Explain a choice', 'tactic': 'Practise work choices'}],
              'sourceIds': ['trial-source']}
    model = SimpleNamespace(complete_json=AsyncMock(return_value=json.dumps(result)))
    context = TutorContext({'goal': {'content': {'value': 'Work meetings'}},
        'observations': [{'lesson_id': 'trial', 'source_event_id': 'trial-source', 'uncertainty': 'limited'}]}, [], False)
    assert await RoadmapPlanner(model).prepare(context, {}) == result
    call = model.complete_json.call_args
    assert json.loads(call.args[1]) == context.data
    assert call.kwargs == {'operation': 'roadmap_prepare', 'retry': False,
                           'response_format': RoadmapResult.response_format()}
    assert 'two distinct lessons' in call.args[0]
    assert 'preserve EVERY existing step id and outcome' in call.args[0]


@pytest.mark.asyncio
@pytest.mark.parametrize('result', [
    {'decision': 'revise', 'reason': 'x', 'steps': [], 'sourceIds': []},
    {'decision': 'keep', 'reason': 'x', 'steps': [], 'sourceIds': ['invented']},
    {'decision': 'insufficient_evidence', 'reason': 'x', 'steps': [{'id': 'a', 'outcome': 'b', 'tactic': 'c'}], 'sourceIds': []},
    {'decision': 'mastered', 'reason': 'x', 'steps': [], 'sourceIds': []},
])
async def test_invalid_roadmap_has_no_fabricated_fallback(result):
    model = SimpleNamespace(complete_json=AsyncMock(return_value=json.dumps(result)))
    with pytest.raises(ValueError):
        await RoadmapPlanner(model).prepare(TutorContext({'observations': []}, [], False), {})


@pytest.mark.asyncio
async def test_insufficient_evidence_is_a_valid_honest_outcome():
    result = {'decision': 'insufficient_evidence', 'reason': 'Trial audio evidence is missing', 'steps': [], 'sourceIds': []}
    model = SimpleNamespace(complete_json=AsyncMock(return_value=json.dumps(result)))
    assert await RoadmapPlanner(model).prepare(TutorContext({'observations': []}, [], False), {}) == result


@pytest.mark.asyncio
async def test_compute_timeout_cancels_model_work_and_returns_typed_failure(monkeypatch):
    import asyncio
    import httpx
    from fastapi import FastAPI
    import app.foundation_api as api
    cancelled = []
    async def compute(*args):
        try:
            await asyncio.Event().wait()
        finally:
            cancelled.append(True)
    real_timeout = asyncio.timeout
    def short_timeout(seconds):
        assert seconds == 120
        return real_timeout(0.01)
    monkeypatch.setattr(api, 'asyncio', SimpleNamespace(timeout=short_timeout))
    app = FastAPI()
    api.install_foundation_api(app, None, SimpleNamespace(compute=compute))
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://test') as client:
        response = await client.post('/internal/foundation/compute', headers={'X-Execution-Scope': 'test'}, json={})
    assert response.status_code == 503
    assert response.json() == {'detail': 'provider_timeout'}
    assert cancelled == [True]
