import json

import httpx
import pytest

from app.foundation_client import BackendFailure, FoundationClient


@pytest.mark.asyncio
async def test_plan_adapter_keeps_historical_identity_and_explicit_cursor_null():
    requests = []

    def handle(request):
        requests.append(request)
        return httpx.Response(200, json={'ok': True, 'result': {'schemaVersion': 1}})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as http:
        backend = FoundationClient('http://backend', 'service', http)
        await backend.learning_stage('scope', task_id='task', plan_id='plan', plan_revision=2, stage_id='exercise')
        await backend.revise_learning_plan('scope', 'same-operation', task_id='task', plan_id='plan',
            expected_task_revision=7, expected_plan_revision=2, expected_state_revision=9,
            definition={}, component='planner', currentStageId=None)
        await backend.revise_learning_plan('scope', 'next-operation', task_id='task', plan_id='plan',
            expected_task_revision=8, expected_plan_revision=3, expected_state_revision=10,
            definition={}, component='planner')
    assert requests[0].url.path.endswith('/plan/stages/exercise')
    assert dict(requests[0].url.params) == {'taskId': 'task', 'revision': '2'}
    assert all(r.headers['X-Execution-Scope'] == 'scope' for r in requests)
    payload = json.loads(requests[1].content)
    assert payload['operationId'] == 'same-operation'
    assert payload['args']['expectedStateRevision'] == 9
    assert payload['args']['currentStageId'] is None
    assert 'currentStageId' not in json.loads(requests[2].content)['args']


@pytest.mark.asyncio
async def test_unknown_plan_schema_fails_closed():
    async with httpx.AsyncClient(transport=httpx.MockTransport(lambda r:
            httpx.Response(200, json={'ok': True, 'result': {'schemaVersion': 999}}))) as http:
        backend = FoundationClient('http://backend', 'service', http)
        with pytest.raises(BackendFailure, match='unsupported_plan_schema'):
            await backend.learning_plan('scope', 'plan')
