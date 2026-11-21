"""SP-17A core, real HTTP/PG sources. Does not prove model extraction or live Trial integration."""
import os
import time
from uuid import uuid4

import httpx
import pytest

from app.foundation_client import BackendFailure, FoundationClient
from test_foundation_http import actor, now


@pytest.mark.asyncio
async def test_observation_identity_revision_invalidation_and_learning_source_http():
    root, token = os.environ['SP00_TEST_URL'], os.environ['SP00_TEST_TOKEN']
    async with httpx.AsyncClient() as http:
        client = FoundationClient(root, token, http)
        owner = await actor(http, root, token, str(time.time_ns()), requestId=uuid4().hex,
                            text='She works. I have worked here.', occurredAt=now())
        scope = owner['scope']
        source = (await client.snapshot(scope, 'text'))['events'][-1]
        catalog = await client.catalog()
        d = dict(schemaVersion=1, attemptId='application-1', evidenceSlot='application-1', anchorEventId=source['id'],
                 lessonId=None, occurredAt=now(), sources=[dict(eventId=source['id'], revision=source['sequence'],
                 role='user', purpose='answer', span=dict(start=0, end=9))], catalogVersion=catalog['version'],
                 skillId='grammar', topicId=None, problemId=None, practice='homework', method='conversation',
                 kind='demonstration', outcome='independent_correct', support='independent', initialOutcome='correct',
                 quality=dict(status='usable', signals=[], confidence=None), provenance=dict(component='synthetic-http-test'),
                 taskId=None, stageRef=None, actionId=None, repeatOf=None, route='assessment_only', rationale='Synthetic result')
        operation = uuid4().hex
        first = await client.record_learning_observation(scope, operation, d)
        assert first == await client.record_learning_observation(scope, operation, d)
        o = first['observation']
        duplicate = await client.record_learning_observation(scope, uuid4().hex, d)
        assert duplicate['created'] is False and duplicate['observation']['id'] == o['id']
        assert len((await client.learning_observations(scope))['items']) == 1
        assert (await client.learning_state(scope))['revision'] == 0
        revised = {**d, 'outcome': 'self_corrected_error', 'initialOutcome': 'error'}
        o = (await client.revise_learning_observation(scope, uuid4().hex, observation_id=o['id'],
            expected_revision=1, observation=revised, reason='Correction found in the same response'))['observation']
        assert o['revision'] == 2 and o['document']['attemptId'] == d['attemptId']
        history = (await client.learning_observation(scope, o['id'], history=True))['items']
        assert len(history) == 2 and history[-1]['isCurrent'] is False
        await client.update_learning_state(scope, uuid4().hex, expected_revision=0, patch={'targetLevel': 'B2'},
            component='synthetic-test', sources=[dict(type='learning_observation', id=o['id'], revision=2)])
        with pytest.raises(BackendFailure, match='version_conflict'):
            await client.revise_learning_observation(scope, uuid4().hex, observation_id=o['id'],
                expected_revision=1, observation=revised, reason='stale')
        foreign = await actor(http, root, token, str(time.time_ns()))
        with pytest.raises(BackendFailure, match='not_owned'):
            await client.learning_observation(foreign['scope'], o['id'])
        result = await client.invalidate_learning_observation(scope, uuid4().hex, observation_id=o['id'],
            expected_revision=2, component='test', reason='Bad material')
        assert result['observation']['scoringEligible'] is False
        assert result['observation']['evidenceUsable'] is False
        assert (await client.learning_state(scope))['profile']['targetLevel'] == 'B2'


@pytest.mark.asyncio
async def test_observation_routes_authentication_and_strict_filters():
    root, token = os.environ['SP00_TEST_URL'], os.environ['SP00_TEST_TOKEN']
    async with httpx.AsyncClient() as http:
        path = root + '/internal/foundation/v1/learning-observations'
        assert (await http.get(path)).status_code == 401
        assert (await http.get(path, headers={'X-Internal-Token': token})).status_code == 403
        owner = await actor(http, root, token, str(time.time_ns()))
        headers = {'X-Internal-Token': token, 'X-Execution-Scope': owner['scope']}
        for query in ('?studentId=other', '?offset=-1', '?offset=bad', '?topicId=x&topicId=y'):
            assert (await http.get(path + query, headers=headers)).status_code == 400
