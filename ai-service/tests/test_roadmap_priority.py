"""SP-09 decision fixtures. They do not prove live pedagogical quality."""
import pytest

from app.roadmap_priority import RoadmapPriorityService, prioritize


def task(id, skill='grammar', lifecycle='Identified', progress=0):
    return {'id': id, 'topic_id': 'topic-' + id, 'problem_id': 'problem-' + id,
            'skill_id': skill, 'lifecycle': lifecycle, 'progress': progress, 'revision': 1}


def entry(t, order, level='A2', knowledge='Unknown', provisional=True, goal=False):
    return {'taskId': t['id'], 'topicId': t['topic_id'], 'problemId': t['problem_id'],
            'skillId': t['skill_id'], 'cefr': level, 'knowledge': knowledge,
            'provisional': provisional, 'goalFocus': goal, 'order': order}


def roadmap(*rows):
    return {'revision': 3, 'catalog_version': 'test-v1',
            'document': {'scope': 'initial_roadmap', 'sequence': list(rows)}}


def test_next_task_explains_hypotheses_and_personal_prerequisites():
    foundation = task('foundation')
    advanced = task('advanced')
    proposal = roadmap(entry(advanced, 1, level='B1', knowledge='Not Known', provisional=True),
                       entry(foundation, 2, knowledge='Not Known', provisional=False))
    result = prioritize(proposal, [advanced, foundation], trigger='initial',
                        prerequisite_task_ids={'advanced': ['foundation']})
    assert result['nextTaskId'] == 'foundation'
    assert [row['taskId'] for row in result['orderedTasks']] == ['foundation', 'advanced']
    assert result['orderedTasks'][0]['evidence'] == 'confirmed'
    assert result['orderedTasks'][1]['evidence'] == 'topic_confirmed_problem_hypothesis'
    assert result['orderedTasks'][1]['prerequisiteTaskIds'] == ['foundation']


def test_learner_defers_topic_without_changing_knowledge_or_progress():
    grammar = task('grammar', progress=45)
    speaking = task('speaking', skill='speaking', progress=12)
    initial = roadmap(entry(grammar, 1, knowledge='Known'), entry(speaking, 2))
    result = prioritize(initial, [grammar, speaking], trigger='learner_request',
                        deferred_task_ids=['grammar'])
    assert result['nextTaskId'] == 'speaking'
    assert grammar['progress'] == 45 and grammar['lifecycle'] == 'Identified'
    assert initial['document']['sequence'][0]['knowledge'] == 'Known'
    assert result['orderedTasks'][-1]['taskId'] == 'grammar'


def test_mastered_stays_in_history_and_direct_request_bypasses_threshold():
    done = task('done', lifecycle='Mastered', progress=100)
    other = task('other')
    direct = task('direct', skill='speaking')
    initial = roadmap(entry(done, 1), entry(other, 2))
    added = entry(direct, 3)
    result = prioritize(initial, [done, other, direct], trigger='learner_request',
                        requested_task_id='direct', accepted_tasks=[added])
    assert result['nextTaskId'] == 'direct'
    assert 'done' not in {row['taskId'] for row in result['orderedTasks']}
    assert done['progress'] == 100
    assert result['explanation'].startswith('The learner requested')


def test_declined_problem_is_not_selected_and_one_answer_cannot_reorder():
    declined = task('declined')
    alternate = task('alternate')
    initial = roadmap(entry(declined, 1), entry(alternate, 2))
    result = prioritize(initial, [declined, alternate], trigger='post_lesson_review',
                        declined_problems=[{'topicId': declined['topic_id'],
                                            'problemId': declined['problem_id']}])
    assert result['nextTaskId'] == 'alternate'
    with pytest.raises(ValueError, match='prior_decline_requires_acceptance'):
        prioritize(initial, [declined, alternate], trigger='learner_request', requested_task_id='declined',
                   declined_problems=[{'topicId': declined['topic_id'], 'problemId': declined['problem_id']}])
    with pytest.raises(ValueError, match='roadmap_review_required'):
        prioritize(initial, [declined, alternate], trigger='single_answer')


