"""SP-04 real Python → Ktor → PostgreSQL; synthetic data, no AI calls."""
import os
import time
from uuid import uuid4

import httpx
import pytest

from app.foundation_client import BackendFailure, FoundationClient
from test_foundation_http import actor, now


@pytest.mark.asyncio
async def test_task_lifecycle_http_and_independent_topic_knowledge():
    root, token = os.environ['SP00_TEST_URL'], os.environ['SP00_TEST_TOKEN']
    async with httpx.AsyncClient() as http:
        backend = FoundationClient(root, token, http)
        owner = await actor(http, root, token, str(time.time_ns()), requestId=uuid4().hex,
                            text='I want B2 for work', occurredAt=now())
        scope = owner['scope']
        event = (await backend.snapshot(scope, 'text'))['events'][-1]
        sources = [{'type': 'event', 'id': event['id'], 'revision': event['sequence']}]
        await backend.update_learning_state(scope, uuid4().hex, expected_revision=0, patch={},
                                            goal='B2 for work', component='http-test', sources=sources)
        catalog = await backend.catalog()
        topic = next(t for t in catalog['topics'] if t['skillId'] == 'grammar')
        args = dict(goal_revision=1, catalog_version=catalog['version'], skill_id='grammar',
                    topic_id=topic['id'], problem_id='grammar_application', component='http-test', sources=sources)
        operation = uuid4().hex
        result = await backend.create_learning_task(scope, operation, **args)
        assert result == await backend.create_learning_task(scope, operation, **args)
        task = result['task']
        assert task['lifecycle'] == 'Identified' and task['progress'] == 0
        duplicate = await backend.create_learning_task(scope, uuid4().hex, **args)
        assert duplicate['created'] is False and duplicate['task']['id'] == task['id']
        for state, progress in [('In Progress', 30), ('Mastered', 100), ('Reopened', 100), ('In Progress', 40)]:
            task = (await backend.change_learning_task(scope, uuid4().hex, task_id=task['id'],
                expected_revision=task['revision'], action='transition', component='http-test',
                lifecycle=state, progress=progress, decisionId=uuid4().hex))['task']
        assert (await backend.learning_task(scope, task['id']))['task'] == task
        history = (await backend.learning_task(scope, task['id'], history=True))['items']
        assert len(history) == 5 and history[-1]['state']['progress'] == 0
        assert history[0]['previous_state']['lifecycle'] == 'Reopened'
        tasks = await backend.learning_tasks(scope, topic_id=topic['id'], problem_id='grammar_application')
        assert [t['id'] for t in tasks['items']] == [task['id']]
        assert (await backend.topic_knowledge(scope, catalog['version'], topic_id=topic['id']))['items'][0]['status'] == 'Unknown'
        other = await actor(http, root, token, str(time.time_ns()))
        with pytest.raises(BackendFailure, match='not_owned'):
            await backend.learning_task(other['scope'], task['id'])
        with pytest.raises(BackendFailure, match='version_conflict'):
            await backend.change_learning_task(scope, uuid4().hex, task_id=task['id'], expected_revision=1,
                action='update', component='http-test', patch={'priority': 1})
        with pytest.raises(BackendFailure, match='invalid_task_progress'):
            await backend.change_learning_task(scope, uuid4().hex, task_id=task['id'], expected_revision=task['revision'],
                action='progress', component='http-test', progress=101, decisionId='invalid-score')


@pytest.mark.asyncio
async def test_task_routes_require_auth_scope_and_strict_queries():
    root, token = os.environ['SP00_TEST_URL'], os.environ['SP00_TEST_TOKEN']
    async with httpx.AsyncClient() as http:
        path = root + '/internal/foundation/v1/learning-tasks'
        assert (await http.get(path)).status_code == 401
        assert (await http.get(path, headers={'X-Internal-Token': token})).status_code == 403
        owner = await actor(http, root, token, str(time.time_ns()))
        headers = {'X-Internal-Token': token, 'X-Execution-Scope': owner['scope']}
        for query in ('?studentId=other', '?offset=-1', '?offset=bad', '?lifecycle=bad', '?topicId=x&topicId=y'):
            assert (await http.get(path + query, headers=headers)).status_code == 400
