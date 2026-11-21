"""SP-12B real Python → Ktor → PostgreSQL handoff on synthetic Task data."""
import os
import time
from uuid import uuid4

import httpx
import pytest

from app.foundation_client import FoundationClient
from app.pedagogical_plan import PedagogicalPlanner, advise_stage, stage_reference
from test_foundation_http import actor, now


@pytest.mark.asyncio
async def test_six_task_templates_attach_and_preserve_progress():
    root, token = os.environ['SP00_TEST_URL'], os.environ['SP00_TEST_TOKEN']
    async with httpx.AsyncClient() as http:
        backend = FoundationClient(root, token, http)
        owner = await actor(http, root, token, str(time.time_ns()), requestId=uuid4().hex,
                            text='I want B2 for work', occurredAt=now())
        scope = owner['scope']
        source = (await backend.snapshot(scope, 'text'))['events'][-1]
        await backend.update_learning_state(scope, uuid4().hex, expected_revision=0, patch={},
            goal='B2 for work', component='sp12b-http-test',
            sources=[{'type': 'event', 'id': source['id'], 'revision': source['sequence']}])
        catalog = await backend.catalog()
        planner = PedagogicalPlanner(backend)
        expected = {
            'grammar_unknown': ('grammar', ['Explanation', 'Exercises', 'Conversation'], [0.3, 0.7]),
            'grammar_application': ('grammar', ['Exercises', 'Conversation'], [0.3, 0.7]),
            'speaking_barrier': ('speaking', ['Conversation'], [1.0]),
            'listening_comprehension': ('listening', ['Exposure'], [1.0]),
            'vocabulary_passive': ('vocabulary', ['Exercises'], [1.0]),
            'vocabulary_active': ('vocabulary', ['Exercises', 'Conversation'], [0.3, 0.7]),
        }
        for problem, (skill, methods, weights) in expected.items():
            topic = next(item for item in catalog['topics'] if item['skillId'] == skill)
            task = (await backend.create_learning_task(scope, uuid4().hex, goal_revision=1,
                catalog_version=catalog['version'], skill_id=skill, topic_id=topic['id'],
                problem_id=problem, component='sp12b-http-test'))['task']
            assert task['details']['plan'] is None
            result = await planner.ensure_plan(scope, task['id'], uuid4().hex)
            assert result['status'] == 'created'
            plan = result['plan']
            assert [stage['method'] for stage in plan['definition']['stages']] == methods
            assert [stage['weight'] for stage in plan['definition']['stages'] if stage['kind'] == 'numeric'] == weights
            assert plan['currentState']['currentStageId'] == plan['definition']['order'][0]
            assert (await planner.ensure_plan(scope, task['id'], uuid4().hex))['status'] == 'existing'
            after = (await backend.learning_task(scope, task['id']))['task']
            assert all(after[key] == task[key] for key in ('skill_id', 'topic_id', 'problem_id', 'progress'))
            assert after['details']['plan'] == {'id': plan['id'], 'revision': 1}
            for stage in plan['definition']['stages']:
                ref = stage_reference(plan, stage['id'])
                assert (await backend.learning_stage(scope, task_id=ref['taskId'], plan_id=ref['planId'],
                    plan_revision=ref['planRevision'], stage_id=ref['stageId']))['stage'] == stage
            assert advise_stage(plan['definition'], plan['definition']['order'][0], learner_refused=True).action == 'pause_task'
            assert (await backend.learning_task(scope, task['id']))['task']['progress'] == 0
            if problem == 'grammar_unknown':
                refused = await planner.apply_stage_result(scope, uuid4().hex, plan, learner_refused=True)
                assert refused['plan']['currentState'] == plan['currentState']
                first = await planner.apply_stage_result(scope, uuid4().hex, plan,
                    understanding_demonstrated=True)
                assert first['plan']['currentState']['currentStageId'] == 'exercises'
                assert first['plan']['revision'] == plan['revision']
                advanced = await planner.apply_stage_result(scope, uuid4().hex, first['plan'], score=85)
                assert advanced['plan']['currentState']['currentStageId'] == 'conversation'
                assert advanced['plan']['revision'] == plan['revision']
                assert (await backend.learning_task(scope, task['id']))['task']['progress'] == 0
