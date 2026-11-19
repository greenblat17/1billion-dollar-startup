import pytest
import httpx
from fastapi import FastAPI

from app.foundation_client import BackendFailure
from app.foundation_api import install_foundation_api
from app.learning_engine import LearningEngine
from app.regular_teaching import RegularTeachingPreparation


def task(problem='grammar_unknown', skill='grammar', plan=None):
    return {'id': 'task-1', 'revision': 4, 'catalog_version': 'published-v1',
            'skill_id': skill, 'topic_id': 'topic-1', 'problem_id': problem,
            'lifecycle': 'Identified', 'details': {'plan': plan}}


def catalog(current):
    return {'schemaVersion': 2, 'version': 'published-v1',
            'topics': [{'id': 'topic-1', 'skillId': current['skill_id'],
                        'objective': 'Use the form', 'scope': 'One conversation'}],
            'problems': [{'id': current['problem_id'], 'skillId': current['skill_id']}]}


class Backend:
    def __init__(self, current=None, priority=True, method='Exercises'):
        self.current = current or task()
        self.priority = priority
        self.method = method
        self.calls = []

    async def learning_roadmap_priority(self, scope):
        self.calls.append('priority')
        if not self.priority:
            raise BackendFailure('roadmap_not_found', 400)
        return {'schemaVersion': 1, 'stale': False,
                'priority': {'revision': 3, 'document': {'nextTaskId': self.current['id']}}}

    async def learning_task(self, scope, task_id):
        self.calls.append('task')
        assert task_id == self.current['id']
        return {'task': self.current}

    async def catalog(self, *, version, topic_id):
        self.calls.append('catalog')
        assert version == 'published-v1' and topic_id == 'topic-1'
        return catalog(self.current)

    async def learning_stage(self, scope, *, task_id, plan_id, plan_revision, stage_id):
        self.calls.append('stage')
        return {'schemaVersion': 1, 'task': self.current,
                'stage': {'method': self.method}}

    async def learning_state(self, scope):
        self.calls.append('state')
        return {'profile': {'currentLevels': {'speaky': {'skills': {
            self.current['skill_id']: {'level': 'Unknown'}}, 'overall': {'level': 'Unknown'}}}}}


@pytest.mark.asyncio
async def test_proposes_approved_plan_without_write():
    backend = Backend()
    result = await RegularTeachingPreparation(backend, None).plan_proposal('scope')
    assert result['taskId'] == 'task-1'
    assert result['priorityRevision'] == 3
    assert result['planProposal']['definition']['order'] == ['explanation', 'exercises', 'conversation']
    assert result['planProposal']['currentStageId'] == 'explanation'
    assert backend.calls == ['priority', 'task', 'catalog']


@pytest.mark.asyncio
async def test_no_route_and_no_template_do_not_forge_plan():
    assert await RegularTeachingPreparation(Backend(priority=False), None).plan_proposal('scope') is None
    backend = Backend(task('unsupported_problem', 'grammar'))
    result = await RegularTeachingPreparation(backend, None).plan_proposal('scope')
    assert result['status'] == 'template_missing'
    assert 'planProposal' not in result


@pytest.mark.asyncio
async def test_existing_plan_uses_its_current_stage_without_replanning():
    backend = Backend(task(plan={'id': 'plan-1', 'revision': 2}))
    result = await RegularTeachingPreparation(backend, None).plan_proposal('scope')
    assert result['status'] == 'ready' and result['planProposal'] is None
    assert backend.calls == ['priority', 'task']


@pytest.mark.asyncio
async def test_content_uses_approved_action_and_pinned_stage():
    backend = Backend(task('grammar_application', plan={'id': 'plan-1', 'revision': 2}))
    teaching = RegularTeachingPreparation(backend, None)
    seen = []

    async def prepared(scope, request):
        seen.append(request)
        return type('Prepared', (), {'document': {'validation': {'status': 'passed'}}})()

    teaching.content.prepare = prepared
    result = await teaching.content_document('scope', {'taskId': 'task-1', 'planId': 'plan-1',
        'planRevision': 2, 'stageId': 'exercises'})
    assert result['document']['validation']['status'] == 'passed'
    assert seen[0].action_kind == 'sentence_transformation'
    assert seen[0].cefr == 'Unknown'
    assert (seen[0].task_id, seen[0].plan_id, seen[0].plan_revision, seen[0].stage_id) == (
        'task-1', 'plan-1', 2, 'exercises')


