"""SP-12A real Python → Ktor → PostgreSQL. No models or live learner data."""
import copy
import os
import time
from uuid import uuid4

import httpx
import pytest

from app.foundation_client import BackendFailure, FoundationClient
from test_foundation_http import actor, now


def definition():
    return {'schemaVersion': 1, 'stages': [
        {'id': 'explain', 'method': 'Explanation', 'kind': 'binary', 'required': True,
         'condition': 'understanding_demonstrated'},
        {'id': 'exercise', 'method': 'Exercises', 'kind': 'numeric', 'required': True, 'weight': .3},
        {'id': 'converse', 'method': 'Conversation', 'kind': 'numeric', 'required': True, 'weight': .7},
    ], 'order': ['explain', 'exercise', 'converse'], 'transitions': [
        {'from': 'explain', 'to': 'exercise'}, {'from': 'exercise', 'to': 'converse'},
        {'from': 'converse', 'to': 'exercise'}, {'from': 'exercise', 'to': 'explain'}]}


async def setup(http, backend, root, token):
    owner = await actor(http, root, token, str(time.time_ns()), requestId=uuid4().hex,
                        text='I want B2 for work', occurredAt=now())
    scope = owner['scope']
    event = (await backend.snapshot(scope, 'text'))['events'][-1]
    await backend.update_learning_state(scope, uuid4().hex, expected_revision=0, patch={}, goal='B2 for work',
        component='http-test', sources=[{'type': 'event', 'id': event['id'], 'revision': event['sequence']}])
    catalog = await backend.catalog()
    topic = next(t for t in catalog['topics'] if t['skillId'] == 'grammar')
    task = (await backend.create_learning_task(scope, uuid4().hex, goal_revision=1,
        catalog_version=catalog['version'], skill_id='grammar', topic_id=topic['id'],
        problem_id='grammar_application', component='http-test'))['task']
    return scope, task


@pytest.mark.asyncio
async def test_task_plan_cursor_revision_and_historical_consumers():
    root, token = os.environ['SP00_TEST_URL'], os.environ['SP00_TEST_TOKEN']
    async with httpx.AsyncClient() as http:
        backend = FoundationClient(root, token, http)
        scope, before = await setup(http, backend, root, token)
        assert before['details']['plan'] is None
        args = dict(task_id=before['id'], expected_task_revision=before['revision'], definition=definition(), component='http-test')
        operation = uuid4().hex
        created = await backend.create_learning_plan(scope, operation, **args)
        assert created == await backend.create_learning_plan(scope, operation, **args)
        plan = created['plan']
        task = (await backend.learning_task(scope, before['id']))['task']
        assert all(task[k] == before[k] for k in ('skill_id', 'topic_id', 'problem_id', 'lifecycle', 'progress', 'goal_revision'))
        assert task['details']['plan'] == {'id': plan['id'], 'revision': 1}
        for stage in ['explain', 'exercise', 'converse', 'exercise']:
            plan = (await backend.select_learning_stage(scope, uuid4().hex, plan_id=plan['id'],
                expected_plan_revision=1, expected_state_revision=plan['currentState']['stateRevision'],
                current_stage_id=stage, reason='plan_transition', component='http-test'))['plan']
        old_ref = dict(task_id=task['id'], plan_id=plan['id'], plan_revision=1, stage_id='exercise')
        old_stage = (await backend.learning_stage(scope, **old_ref))['stage']
        revised = copy.deepcopy(definition())
        revised['stages'][1]['weight'], revised['stages'][2]['weight'] = .4, .6
        revised['order'] = ['converse', 'exercise', 'explain']
        plan = (await backend.revise_learning_plan(scope, uuid4().hex, task_id=task['id'], plan_id=plan['id'],
            expected_task_revision=task['revision'], expected_plan_revision=1,
            expected_state_revision=plan['currentState']['stateRevision'], definition=revised, component='http-test'))['plan']
        assert plan['currentState']['currentStageId'] == 'exercise'
        assert (await backend.learning_stage(scope, **old_ref))['stage'] == old_stage
        assert (await backend.learning_stage(scope, **{**old_ref, 'plan_revision': 2}))['stage']['weight'] == .4
        assert (await backend.learning_plan(scope, plan['id'], revision=1))['plan']['definition'] == definition()
        assert len((await backend.learning_plan(scope, plan['id'], history=True))['items']) == 6
        assert (await backend.learning_task(scope, task['id']))['task']['progress'] == 0
        other = await actor(http, root, token, str(time.time_ns()))
        with pytest.raises(BackendFailure, match='not_owned'):
            await backend.learning_stage(other['scope'], **old_ref)
        with pytest.raises(BackendFailure, match='version_conflict'):
            await backend.select_learning_stage(scope, uuid4().hex, plan_id=plan['id'],
                expected_plan_revision=1, expected_state_revision=1,
                current_stage_id='converse', reason='student_request', component='http-test')


@pytest.mark.asyncio
@pytest.mark.parametrize('failure', ['sum', 'duplicate_method', 'binary_weight', 'missing_stage', 'fake_task_link'])
async def test_invalid_contract_cannot_change_task(failure):
    root, token = os.environ['SP00_TEST_URL'], os.environ['SP00_TEST_TOKEN']
    async with httpx.AsyncClient() as http:
        backend = FoundationClient(root, token, http)
        scope, task = await setup(http, backend, root, token)
        d = definition()
        if failure == 'sum':
            d['stages'][1]['weight'] = .31
        elif failure == 'duplicate_method':
            d['stages'][2]['method'] = 'Exercises'
        elif failure == 'binary_weight':
            d['stages'][0]['weight'] = 0
        elif failure == 'missing_stage':
            d['transitions'][0]['to'] = 'absent'
        with pytest.raises(BackendFailure):
            if failure == 'fake_task_link':
                await backend.change_learning_task(scope, uuid4().hex, task_id=task['id'], expected_revision=1,
                    action='update', component='http-test', patch={'plan': {'id': 'missing', 'revision': 1}})
            else:
                await backend.create_learning_plan(scope, uuid4().hex, task_id=task['id'], expected_task_revision=1,
                    definition=d, component='http-test')
        assert (await backend.learning_task(scope, task['id']))['task'] == task


@pytest.mark.asyncio
async def test_plan_routes_require_auth_and_strict_historical_reference():
    root, token = os.environ['SP00_TEST_URL'], os.environ['SP00_TEST_TOKEN']
    async with httpx.AsyncClient() as http:
        path = root + '/internal/foundation/v1/learning-plans/missing'
        assert (await http.get(path)).status_code == 401
        assert (await http.get(path, headers={'X-Internal-Token': token})).status_code == 403
        owner = await actor(http, root, token, str(time.time_ns()))
        headers = {'X-Internal-Token': token, 'X-Execution-Scope': owner['scope']}
        for query, code in [('?studentId=other', 'invalid_plan_query'), ('?revision=0', 'invalid_plan_revision'),
                            ('?revision=1&revision=2', 'invalid_plan_query'), ('?revision=x', 'invalid_plan_revision')]:
            response = await http.get(path + query, headers=headers)
            assert (response.status_code, response.json()) == (400, {'ok': False, 'error': code})
        stage_path = path + '/stages/exercise?taskId=task'
        for query, code in [('', 'invalid_plan_revision'), ('&revision=0', 'invalid_plan_revision'),
                            ('&revision=x', 'invalid_plan_revision'), ('&revision=1&revision=2', 'invalid_plan_query')]:
            response = await http.get(stage_path + query, headers=headers)
            assert (response.status_code, response.json()) == (400, {'ok': False, 'error': code})
