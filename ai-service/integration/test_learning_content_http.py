"""SP-13A Python → Ktor → PostgreSQL contract; synthetic material only."""
import copy
import os
import time
from datetime import datetime, timedelta, timezone
from uuid import uuid4

import httpx
import pytest

from app.foundation_client import BackendFailure, FoundationClient
from test_foundation_http import actor
from test_learning_plans_http import definition, setup


def document(task, plan_id):
    return {
        'schemaVersion': 1, 'taskId': task['id'],
        'stageRef': {'taskId': task['id'], 'planId': plan_id, 'planRevision': 1, 'stageId': 'exercise'},
        'catalogVersion': task['catalog_version'], 'skillId': task['skill_id'],
        'topicId': task['topic_id'], 'problemId': task['problem_id'],
        'method': 'Exercises', 'actionKind': 'sentence_transformation', 'cefr': 'A2',
        'objective': 'Use a past form in speech.', 'spokenInstruction': 'Say this in the past tense.',
        'payload': {'stimulus': 'I go to work yesterday.', 'targetOperation': 'Use the past form.'},
        'answerGuide': {'criteria': ['Preserve the meaning and use a past form.'],
                        'alternatives': ['Natural equivalent wording is acceptable.'],
                        'unacceptable': ['A present form for a completed past action.']},
        'validation': {'status': 'passed', 'component': 'synthetic-fixture', 'reasons': []},
    }


@pytest.mark.asyncio
async def test_content_versions_and_historical_stage_survive_rewording():
    root, token = os.environ['SP00_TEST_URL'], os.environ['SP00_TEST_TOKEN']
    async with httpx.AsyncClient() as http:
        backend = FoundationClient(root, token, http)
        scope, original = await setup(http, backend, root, token)
        plan = (await backend.create_learning_plan(scope, uuid4().hex, task_id=original['id'],
            expected_task_revision=original['revision'], definition=definition(), component='http-test'))['plan']
        task = (await backend.learning_task(scope, original['id']))['task']
        first_document = document(task, plan['id'])
        operation = uuid4().hex
        first = (await backend.create_learning_content(scope, operation, document=first_document,
            component='http-test'))['content']
        assert first == (await backend.create_learning_content(scope, operation, document=first_document,
            component='http-test'))['content']
        assert (await backend.learning_content(scope, first['id']))['content'] == first
        lesson = await backend.command(scope, uuid4().hex, 'lesson.create', {
            'kind': 'regular', 'launchMode': 'placeholder',
            'startsAt': (datetime.now(timezone.utc) + timedelta(hours=1)).isoformat(),
            'durationMinutes': 30, 'timezone': 'UTC',
        })
        issued = (await backend.issue_learning_content(scope, uuid4().hex, content_id=first['id'],
            expected_revision=1, lesson_id=lesson['id']))['content']
        assert issued['status'] == 'issued'
        clearer = copy.deepcopy(first_document)
        clearer['spokenInstruction'] = 'Say the same idea in the past tense.'
        revised = (await backend.revise_learning_content(scope, uuid4().hex, content_id=first['id'],
            expected_revision=1, document=clearer, component='http-test', reason='rephrase'))['content']
        assert revised['revision'] == 2 and revised['status'] == 'prepared'
        old = (await backend.learning_content(scope, first['id'], revision=1))['content']
        assert old['document'] == first_document and old['issuedLessonId'] == lesson['id']
        defective = (await backend.mark_learning_content_defective(scope, uuid4().hex, content_id=first['id'],
            revision=1, reason='ambiguous_condition', component='http-test'))['content']
        assert defective['status'] == 'defective' and defective['revision'] == 1
        assert defective['defectComponent'] == 'http-test' and defective['defectAt']
        assert len((await backend.learning_content(scope, first['id'], history=True))['items']) == 2
        assert (await backend.learning_task(scope, task['id']))['task'] == task


@pytest.mark.asyncio
async def test_content_scope_and_method_are_enforced_over_http():
    root, token = os.environ['SP00_TEST_URL'], os.environ['SP00_TEST_TOKEN']
    async with httpx.AsyncClient() as http:
        backend = FoundationClient(root, token, http)
        scope, original = await setup(http, backend, root, token)
        plan = (await backend.create_learning_plan(scope, uuid4().hex, task_id=original['id'],
            expected_task_revision=original['revision'], definition=definition(), component='http-test'))['plan']
        task = (await backend.learning_task(scope, original['id']))['task']
        d = document(task, plan['id'])
        wrong = copy.deepcopy(d)
        wrong['method'] = 'Conversation'
        wrong['actionKind'] = 'topic_driven'
        wrong['payload'] = {'openingPrompt': 'What did you do yesterday?', 'targetOpportunities': ['Use a past form.']}
        with pytest.raises(BackendFailure, match='content_method_mismatch'):
            await backend.create_learning_content(scope, uuid4().hex, document=wrong, component='http-test')
        content = (await backend.create_learning_content(scope, uuid4().hex, document=d,
            component='http-test'))['content']
        other = await actor(http, root, token, str(time.time_ns()))
        with pytest.raises(BackendFailure, match='not_owned'):
            await backend.learning_content(other['scope'], content['id'])
        path = root + '/internal/foundation/v1/learning-content/' + content['id']
        assert (await http.get(path)).status_code == 401
        assert (await http.get(path, headers={'X-Internal-Token': token})).status_code == 403
        headers = {'X-Internal-Token': token, 'X-Execution-Scope': scope}
        assert (await http.get(path + '?revision=0', headers=headers)).status_code == 400
