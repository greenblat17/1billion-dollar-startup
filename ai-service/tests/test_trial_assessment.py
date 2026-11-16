import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.trial_assessment import TrialAssessment


def observation(id, skill, outcome='independent_correct', topic='topic-a', attempt=None, usable=True):
    return {'id': id, 'revision': 1, 'status': 'active', 'evidenceUsable': usable,
            'document': {'catalogVersion': 'v1', 'practice': 'trial', 'route': 'assessment_only', 'skillId': skill,
                         'topicId': topic, 'problemId': None, 'outcome': outcome,
                         'support': 'independent', 'attemptId': attempt or id,
                         'quality': {'status': 'usable' if usable else 'uncertain'},
                         'rationale': 'Observed in independent answer',
                         'provenance': {'component': 'trial-observation-extractor'}}}


def backend(rows):
    return SimpleNamespace(
        trial_events=AsyncMock(return_value={'schemaVersion': 1, 'lesson': {
            'trial_version': 'v5.5', 'status': 'completed', 'transcript_revision': 7},
            'observationStatus': 'succeeded'}),
        learning_state=AsyncMock(return_value={'schemaVersion': 1, 'revision': 3,
            'profile': {'currentLevels': {'selfAssessment': {'overall': {'level': 'B1'},
                'skills': {'listening': {'level': 'B2'}}},
                'speaky': {'overall': {'level': 'Unknown'}}}}}),
        catalog=AsyncMock(return_value={'schemaVersion': 2, 'version': 'v1',
            'topics': [{'id': 'topic-a', 'skillId': 'grammar', 'cefr': 'A2', 'title': 'Topic A'}]}),
        topic_knowledge=AsyncMock(return_value={'schemaVersion': 1, 'items': [], 'nextOffset': None}),
        learning_observations=AsyncMock(return_value={'schemaVersion': 1, 'items': rows, 'nextOffset': None}))


def level(skill, value='Unknown', ids=None):
    return {'skillId': skill, 'level': value, 'observationIds': ids or [],
            'explanation': 'Preliminary assessment from saved Trial evidence.'}


@pytest.mark.asyncio
async def test_partial_trial_keeps_listening_unknown_and_self_report_separate():
    rows = [observation('g1', 'grammar'), observation('g2', 'grammar'),
            observation('s1', 'speaking', topic=None), observation('s2', 'speaking', topic=None)]
    b = backend(rows)
    model = SimpleNamespace(complete_json=AsyncMock(return_value=json.dumps({'levels': [
        level('overall', 'A2', ['g1', 'g2', 's1', 's2']), level('grammar', 'A2', ['g1', 'g2']),
        level('speaking', 'A2', ['s1', 's2']), level('vocabulary')]})))
    result = await TrialAssessment(b, model).assess('scope', 'lesson', {'transcriptRevision': 7, 'catalogVersion': 'v1'})
    assert result['levels']['grammar']['level'] == 'A2'
    assert result['levels']['speaking']['level'] == 'A2'
    assert result['levels']['vocabulary']['level'] == 'Unknown'
    assert result['levels']['listening']['level'] == 'Unknown'
    assert result['topics'][0]['status'] == 'Known'
    assert result['coverage']['usableWithoutTopic'] == 2
    assert result['baseLearningRevision'] == 3
    assert b.learning_state.return_value['profile']['currentLevels']['selfAssessment']['skills']['listening']['level'] == 'B2'
    assert 'Self-report is context only' in model.complete_json.call_args.args[0]


@pytest.mark.asyncio
async def test_short_or_uncertain_trial_has_no_invented_level_or_topic():
    rows = [observation('u1', 'grammar', usable=False)]
    b = backend(rows)
    model = SimpleNamespace(complete_json=AsyncMock())
    result = await TrialAssessment(b, model).assess('scope', 'lesson', {'transcriptRevision': 7, 'catalogVersion': 'v1'})
    assert all(value['level'] == 'Unknown' for value in result['levels'].values())
    assert result['topics'] == []
    model.complete_json.assert_not_awaited()


@pytest.mark.asyncio
async def test_one_error_is_hypothesis_two_independent_errors_are_confirmed():
    b = backend([observation('g1', 'grammar', 'uncorrected_error')])
    model = SimpleNamespace(complete_json=AsyncMock())
    one = await TrialAssessment(b, model).assess('scope', 'lesson', {'transcriptRevision': 7, 'catalogVersion': 'v1'})
    assert one['topics'][0]['status'] == 'Presumed Not Known'
    b.learning_observations.return_value['items'].append(observation('g2', 'grammar', 'uncorrected_error'))
    model.complete_json.return_value = json.dumps({'levels': [level('overall'), level('speaking'),
                                                      level('grammar'), level('vocabulary')]})
    two = await TrialAssessment(b, model).assess('scope', 'lesson', {'transcriptRevision': 7, 'catalogVersion': 'v1'})
    assert two['topics'][0]['status'] == 'Not Known'
    b.learning_observations.return_value['items'].append(observation('g3', 'grammar', 'independent_correct'))
    mixed = await TrialAssessment(b, model).assess('scope', 'lesson', {'transcriptRevision': 7, 'catalogVersion': 'v1'})
    assert mixed['topics'][0]['status'] == 'Unknown'


@pytest.mark.asyncio
async def test_unsupported_model_level_is_rejected():
    b = backend([observation('g1', 'grammar'), observation('g2', 'grammar')])
    model = SimpleNamespace(complete_json=AsyncMock(return_value=json.dumps({'levels': [
        level('overall'), level('speaking'), level('grammar', 'B1', ['g1']), level('vocabulary')]})))
    with pytest.raises(ValueError, match='unsupported_skill_level'):
        await TrialAssessment(b, model).assess('scope', 'lesson', {'transcriptRevision': 7, 'catalogVersion': 'v1'})


@pytest.mark.asyncio
async def test_invalidated_evidence_returns_previous_trial_topic_to_unknown():
    b = backend([])
    b.topic_knowledge.return_value['items'] = [{
        'topic_id': 'topic-a', 'status': 'Known', 'component': 'post-trial-assessment',
        'sources': [{'lessonId': 'lesson'}]}]
    model = SimpleNamespace(complete_json=AsyncMock())
    result = await TrialAssessment(b, model).assess('scope', 'lesson', {'transcriptRevision': 7, 'catalogVersion': 'v1'})
    assert result['topics'] == [{'topicId': 'topic-a', 'status': 'Unknown',
        'explanation': 'Earlier Trial evidence is no longer active; knowledge is unknown.',
        'observationRefs': []}]
    model.complete_json.assert_not_awaited()
