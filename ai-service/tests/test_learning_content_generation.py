import copy
import json
from unittest.mock import AsyncMock

import httpx
import pytest
from openai import APIStatusError

from app.diagnostics import diagnostic_context
from app.learning_content_generation import (
    COMPONENT, REASONS, ContentGenerationError, ContentRequest, LearningContentGenerator, issued_handoff,
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
    example_instruction = ('In payload.examples, provide 1 to 4 unique short examples, each at most 300 characters. '
                           'Aim for exactly two examples.')
    assert (example_instruction in model.calls[0][0]) == (method == 'Explanation')
    response_format = model.calls[0][2]['response_format']
    assert response_format['type'] == 'json_schema' and response_format['json_schema']['strict'] is True
    schema = response_format['json_schema']['schema']
    properties = schema['properties']
    for object_schema, value in ((schema, draft_for(method)),
                                 (properties['payload'], document['payload']),
                                 (properties['answerGuide'], document['answerGuide'])):
        assert set(object_schema['required']) == set(object_schema['properties']) == set(value)
        assert object_schema['additionalProperties'] is False
    review_format = model.calls[1][2]['response_format']
    assert review_format['type'] == 'json_schema' and review_format['json_schema']['strict'] is True
    review_schema = review_format['json_schema']['schema']
    assert review_schema['additionalProperties'] is False
    assert set(review_schema['required']) == set(model.review)
    assert review_schema['properties'] == {
        'passed': {'type': 'boolean'},
        'reasons': {'type': 'array', 'items': {'type': 'string', 'enum': sorted(REASONS)}},
        'checkedAlternatives': {'type': 'boolean'},
        'rationale': {'type': 'string'},
    }
    assert model.calls[1][1]['draft'] == draft_for(method)
    stored = await service.create('student-scope', 'operation-1', prepared)
    assert stored['content']['document'] == document
    assert backend.writes[0][2]['component'] == COMPONENT


@pytest.mark.asyncio
async def test_malformed_structured_draft_does_not_reach_review_or_storage():
    backend, model = Backend(), Model(draft_for('Exercises'))
    model.complete_json = AsyncMock(return_value='{"objective":')
    with pytest.raises(ContentGenerationError, match='invalid_generated_json'):
        await LearningContentGenerator(backend, model).prepare('student-scope', request())
    model.complete_json.assert_awaited_once()
    assert backend.writes == []


@pytest.mark.asyncio
async def test_malformed_structured_review_does_not_store_content():
    backend, model = Backend(), Model(draft_for('Exercises'))
    model.complete_json = AsyncMock(side_effect=[json.dumps(model.draft), '{"passed":'])
    service = LearningContentGenerator(backend, model)
    with pytest.raises(ContentGenerationError, match='invalid_validation_json'):
        await service.prepare('student-scope', request())
    assert [call.kwargs['operation'] for call in model.complete_json.await_args_list] == [
        'learning_content_generate', 'learning_content_validate']
    assert backend.writes == []


@pytest.mark.asyncio
@pytest.mark.parametrize('phase', ['draft', 'review'])
@pytest.mark.parametrize('status', [400, 401, 402, 403, 404, 408, 422, 429, 503])
async def test_content_provider_rejection_is_distinct_and_preserves_other_statuses(phase, status):
    backend, model = Backend(), Model(draft_for('Exercises'))
    response = httpx.Response(status, request=httpx.Request('POST', 'https://example.test/chat/completions'))
    error = APIStatusError('provider rejected the request', response=response, body=None)
    results = [json.dumps(model.draft), error] if phase == 'review' else [error]
    model.complete_json = AsyncMock(side_effect=results)
    generator = LearningContentGenerator(backend, model)
    if status in {400, 422}:
        with pytest.raises(ContentGenerationError, match=f'{phase}_provider_rejected') as failure:
            await generator.prepare('student-scope', request())
        assert failure.value.__cause__ is error
    else:
        with pytest.raises(APIStatusError) as failure:
            await generator.prepare('student-scope', request())
        assert failure.value is error
    assert model.complete_json.await_count == len(results)
    assert all(call.kwargs['retry'] is False for call in model.complete_json.await_args_list)
    assert backend.writes == []


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
async def test_review_must_have_checked_alternatives_even_when_it_passes():
    backend, model = Backend(), Model(draft_for('Exercises'))
    model.review['checkedAlternatives'] = False
    with pytest.raises(ContentGenerationError, match='semantic_validation_failed'):
        await LearningContentGenerator(backend, model).prepare('student-scope', request())
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
@pytest.mark.parametrize(('section', 'field', 'value', 'expected'), [
    (None, 'spokenInstruction', 'PRIVATE_VALUE' * 40,
     {'field': 'spokenInstruction', 'rule': 'too_long', 'type': 'str', 'length': 520, 'maxLength': 400}),
    ('payload', 'explanation', {'PRIVATE_KEY': 'PRIVATE_VALUE'},
     {'field': 'payload.explanation', 'rule': 'expected_string', 'type': 'dict', 'count': 1}),
    ('answerGuide', 'criteria', [],
     {'field': 'answerGuide.criteria', 'rule': 'item_count', 'type': 'list', 'count': 0, 'minCount': 1, 'maxCount': 8}),
    ('payload', 'examples', ['PRIVATE_VALUE', 'PRIVATE_VALUE'],
     {'field': 'payload.examples', 'rule': 'duplicate_items', 'type': 'list', 'count': 2, 'duplicate': True}),
    ('payload', 'examples', [f'PRIVATE_VALUE_{index}' for index in range(5)],
     {'field': 'payload.examples', 'rule': 'item_count', 'type': 'list', 'count': 5, 'minCount': 1, 'maxCount': 4}),
    ('payload', 'explanation', 'Try https://private.invalid/PRIVATE_VALUE',
     {'field': 'payload.explanation', 'rule': 'url_present', 'type': 'str', 'urlPresent': True}),
    ('payload', 'examples', [False],
     {'field': 'payload.examples[0]', 'rule': 'expected_string', 'type': 'bool'}),
    ('payload', 'PRIVATE_KEY', 'PRIVATE_VALUE',
     {'field': 'payload', 'rule': 'unexpected_fields', 'type': 'dict', 'count': 3, 'unexpectedCount': 1}),
])
async def test_invalid_draft_logs_only_validation_metadata(section, field, value, expected, caplog):
    caplog.set_level('INFO', logger='app.diagnostics')
    draft = draft_for('Explanation')
    target = draft if section is None else draft[section]
    target[field] = value
    backend, model = Backend('Explanation'), Model(draft)

    with diagnostic_context(trace_id='draft-test-trace'):
        with pytest.raises(ContentGenerationError, match='invalid_generated_material'):
            await LearningContentGenerator(backend, model).prepare('student-scope', request('comparison'))

    assert len(model.calls) == 1 and backend.writes == []
    [record] = caplog.records
    metadata = json.loads(record.getMessage().removeprefix('runtime_event '))
    assert metadata['event'] == 'learning_content_draft_rejected'
    assert metadata['stage'] == 'compute_validation'
    assert metadata['trace_id'] == 'draft-test-trace'
    assert metadata['method'] == 'Explanation'
    assert {key: metadata[key] for key in expected} == expected
    assert 'PRIVATE_VALUE' not in caplog.text
    assert 'PRIVATE_KEY' not in caplog.text
    assert 'private.invalid' not in caplog.text


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
