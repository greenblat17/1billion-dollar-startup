import json
from pathlib import Path

import httpx
import pytest

from app.foundation_client import BackendFailure, FoundationClient
from app.learning_observations import LearningObservation


@pytest.mark.asyncio
async def test_observation_adapter_preserves_identity_revisions_nulls_and_scope():
    requests = []

    def handle(request):
        requests.append(request)
        return httpx.Response(200, json={'ok': True, 'result': {'schemaVersion': 1}})

    observation: LearningObservation = json.loads((Path(__file__).parents[2] / 'observation-contract/v1/examples.json').read_text())[0]['observation']
    async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as http:
        client = FoundationClient('http://backend', 'service', http)
        await client.record_learning_observation('scope', 'retry-id', observation)
        await client.revise_learning_observation('scope', 'rev-id', observation_id='obs',
            expected_revision=2, observation=observation, reason='hint found')
        await client.invalidate_learning_observation('scope', 'invalid-id', observation_id='obs',
            expected_revision=3, component='review', reason='defective material')
        await client.learning_observation('scope', 'obs', history=True, offset=50)
        await client.learning_observations('scope', lesson_id='lesson', task_id='task')
    assert all(r.headers['X-Execution-Scope'] == 'scope' for r in requests)
    assert json.loads(requests[0].content)['args']['observation'] == observation
    assert json.loads(requests[1].content)['args']['expectedRevision'] == 2
    assert json.loads(requests[2].content)['action'] == 'learning.observation.invalidate'
    assert requests[3].url.path.endswith('/obs/history')
    assert requests[3].url.params['offset'] == '50'
    assert requests[4].url.params['lessonId'] == 'lesson'


@pytest.mark.asyncio
async def test_unknown_observation_schema_fails_closed():
    async with httpx.AsyncClient(transport=httpx.MockTransport(lambda r:
            httpx.Response(200, json={'ok': True, 'result': {'schemaVersion': 2}}))) as http:
        client = FoundationClient('http://backend', 'service', http)
        with pytest.raises(BackendFailure, match='unsupported_observation_schema'):
            await client.learning_observations('scope')
