"""SP-08 acceptance fixtures; these are not live pedagogical acceptance."""
import json
from pathlib import Path

import pytest

from app.personalized_roadmap import PersonalizedRoadmap, build_roadmap


CATALOG = json.loads((Path(__file__).parents[2] / 'learning-catalog/v1/catalog.json').read_text())


def state(level='A2', target='B2', goal='Use English at work'):
    skills = {skill: {'level': level} for skill in ('speaking', 'listening', 'grammar', 'vocabulary')}
    return {'schemaVersion': 1, 'revision': 4,
            'profile': {'targetLevel': target,
                        'currentLevels': {'speaky': {'skills': skills}, 'selfAssessment': {'skills': skills}}},
            'goal': {'revision': 2, 'content': {'value': goal}}}


def test_a2_to_b2_covers_all_four_skills_and_preserves_goal_context():
    result = build_roadmap(state(), CATALOG, [], [])
    sequence = result['sequence']
    assert {row['skillId'] for row in sequence} == {'speaking', 'listening', 'grammar', 'vocabulary'}
    assert {row['cefr'] for row in sequence} == {'A2', 'B1', 'B2'}
    assert all(row['provisional'] and row['knowledge'] == 'Unknown' for row in sequence)
    assert all(row['taskId'] is None and row['orderReason'] for row in sequence)
    assert [row['order'] for row in sequence] == list(range(1, len(sequence) + 1))
    assert any(row['goalFocus'] and row['topicId'] == 'vocabulary-b2-professional' for row in sequence)
    assert any(not row['goalFocus'] and row['skillId'] == 'grammar' and row['cefr'] == 'B2' for row in sequence)
    assert result['timeline'] is None


def test_skill_levels_are_independent_and_earlier_recorded_gap_is_retained():
    student = state()
    student['profile']['currentLevels']['speaky']['skills']['grammar']['level'] = 'B1'
    knowledge = [{'topic_id': 'grammar-a2-past-simple', 'status': 'Not Known'}]
    result = build_roadmap(student, CATALOG, knowledge, [])
    grammar = [row for row in result['sequence'] if row['skillId'] == 'grammar']
    speaking = [row for row in result['sequence'] if row['skillId'] == 'speaking']
    assert all(row['cefr'] != 'A2' for row in grammar if row['topicId'] != 'grammar-a2-past-simple')
    assert any(row['cefr'] == 'A2' for row in speaking)
    assert any(row['topicId'] == 'grammar-a2-past-simple' and row['knowledge'] == 'Not Known'
               and row['provisional'] for row in grammar)


def test_work_meeting_goal_from_trial_focuses_eligible_topics():
    goal = 'Выйти на B2, чтобы уверенно объяснять проблемы и обсуждать сроки на английском на рабочих встречах'
    student = state(level='Unknown', goal=goal)
    student['profile']['currentLevels']['speaky']['skills'].update(
        speaking={'level': 'B1'}, grammar={'level': 'A2'},
    )
    knowledge = [{'topic_id': 'speaking-b1-problem-solving', 'status': 'Known'}]

    sequence = build_roadmap(student, CATALOG, knowledge, [])['sequence']

    focused = {row['topicId'] for row in sequence if row['goalFocus']}
    assert {'vocabulary-a2-work', 'vocabulary-b1-work-communication', 'speaking-b1-interview',
            'vocabulary-b2-professional', 'speaking-b2-negotiation'} <= focused
    assert all(row['skillId'] != 'grammar' for row in sequence if row['goalFocus'])
    assert 'speaking-a2-work-basics' not in {row['topicId'] for row in sequence}


def test_known_is_excluded_unknown_remains_hypothesis_and_reopened_survives():
    known = 'speaking-a2-work-basics'
    unknown = 'speaking-a2-social'
    tasks = [{'id': 'reopened-1', 'topic_id': known, 'problem_id': 'speaking_barrier',
              'skill_id': 'speaking', 'catalog_version': CATALOG['version'], 'goal_revision': 2,
              'lifecycle': 'Reopened', 'revision': 3, 'progress': 70}]
    knowledge = [{'topic_id': known, 'status': 'Known'}, {'topic_id': unknown, 'status': 'Unknown'}]
    result = build_roadmap(state(), CATALOG, knowledge, tasks)
    assert next(row for row in result['sequence'] if row['topicId'] == known)['taskId'] == 'reopened-1'
    assert next(row for row in result['sequence'] if row['topicId'] == unknown)['provisional']
    assert next(row for row in result['sequence'] if row['topicId'] == unknown)['knowledge'] == 'Unknown'
    without_reopen = build_roadmap(state(), CATALOG, knowledge, [])
    assert known not in {row['topicId'] for row in without_reopen['sequence']}


def test_unknown_target_cannot_claim_cefr_roadmap():
    student = state(target='Unknown')
    with pytest.raises(ValueError, match='target_level_required'):
        build_roadmap(student, CATALOG, [], [])


def test_mastered_work_stays_in_history_without_duplicate_assignment():
    topic = 'vocabulary-a2-work'
    mastered = {'id': 'old-task', 'topic_id': topic, 'problem_id': 'vocabulary_active',
                'skill_id': 'vocabulary', 'catalog_version': CATALOG['version'],
                'goal_revision': 2, 'lifecycle': 'Mastered', 'revision': 4, 'progress': 100}
    result = build_roadmap(state(), CATALOG, [], [mastered])
    assert topic not in {row['topicId'] for row in result['sequence']}
    assert {'taskId': 'old-task', 'lifecycle': 'Mastered'} in result['history']


class Backend:
    def __init__(self):
        self.tasks = []
        self.requested_version = None

    async def learning_state(self, scope):
        return state()

    async def catalog(self, *, version=None):
        self.requested_version = version
        return CATALOG

    async def topic_knowledge(self, scope, version, *, offset):
        return {'items': [], 'nextOffset': None}

    async def learning_tasks(self, scope, *, offset):
        return {'items': self.tasks, 'nextOffset': None}

@pytest.mark.asyncio
async def test_job_planning_is_read_only_and_uses_trial_catalog_version():
    backend = Backend()
    proposal = await PersonalizedRoadmap(backend).plan('job-scope', catalog_version=CATALOG['version'])
    assert backend.requested_version == CATALOG['version']
    assert proposal['taskCount'] > 0
    assert all(row['taskId'] is None for row in proposal['sequence'])
    assert backend.tasks == []
