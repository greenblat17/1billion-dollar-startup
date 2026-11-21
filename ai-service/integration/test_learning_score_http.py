"""SP-19A score state through Python → Ktor → PostgreSQL, using fixture evidence."""

import os
import time
from uuid import uuid4

import httpx
import pytest

from app.foundation_client import BackendFailure, FoundationClient
from test_foundation_http import actor
from test_learning_plans_http import definition, setup


@pytest.mark.asyncio
async def test_score_fixture_is_scoped_persisted_and_idempotent():
    root, token = os.environ['SP00_TEST_URL'], os.environ['SP00_TEST_TOKEN']
    async with httpx.AsyncClient() as http:
        backend = FoundationClient(root, token, http)
        scope, task = await setup(http, backend, root, token)
        plan = (await backend.create_learning_plan(
            scope, uuid4().hex, task_id=task['id'], expected_task_revision=task['revision'],
            definition=definition(), component='wave4-http-test'))['plan']

        initial = await backend.learning_score(scope, task['id'])
        assert initial['progress'] == 0
        assert initial['masteryReady'] is False

        operation_id = uuid4().hex
        fixture = {'taskId': task['id'], 'planId': plan['id'], 'planRevision': 1,
                   'stageId': 'exercise', 'fixtureEventId': uuid4().hex,
                   'outcome': 'independent_correct'}
        written = await backend.command(scope, operation_id, 'learning.score.fixture', fixture)
        assert await backend.command(scope, operation_id, 'learning.score.fixture', fixture) == written
        current = await backend.learning_score(scope, task['id'])
        assert current['progress'] == pytest.approx(100 * .3 * 6 / 35)
        assert next(stage for stage in current['stages'] if stage['stageId'] == 'exercise')['score'] == 56
        assert (await backend.learning_task(scope, task['id']))['task']['progress'] == pytest.approx(current['progress'])
        assert len((await backend.learning_score(scope, task['id'], history=True))['items']) == 1

        other = await actor(http, root, token, str(time.time_ns()))
        with pytest.raises(BackendFailure, match='not_owned'):
            await backend.learning_score(other['scope'], task['id'])
