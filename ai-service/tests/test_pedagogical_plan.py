import json

import httpx
import pytest

from app.foundation_client import FoundationClient
from app.pedagogical_plan import (PedagogicalPlanner, PlanInputError, advise_stage,
                                  propose_plan, stage_reference)


def fixture_task(skill='grammar', problem='grammar_unknown'):
    return {'id': 'task-1', 'revision': 4, 'catalog_version': 'published-v1', 'skill_id': skill,
            'topic_id': 'topic-1', 'problem_id': problem, 'progress': 0, 'details': {'plan': None}}


def fixture_catalog(task):
    return {'schemaVersion': 2, 'version': 'published-v1',
            'topics': [{'id': 'topic-1', 'skillId': task['skill_id'],
                        'objective': 'Use this ability', 'scope': 'One level of practice'}],
            'problems': [{'id': task['problem_id'], 'skillId': task['skill_id']}]}


@pytest.mark.parametrize('skill,problem,expected', [
    ('grammar', 'grammar_unknown', [('Explanation', None), ('Exercises', 0.3), ('Conversation', 0.7)]),
    ('grammar', 'grammar_application', [('Exercises', 0.3), ('Conversation', 0.7)]),
    ('speaking', 'speaking_barrier', [('Conversation', 1.0)]),
    ('listening', 'listening_comprehension', [('Exposure', 1.0)]),
    ('vocabulary', 'vocabulary_passive', [('Exercises', 1.0)]),
    ('vocabulary', 'vocabulary_active', [('Exercises', 0.3), ('Conversation', 0.7)]),
])
def test_six_approved_templates_have_exact_methods_and_weights(skill, problem, expected):
    task = fixture_task(skill, problem)
    proposal = propose_plan(task, fixture_catalog(task))
    stages = proposal.definition['stages']
    assert [(stage['method'], stage.get('weight')) for stage in stages] == expected
    assert proposal.first_stage_id == stages[0]['id']
    assert sum(stage.get('weight', 0) for stage in stages) == pytest.approx(1.0)
    assert all(stage['required'] for stage in stages)
    assert proposal.definition['order'] == [stage['id'] for stage in stages]
    for first, second in zip(proposal.definition['order'], proposal.definition['order'][1:]):
        assert {'from': first, 'to': second} in proposal.definition['transitions']
        assert {'from': second, 'to': first} in proposal.definition['transitions']
    if problem == 'grammar_unknown':
        assert stages[0]['condition'] == 'understanding_demonstrated'
        assert stages[0]['kind'] == 'binary'


def test_missing_template_and_invalid_catalog_fail_without_fallback():
    task = fixture_task('reading', 'reading_comprehension')
    assert propose_plan(task, fixture_catalog(task)) is None
    catalog = fixture_catalog(task)
    catalog['problems'][0]['skillId'] = 'writing'
    with pytest.raises(PlanInputError, match='invalid_task_catalog_links'):
        propose_plan(task, catalog)
    catalog = fixture_catalog(task)
    catalog['version'] = 'unpublished-other-version'
    with pytest.raises(PlanInputError, match='catalog_version_mismatch'):
        propose_plan(task, catalog)


def test_stage_advice_preserves_cursor_on_refusal_and_does_not_skip():
    definition = propose_plan(fixture_task(), fixture_catalog(fixture_task())).definition
    assert advise_stage(definition, 'explanation', learner_refused=True).action == 'pause_task'
    assert advise_stage(definition, 'explanation').action == 'continue'
    assert advise_stage(definition, 'explanation', understanding_demonstrated=True).next_stage_id == 'exercises'
    assert advise_stage(definition, 'exercises', score=70).action == 'continue'
    assert advise_stage(definition, 'exercises', score=15).action == 'reconsider'
    assert advise_stage(definition, 'exercises', score=85).next_stage_id == 'conversation'
    assert advise_stage(definition, 'conversation', score=85).action == 'stage_complete'
    with pytest.raises(PlanInputError, match='binary_stage_has_no_score'):
        advise_stage(definition, 'explanation', score=85)
    for invalid in (float('nan'), float('inf'), True, -1, 101):
        with pytest.raises(PlanInputError, match='numeric_stage_score_required'):
            advise_stage(definition, 'exercises', score=invalid)


