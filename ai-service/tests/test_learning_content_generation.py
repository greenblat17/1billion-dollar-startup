import copy
import json

import pytest

from app.learning_content_generation import (
    COMPONENT, ContentGenerationError, ContentRequest, LearningContentGenerator, issued_handoff,
)


class Backend:
    def __init__(self, method='Exercises', level='A2'):
        self.method, self.level = method, level
        self.writes = []
        self.task = {'id': 'task', 'catalog_version': 'published-v1', 'skill_id': 'grammar',
                     'topic_id': 'past', 'problem_id': 'grammar_application',
                     'details': {'plan': {'id': 'plan', 'revision': 2}}}

    async def learning_stage(self, scope, **kwargs):
        assert scope == 'student-scope'
        return {'schemaVersion': 1, 'task': self.task, 'stage': {'method': self.method}}

    async def catalog(self, **kwargs):
        assert kwargs == {'version': 'published-v1', 'topic_id': 'past'}
        return {'schemaVersion': 2, 'version': 'published-v1',
                'topics': [{'id': 'past', 'skillId': 'grammar', 'title': 'Past events',
                            'objective': 'Describe completed events', 'scope': 'Everyday past events'}],
                'problems': [{'id': 'grammar_application', 'skillId': 'grammar',
                              'title': 'Past form use', 'description': 'Knows the form but does not apply it'}]}

    async def learning_state(self, scope):
        return {'profile': {'currentLevels': {'speaky': {
            'overall': {'level': 'Unknown'}, 'skills': {'grammar': {'level': self.level}}}}}}

    async def create_learning_content(self, scope, operation_id, **kwargs):
        self.writes.append((scope, operation_id, kwargs))
        return {'schemaVersion': 1, 'content': {'id': 'content', 'revision': 1,
                'document': kwargs['document']}}

    async def learning_content(self, scope, content_id, **kwargs):
        return {'content': {'document': self.writes[0][2]['document']}}

    async def revise_learning_content(self, scope, operation_id, **kwargs):
        self.writes.append((scope, operation_id, kwargs))
        return {'schemaVersion': 1, 'content': {'id': 'content', 'revision': 2,
                'document': kwargs['document']}}

    async def mark_learning_content_defective(self, scope, operation_id, **kwargs):
        self.writes.append((scope, operation_id, kwargs))
        return {'schemaVersion': 1, 'content': {'id': kwargs['content_id'],
                'revision': kwargs['revision'], 'status': 'defective'}}


class Model:
    def __init__(self, draft, review=None):
        self.draft = draft
        self.review = review or {'passed': True, 'reasons': [], 'checkedAlternatives': True,
                                 'rationale': 'The alternate sentence preserves the intended past event.'}
        self.calls = []

    async def complete_json(self, system, data, **kwargs):
        self.calls.append((system, json.loads(data), kwargs))
        return json.dumps(self.draft if len(self.calls) == 1 else self.review)


def request(action='sentence_transformation', **changes):
    return ContentRequest('task', 'plan', 2, 'stage', action, 'A2', **changes)


def draft_for(method):
    payload = {
        'Explanation': {'explanation': 'Use went for a finished trip.',
                        'examples': ['I go to work every day.', 'I went to work yesterday.']},
        'Exercises': {'stimulus': 'I go to work yesterday.',
                      'targetOperation': 'Say the same event naturally in the past.'},
        'Exposure': {'segments': ['I went to work yesterday.'], 'checks': ['reconstruction', 'meaning']},
        'Conversation': {'openingPrompt': 'Tell me about something you did yesterday.',
                         'targetOpportunities': ['Describe one finished event naturally.']},
    }[method]
    return {'objective': 'Use a past form for a completed event.',
            'spokenInstruction': 'Tell me your answer aloud.', 'payload': payload,
            'answerGuide': {'criteria': ['Express the event in the past.'],
                            'alternatives': ['I went to work yesterday.', 'Yesterday, I went to my job.'],
                            'unacceptable': ['I go to work yesterday.']}}


@pytest.mark.asyncio
@pytest.mark.parametrize(('method', 'action'), [
    ('Explanation', 'comparison'), ('Exercises', 'sentence_transformation'),
    ('Exposure', 'listening_reconstruction'), ('Conversation', 'personalized'),
])
async def test_four_methods_produce_scoped_voice_handoff(method, action):
    backend, model = Backend(method), Model(draft_for(method))
    service = LearningContentGenerator(backend, model)
    prepared = await service.prepare('student-scope', request(action, learner_context='Likes commuting by bike.'))
    document = prepared.document
    assert document['method'] == method and document['actionKind'] == action
    assert document['stageRef'] == {'taskId': 'task', 'planId': 'plan', 'planRevision': 2, 'stageId': 'stage'}
    assert document['cefr'] == 'A2' and document['validation']['status'] == 'passed'
    assert document['answerGuide']['alternatives'][1] == 'Yesterday, I went to my job.'
    assert model.calls[0][1]['topic']['scope'] == 'Everyday past events'
    assert model.calls[0][1]['learnerContext'] == 'Likes commuting by bike.'
    assert model.calls[1][1]['draft'] == draft_for(method)
    stored = await service.create('student-scope', 'operation-1', prepared)
    assert stored['content']['document'] == document
    assert backend.writes[0][2]['component'] == COMPONENT


