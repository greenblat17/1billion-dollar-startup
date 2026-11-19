import json
import httpx
import pytest
from app.foundation_client import BackendFailure, FoundationClient


@pytest.mark.asyncio
async def test_task_adapter_preserves_explicit_nulls_revisions_scope_and_operation():
    requests = []
    def handle(request):
        requests.append(request)
        return httpx.Response(200, json={'ok': True, 'result': {'schemaVersion': 1}})
    async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as http:
        backend = FoundationClient('http://backend', 'service', http)
        await backend.learning_task('scope', 'task-id', history=True, offset=50)
        await backend.change_learning_task('scope', 'same-operation', task_id='task-id', expected_revision=7,
            action='update', component='review', patch={'plan': None, 'priority': None})
        with pytest.raises(ValueError):
            await backend.change_learning_task('scope', 'bad', task_id='task-id', expected_revision=7,
                action='erase', component='review')
    assert len(requests) == 2
    assert requests[0].url.path.endswith('/task-id/history') and requests[0].url.params['offset'] == '50'
    assert all(r.headers['X-Execution-Scope'] == 'scope' for r in requests)
    payload = json.loads(requests[1].content)
    assert payload['operationId'] == 'same-operation'
    assert payload['args']['expectedRevision'] == 7
    assert payload['args']['patch'] == {'plan': None, 'priority': None}


@pytest.mark.asyncio
async def test_unknown_task_schema_fails_closed():
    async with httpx.AsyncClient(transport=httpx.MockTransport(lambda r:
            httpx.Response(200, json={'ok': True, 'result': {'schemaVersion': 999}}))) as http:
        backend = FoundationClient('http://backend', 'service', http)
        with pytest.raises(BackendFailure, match='unsupported_task_schema'):
            await backend.learning_tasks('scope')