@pytest.mark.asyncio
async def test_planner_uses_real_task_and_published_catalog_adapter_to_create_plan():
    task = fixture_task()
    requests = []

    def handle(request):
        requests.append(request)
        if request.url.path.endswith('/learning-tasks/task-1'):
            result = {'schemaVersion': 1, 'task': task}
        elif request.url.path.endswith('/catalog'):
            assert dict(request.url.params) == {'version': 'published-v1', 'topicId': 'topic-1'}
            result = fixture_catalog(task)
        else:
            payload = json.loads(request.content)
            assert payload['operationId'] == 'operation-1'
            assert payload['action'] == 'learning.plan.create'
            assert payload['args']['expectedTaskRevision'] == 4
            assert payload['args']['currentStageId'] == 'explanation'
            result = {'schemaVersion': 1, 'plan': {'id': 'plan-1', 'taskId': 'task-1', 'revision': 1,
                      'definition': payload['args']['definition']}}
        return httpx.Response(200, json={'ok': True, 'result': result})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as http:
        planner = PedagogicalPlanner(FoundationClient('http://backend', 'service', http))
        result = await planner.ensure_plan('operator-scope', 'task-1', 'operation-1')
    assert result['status'] == 'created'
    assert result['templateId'] == 'sp12b.v1.grammar_unknown'
    assert stage_reference(result['plan'], 'exercises') == {
        'taskId': 'task-1', 'planId': 'plan-1', 'planRevision': 1, 'stageId': 'exercises'}
    assert len(requests) == 3
    assert requests[0].headers['X-Execution-Scope'] == 'operator-scope'


@pytest.mark.asyncio
async def test_missing_template_does_not_issue_plan_command():
    task = fixture_task('reading', 'reading_comprehension')
    requests = []

    def handle(request):
        requests.append(request)
        if request.url.path.endswith('/learning-tasks/task-1'):
            result = {'schemaVersion': 1, 'task': task}
        else:
            result = fixture_catalog(task)
        return httpx.Response(200, json={'ok': True, 'result': result})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as http:
        planner = PedagogicalPlanner(FoundationClient('http://backend', 'service', http))
        result = await planner.ensure_plan('operator-scope', 'task-1', 'operation-1')
    assert result == {'status': 'template_missing', 'taskId': 'task-1', 'problemId': 'reading_comprehension'}
    assert all(request.method == 'GET' for request in requests)


@pytest.mark.asyncio
async def test_existing_task_plan_is_returned_without_new_catalog_or_write():
    task = fixture_task()
    task['details']['plan'] = {'id': 'plan-1', 'revision': 3}
    paths = []

    def handle(request):
        paths.append(request.url.path)
        if request.url.path.endswith('/learning-tasks/task-1'):
            result = {'schemaVersion': 1, 'task': task}
        else:
            result = {'schemaVersion': 1, 'plan': {'id': 'plan-1', 'taskId': 'task-1', 'revision': 3}}
        return httpx.Response(200, json={'ok': True, 'result': result})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as http:
        planner = PedagogicalPlanner(FoundationClient('http://backend', 'service', http))
        result = await planner.ensure_plan('operator-scope', 'task-1', 'unused-operation')
    assert result['status'] == 'existing'
    assert result['plan']['revision'] == 3
    assert paths == ['/internal/foundation/v1/learning-tasks/task-1',
                     '/internal/foundation/v1/learning-plans/plan-1']


@pytest.mark.asyncio
async def test_stage_result_moves_only_on_verified_threshold_and_keeps_snapshot_for_retry():
    task = fixture_task()
    definition = propose_plan(task, fixture_catalog(task)).definition
    plan = {'id': 'plan-1', 'taskId': 'task-1', 'revision': 1, 'definition': definition,
            'currentState': {'planRevision': 1, 'stateRevision': 2, 'currentStageId': 'exercises'}}
    commands = []

    def handle(request):
        payload = json.loads(request.content)
        commands.append(payload)
        assert payload['action'] == 'learning.plan.select-stage'
        assert payload['args']['expectedPlanRevision'] == 1
        assert payload['args']['expectedStateRevision'] == 2
        assert payload['args']['reason'] == 'plan_transition'
        changed = {**plan, 'currentState': {**plan['currentState'], 'stateRevision': 3,
                                          'currentStageId': payload['args']['currentStageId']}}
        return httpx.Response(200, json={'ok': True, 'result': {'schemaVersion': 1, 'plan': changed}})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as http:
        planner = PedagogicalPlanner(FoundationClient('http://backend', 'service', http))
        assert (await planner.apply_stage_result('scope', 'refusal', plan, learner_refused=True))['advice'].action == 'pause_task'
        assert (await planner.apply_stage_result('scope', 'below', plan, score=60))['advice'].action == 'continue'
        assert (await planner.apply_stage_result('scope', 'low', plan, score=10))['advice'].action == 'reconsider'
        assert commands == []
        advanced = await planner.apply_stage_result('scope', 'high', plan, score=85)
        assert advanced['plan']['currentState']['currentStageId'] == 'conversation'
        assert commands[-1]['operationId'] == 'high'
        assert commands[-1]['args']['currentStageId'] == 'conversation'
        await planner.apply_stage_result('scope', 'high', plan, score=85)
        assert commands[-1] == commands[-2]
        returned = await planner.apply_stage_result('scope', 'return', plan, score=10, return_to_previous=True)
        assert returned['plan']['currentState']['currentStageId'] == 'explanation'
        with pytest.raises(PlanInputError, match='return_not_indicated'):
            await planner.apply_stage_result('scope', 'invalid', plan, score=85, return_to_previous=True)
    assert len(commands) == 3