@pytest.mark.asyncio
async def test_reviewer_rejects_ambiguous_condition_and_invalid_alternative_without_write():
    backend = Backend()
    model = Model(draft_for('Exercises'), {'passed': False,
        'reasons': ['ambiguous_condition', 'invalid_alternative'], 'checkedAlternatives': True,
        'rationale': 'The instruction conflicts with the proposed answer.'})
    with pytest.raises(ContentGenerationError, match='semantic_validation_failed') as failure:
        await LearningContentGenerator(backend, model).prepare('student-scope', request())
    assert failure.value.review['reasons'] == ['ambiguous_condition', 'invalid_alternative']
    assert backend.writes == []


@pytest.mark.asyncio
async def test_cefr_stage_and_catalog_mismatch_fail_before_model_call():
    backend, model = Backend(), Model(draft_for('Exercises'))
    generator = LearningContentGenerator(backend, model)
    with pytest.raises(ContentGenerationError, match='cefr_state_mismatch'):
        await generator.prepare('student-scope', ContentRequest('task', 'plan', 2, 'stage',
                                                               'sentence_transformation', 'B2'))
    backend.task['details']['plan']['revision'] = 3
    with pytest.raises(ContentGenerationError, match='stale_or_invalid_stage'):
        await generator.prepare('student-scope', request())
    assert model.calls == []


@pytest.mark.asyncio
async def test_screen_or_external_link_rejected_even_when_model_calls_it_valid():
    backend = Backend()
    draft = draft_for('Exercises')
    draft['payload']['stimulus'] = 'Watch https://example.com/video and describe it.'
    model = Model(draft)
    with pytest.raises(ContentGenerationError, match='invalid_generated_material'):
        await LearningContentGenerator(backend, model).prepare('student-scope', request())
    assert len(model.calls) == 1 and backend.writes == []


@pytest.mark.asyncio
async def test_simplification_revises_same_identity_with_new_material():
    backend = Backend()
    generator = LearningContentGenerator(backend, Model(draft_for('Exercises')))
    original = await generator.prepare('student-scope', request())
    await generator.create('student-scope', 'operation-1', original)
    simpler_draft = copy.deepcopy(draft_for('Exercises'))
    simpler_draft['spokenInstruction'] = 'Say this sentence about yesterday.'
    generator.model = Model(simpler_draft)
    simpler = await generator.prepare('student-scope', request(reason='simplify'))
    saved = await generator.revise('student-scope', 'operation-2', 'content', 1, simpler, 'simplify')
    assert saved['content']['revision'] == 2
    assert backend.writes[1][2]['expected_revision'] == 1
    assert backend.writes[1][2]['reason'] == 'simplify'


@pytest.mark.asyncio
async def test_unknown_cefr_is_preserved_without_self_report_substitution():
    backend = Backend(method='Conversation', level='Unknown')
    model = Model(draft_for('Conversation'))
    service = LearningContentGenerator(backend, model)
    prepared = await service.prepare('student-scope', ContentRequest('task', 'plan', 2, 'stage',
                                                                     'personalized', 'Unknown'))
    assert prepared.document['cefr'] == 'Unknown'
    assert model.calls[0][1]['cefr'] == 'Unknown'


@pytest.mark.asyncio
async def test_issued_handoff_and_defect_signal_preserve_exact_revision():
    backend = Backend()
    service = LearningContentGenerator(backend, Model(draft_for('Exercises')))
    prepared = await service.prepare('student-scope', request())
    content = {'id': 'content', 'revision': 3, 'status': 'issued',
               'issuedLessonId': 'lesson', 'document': prepared.document}
    handoff = issued_handoff(content)
    assert handoff['contentRef'] == {'contentId': 'content', 'revision': 3}
    assert handoff['lessonId'] == 'lesson' and handoff['answerGuide'] == prepared.document['answerGuide']
    assert {key: handoff[key] for key in ('catalogVersion', 'skillId', 'topicId', 'problemId')} == {
        'catalogVersion': 'published-v1', 'skillId': 'grammar', 'topicId': 'past',
        'problemId': 'grammar_application'}
    assert 'score' not in handoff and 'learnerOutcome' not in handoff
    with pytest.raises(ContentGenerationError, match='content_not_issued'):
        issued_handoff({**content, 'status': 'prepared'})
    marked = await service.report_issued_defect('student-scope', 'defect-operation', 'content',
                                                3, 'ambiguous_condition')
    assert marked['content']['status'] == 'defective'
    assert backend.writes[-1][2] == {'content_id': 'content', 'revision': 3,
                                     'reason': 'ambiguous_condition', 'component': COMPONENT}