def test_new_request_clears_previous_deferral():
    requested, other = task('requested'), task('other')
    result = prioritize(roadmap(entry(other, 1), entry(requested, 2)), [other, requested],
                        trigger='learner_request', requested_task_id='requested',
                        deferred_task_ids=['requested'])
    assert result['nextTaskId'] == 'requested'
    assert result['deferredTaskIds'] == []


def test_latest_topic_knowledge_replaces_old_roadmap_snapshot():
    old = task('old')
    other = task('other')
    result = prioritize(roadmap(entry(old, 1), entry(other, 2)), [old, other], trigger='post_lesson_review',
                        topic_knowledge={old['topic_id']: 'Known'})
    assert result['nextTaskId'] == 'other'
    assert next(item for item in result['orderedTasks'] if item['taskId'] == 'old')['evidence'] == 'known'


def test_no_available_task_requests_review_and_cycle_is_rejected():
    only = task('only', lifecycle='Mastered', progress=100)
    result = prioritize(roadmap(entry(only, 1)), [only], trigger='task_mastered')
    assert result['needsRoadmapReview'] and result['nextTaskId'] is None
    a, b = task('a'), task('b')
    with pytest.raises(ValueError, match='personal_prerequisite_cycle'):
        prioritize(roadmap(entry(a, 1), entry(b, 2)), [a, b], trigger='initial',
                   prerequisite_task_ids={'a': ['b'], 'b': ['a']})


class Backend:
    def __init__(self):
        self.a, self.b = task('a'), task('b')
        self.calls = []

    async def learning_roadmap(self, scope):
        return {'roadmap': roadmap(entry(self.a, 1), entry(self.b, 2))}

    async def learning_roadmap_priority(self, scope):
        return {'priority': None, 'problemDecisions': []}

    async def learning_tasks(self, scope, *, offset):
        return ({'items': [self.a], 'nextOffset': 1} if offset == 0
                else {'items': [self.b], 'nextOffset': None})

    async def topic_knowledge(self, scope, version, *, offset):
        return {'items': [], 'nextOffset': None}

    async def command(self, scope, operation_id, action, args):
        self.calls.append((scope, operation_id, action, args))
        return args


@pytest.mark.asyncio
async def test_review_handoff_uses_current_roadmap_and_optimistic_revision():
    backend = Backend()
    result = await RoadmapPriorityService(backend).review('scope', 'op-1', trigger='initial')
    assert backend.calls[0][2] == 'learning.roadmap.priority.save'
    assert result['expectedRevision'] == 0
    assert result['decision']['roadmapRevision'] == 3
    assert result['decision']['nextTaskId'] == 'a'
    assert {entry['taskId'] for entry in result['decision']['orderedTasks']} == {'a', 'b'}


@pytest.mark.asyncio
async def test_review_rejects_over_limit_task_page_with_more_results():
    backend = Backend()

    async def too_many_tasks(scope, *, offset):
        return {'items': [backend.a] * 1001, 'nextOffset': 1001}

    backend.learning_tasks = too_many_tasks
    with pytest.raises(ValueError, match='learning_task_limit'):
        await RoadmapPriorityService(backend).review('scope', 'op-1', trigger='initial')
    assert backend.calls == []


@pytest.mark.asyncio
async def test_post_lesson_review_drops_mastered_task_from_saved_deferrals():
    backend = Backend()
    backend.a['lifecycle'] = 'Mastered'

    async def previous(scope):
        return {'priority': {'revision': 2, 'document': {'deferredTaskIds': ['a']}},
                'problemDecisions': []}

    backend.learning_roadmap_priority = previous
    result = await RoadmapPriorityService(backend).plan('scope', trigger='post_lesson_review')
    assert result['expectedRevision'] == 2
    assert result['decision']['nextTaskId'] == 'b'
    assert result['decision']['deferredTaskIds'] == []


