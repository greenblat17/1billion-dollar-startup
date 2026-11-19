"""Real Python → Ktor → PostgreSQL SP-03 contract. No live model or user data."""
import os
import time
from uuid import uuid4

import httpx
import pytest

from app.context_builder import ContextBuilder
from app.foundation_client import BackendFailure, FoundationClient
from test_foundation_http import actor, now


@pytest.mark.asyncio
async def test_profile_topic_history_and_context_through_real_http():
    root, token = os.environ['SP00_TEST_URL'], os.environ['SP00_TEST_TOKEN']
    async with httpx.AsyncClient() as http:
        backend = FoundationClient(root, token, http)
        subject = str(time.time_ns())
        owner = await actor(http, root, token, subject, requestId=uuid4().hex,
                            text='I want B2 for work', occurredAt=now())
        scope = owner['scope']
        source = (await backend.snapshot(scope, 'text'))['events'][-1]
        refs = [{'type': 'event', 'id': source['id'], 'revision': source['sequence']}]
        assert (await backend.learning_state(scope))['revision'] == 0
        args = dict(expected_revision=0, patch={'targetLevel': 'B2',
                    'currentLevels': {'selfAssessment': {'overall': {'level': 'A2'}}},
                    'intensity': {'lessonsPerWeek': 3, 'lessonDurationMinutes': 30},
                    'homework': {'consent': 'yes', 'minutes': 15, 'period': 'day'}},
                    goal='Confident work conversations', component='http-test', sources=refs)
        operation = uuid4().hex
        saved = await backend.update_learning_state(scope, operation, **args)
        assert saved == await backend.update_learning_state(scope, operation, **args)
        assert saved['profile']['currentLevels']['speaky']['overall']['level'] == 'Unknown'
        with pytest.raises(BackendFailure, match='version_conflict'):
            await backend.update_learning_state(scope, uuid4().hex, **args)
        catalog = await backend.catalog()
        topic = catalog['topics'][0]['id']
        for revision, status in enumerate(('Presumed Not Known', 'Known')):
            await backend.record_topic_knowledge(scope, uuid4().hex, catalog_version=catalog['version'], topic_id=topic,
                expected_revision=revision, status=status, explanation='Stored assessment, no inference in SP-03',
                component='http-test', sources=refs)
        current = await backend.topic_knowledge(scope, catalog['version'], topic_id=topic)
        assert current['items'][0]['status'] == 'Known'
        history = await backend.topic_knowledge(scope, catalog['version'], topic_id=topic, history=True)
        assert [item['status'] for item in history['items']] == ['Known', 'Presumed Not Known']
        page = await backend.topic_knowledge(scope, catalog['version'])
        assert len(page['items']) == 50 and page['nextOffset'] == 50
        context = await ContextBuilder(backend).build(scope, 'text')
        assert context.data['learningState']['profile']['targetLevel'] == 'B2'
        assert context.data['goal']['content']['value'] == 'Confident work conversations'
        assert not context.data['facts']
        other = await actor(http, root, token, str(time.time_ns()))
        assert (await backend.learning_state(other['scope']))['revision'] == 0
        with pytest.raises(BackendFailure, match='learning_source_not_owned'):
            await backend.update_learning_state(other['scope'], uuid4().hex, **args)
        await backend.command(scope, uuid4().hex, 'learning.forget', {'expectedRevision': 1})
        fresh = await actor(http, root, token, subject)
        assert (await backend.learning_state(fresh['scope']))['profile']['targetLevel'] == 'Unknown'
        assert not (await backend.topic_knowledge(fresh['scope'], catalog['version'], topic_id=topic, history=True))['items']


@pytest.mark.asyncio
async def test_learning_routes_require_auth_scope_and_strict_query():
    root, token = os.environ['SP00_TEST_URL'], os.environ['SP00_TEST_TOKEN']
    async with httpx.AsyncClient() as http:
        response = await http.get(root + '/internal/foundation/v1/learning-state')
        assert response.status_code == 401
        response = await http.get(root + '/internal/foundation/v1/learning-state', headers={'X-Internal-Token': token})
        assert response.status_code == 403
        owner = await actor(http, root, token, str(time.time_ns()))
        headers = {'X-Internal-Token': token, 'X-Execution-Scope': owner['scope']}
        for query in ('?studentId=other', '?catalogVersion=x&offset=bad', '?catalogVersion=x&history=maybe',
                      '?catalogVersion=x&catalogVersion=y'):
            response = await http.get(root + '/internal/foundation/v1/learning-state/topics' + query, headers=headers)
            assert response.status_code == 400
