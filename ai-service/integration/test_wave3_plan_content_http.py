"""A pedagogical Plan can supply the StageRef required by the content contract."""

import os
import time
from uuid import uuid4

import httpx
import pytest

from app.foundation_client import FoundationClient
from app.pedagogical_plan import PedagogicalPlanner, stage_reference
from test_foundation_http import actor, now
from test_learning_content_http import document


@pytest.mark.asyncio
async def test_pedagogical_stage_accepts_versioned_content():
    root, token = os.environ['SP00_TEST_URL'], os.environ['SP00_TEST_TOKEN']
    async with httpx.AsyncClient() as http:
        backend = FoundationClient(root, token, http)
        owner = await actor(http, root, token, str(time.time_ns()), requestId=uuid4().hex,
                            text='I want B2 for work', occurredAt=now())
        scope = owner['scope']
        source = (await backend.snapshot(scope, 'text'))['events'][-1]
        await backend.update_learning_state(scope, uuid4().hex, expected_revision=0, patch={},
            goal='B2 for work', component='wave3-http-test',
            sources=[{'type': 'event', 'id': source['id'], 'revision': source['sequence']}])
        catalog = await backend.catalog()
        topic = next(item for item in catalog['topics'] if item['skillId'] == 'grammar')
        task = (await backend.create_learning_task(scope, uuid4().hex, goal_revision=1,
            catalog_version=catalog['version'], skill_id='grammar', topic_id=topic['id'],
            problem_id='grammar_application', component='wave3-http-test'))['task']

        result = await PedagogicalPlanner(backend).ensure_plan(scope, task['id'], uuid4().hex)
        assert result['status'] == 'created'
        plan = result['plan']
        stage = next(item for item in plan['definition']['stages'] if item['method'] == 'Exercises')
        current_task = (await backend.learning_task(scope, task['id']))['task']
        material = document(current_task, plan['id'])
        material['stageRef'] = stage_reference(plan, stage['id'])
        material['cefr'] = topic['cefr']

        content = (await backend.create_learning_content(scope, uuid4().hex, document=material,
            component='wave3-http-test'))['content']
        assert (await backend.learning_content(scope, content['id']))['content']['document'] == material
        assert (await backend.learning_task(scope, task['id']))['task']['progress'] == 0
