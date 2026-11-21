"""SP-13A Python → Ktor → PostgreSQL contract; synthetic material only."""
import copy
import json
import os
import time
from datetime import datetime, timedelta, timezone
from unittest.mock import AsyncMock
from uuid import uuid4

import httpx
import pytest

from app.foundation_client import BackendFailure, FoundationClient
from app.learning_content_generation import COMPONENT, ContentGenerationError, ContentRequest, LearningContentGenerator
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
@pytest.mark.parametrize('limit_case', ['ascii', 'emoji', 'compact_json'])
async def test_generator_and_ktor_agree_at_content_limits(limit_case):
    root, token = os.environ['SP00_TEST_URL'], os.environ['SP00_TEST_TOKEN']
    async with httpx.AsyncClient() as http:
        backend = FoundationClient(root, token, http)
        scope, task = await setup(http, backend, root, token)
        plan = (await backend.create_learning_plan(scope, uuid4().hex, task_id=task['id'],
            expected_task_revision=task['revision'], definition=definition(), component='http-test'))['plan']
        request = ContentRequest(task['id'], plan['id'], 1, 'exercise', 'sentence_transformation', 'Unknown')
        fixture = document(task, plan['id'])
        if limit_case == 'compact_json':
            fixture['cefr'] = 'Unknown'
            fixture['validation']['component'] = COMPONENT
            fixture['answerGuide'] = {field: [str(index) + '"' * 299 for index in range(8)]
                                      for field in fixture['answerGuide']}
            fixture['objective'] = ''
            padding = 16_000 - len(json.dumps(fixture, ensure_ascii=False, separators=(',', ':')))
            fixture['objective'] = '🌐' * (padding // 2) + 'a' * (padding % 2)
            assert len(fixture['objective']) < 500  # The document reaches its limit before the field.
            generation_error, backend_error = 'content_too_large', 'content_too_large'
        else:
            fixture['objective'] = 'a' * 1000 if limit_case == 'ascii' else '🌐' * 500
            generation_error, backend_error = 'invalid_generated_material', 'invalid_content_text'
        draft = {field: fixture[field] for field in ('objective', 'spokenInstruction', 'payload', 'answerGuide')}
        review = {'passed': True, 'reasons': [], 'checkedAlternatives': True, 'rationale': 'Fixture accepted.'}
        model = AsyncMock()
        model.complete_json.side_effect = [json.dumps(draft), json.dumps(review)]
        generator = LearningContentGenerator(backend, model)
        prepared = await generator.prepare(scope, request)
        created = (await generator.create(scope, uuid4().hex, prepared))['content']
        assert created['document']['objective'] == draft['objective']

        over_limit = draft['objective'] + 'a'
        model.complete_json.side_effect = [json.dumps({**draft, 'objective': over_limit})] * 2
        with pytest.raises(ContentGenerationError, match=generation_error):
            await generator.prepare(scope, request)
        operation = uuid4().hex
        with pytest.raises(BackendFailure, match=backend_error):
            await backend.create_learning_content(scope, operation, component='http-test',
                document={**prepared.document, 'objective': over_limit})
        assert await backend.receipt(scope, operation) is None
        assert (await backend.learning_content(scope, created['id'], revision=1))['content'] == created


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
        for invalid, code in [
            ({**d, 'actionKind': 'direct'}, 'invalid_content_choice'),
            ({key: value for key, value in d.items() if key != 'actionKind'}, 'invalid_content_text'),
        ]:
            operation = uuid4().hex
            with pytest.raises(BackendFailure, match=code):
                await backend.create_learning_content(scope, operation, document=invalid, component='http-test')
            assert await backend.receipt(scope, operation) is None
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
        for query, code in [('?revision=0', 'invalid_content_revision'), ('?revision=x', 'invalid_content_revision'),
                            ('?revision=1&revision=2', 'invalid_content_query')]:
            response = await http.get(path + query, headers=headers)
            assert (response.status_code, response.json()) == (400, {'ok': False, 'error': code})