@pytest.mark.asyncio
async def test_missing_action_template_fails_before_generation():
    backend = Backend(task('unsupported_problem'))
    teaching = RegularTeachingPreparation(backend, None)
    with pytest.raises(ValueError, match='missing_action_template'):
        await teaching.content_document('scope', {'taskId': 'task-1', 'planId': 'plan-1',
            'planRevision': 1, 'stageId': 'exercises'})
    assert backend.calls == ['stage']


@pytest.mark.asyncio
@pytest.mark.parametrize('skill,problem,method,action', [
    ('grammar', 'grammar_unknown', 'Explanation', 'direct'),
    ('grammar', 'grammar_unknown', 'Exercises', 'sentence_construction'),
    ('grammar', 'grammar_application', 'Exercises', 'sentence_transformation'),
    ('vocabulary', 'vocabulary_passive', 'Exercises', 'meaning_recognition'),
    ('vocabulary', 'vocabulary_active', 'Exercises', 'word_recall'),
    ('listening', 'listening_comprehension', 'Exposure', 'listening_reconstruction'),
    ('speaking', 'speaking_barrier', 'Conversation', 'topic_driven'),
])
async def test_every_approved_initial_stage_has_existing_action(skill, problem, method, action):
    backend = Backend(task(problem, skill, {'id': 'plan-1', 'revision': 1}), method=method)
    teaching = RegularTeachingPreparation(backend, None)
    selected = []

    async def prepared(scope, request):
        selected.append(request.action_kind)
        return type('Prepared', (), {'document': {}})()

    teaching.content.prepare = prepared
    await teaching.content_document('scope', {'taskId': 'task-1', 'planId': 'plan-1',
        'planRevision': 1, 'stageId': method.lower()})
    assert selected == [action]


@pytest.mark.asyncio
async def test_missing_action_template_is_explicit_compute_failure():
    class MissingAction:
        async def compute(self, scope, job):
            raise ValueError('missing_action_template')

    app = FastAPI()
    install_foundation_api(app, None, MissingAction())
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://test') as client:
        response = await client.post('/internal/foundation/compute',
            headers={'X-Execution-Scope': 'job-token'}, json={'kind': 'lesson.content.prepare'})
    assert response.status_code == 422
    assert response.json() == {'detail': 'missing_action_template'}


@pytest.mark.asyncio
async def test_shipping_engine_dispatches_plan_then_content_jobs():
    class Contexts:
        async def build(self, scope, purpose, lesson_id):
            assert (scope, purpose, lesson_id) == ('scope', 'learning', 'lesson-1')
            return type('Context', (), {'data': {'lesson': {'kind': 'regular'}}})()

    class Planner:
        async def prepare(self, context, inputs):
            return {'objective': 'Speak', 'topic': 'Work', 'strategy': 'Conversation'}

    class Teaching:
        async def plan_proposal(self, scope):
            return {'taskId': 'task-1', 'status': 'template_missing'}

        async def content_document(self, scope, inputs):
            assert inputs == {'taskId': 'task-1'}
            return {'document': {'validation': {'status': 'passed'}}}

    engine = LearningEngine(None, Contexts(), planner=Planner(), regular_teaching=Teaching())
    prepared = await engine.compute('scope', {'schema_version': 1, 'kind': 'lesson.prepare',
        'target_id': 'lesson-1', 'input_refs': {}})
    assert prepared['teachingPlan'] == {'taskId': 'task-1', 'status': 'template_missing'}
    content = await engine.compute('scope', {'schema_version': 1, 'kind': 'lesson.content.prepare',
        'target_id': 'lesson-1', 'input_refs': {'taskId': 'task-1'}})
    assert content['document']['validation']['status'] == 'passed'
