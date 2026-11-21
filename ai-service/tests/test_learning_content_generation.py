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
    prompt = model.calls[0][0]
    for limit in (
        'objective: nonblank string, at most 1000 UTF-16 units',
        'spokenInstruction: nonblank string, at most 400 UTF-16 units',
        'answerGuide.criteria: 1–8 unique nonblank strings, at most 300 UTF-16 units each',
        'answerGuide.alternatives: 0–8 unique nonblank strings, at most 300 UTF-16 units each',
        'answerGuide.unacceptable: 0–8 unique nonblank strings, at most 300 UTF-16 units each',
        'including metadata must fit 16000 UTF-16 units',
        'one short sentence for objective',
    ):
        assert limit in prompt
    payload_limits = {
        'Explanation': ('explanation: nonblank string, at most 1200 UTF-16 units',
                        'examples: 1–4 unique nonblank strings, at most 300 UTF-16 units each'),
        'Exercises': ('stimulus: nonblank string, at most 300 UTF-16 units',
                      'targetOperation: nonblank string, at most 300 UTF-16 units'),
        'Exposure': ('segments: 1–8 unique nonblank strings, at most 250 UTF-16 units each',
                     'checks: 1–2 unique nonblank strings, at most 40 UTF-16 units each'),
        'Conversation': ('openingPrompt: nonblank string, at most 400 UTF-16 units',
                         'targetOpportunities: 1–6 unique nonblank strings, at most 250 UTF-16 units each'),
    }[method]
    assert all(f'payload.{limit}' in prompt for limit in payload_limits)
    assert ('Aim for exactly two short examples.' in prompt) == (method == 'Explanation')
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
async def test_valid_semantic_rejection_repairs_once_and_gets_independent_review(caplog):
    backend = Backend()
    draft = draft_for('Exercises')
    repaired = copy.deepcopy(draft)
    repaired['spokenInstruction'] = 'Say the sentence using the past form.'
    model = Model(draft, {'passed': False,
        'reasons': ['ambiguous_condition', 'invalid_alternative'], 'checkedAlternatives': True,
        'rationale': 'PRIVATE_RATIONALE'})
    accepted = {'passed': True, 'reasons': [], 'checkedAlternatives': True, 'rationale': 'Aligned.'}
    model.complete_json = AsyncMock(side_effect=map(json.dumps, [draft, model.review, repaired, accepted]))
    caplog.set_level('INFO', logger='app.diagnostics')

    prepared = await LearningContentGenerator(backend, model).prepare('student-scope', request())

    generate, audit, repair, reaudit = model.complete_json.await_args_list
    assert [call.kwargs['operation'] for call in (generate, audit, repair, reaudit)] == [
        'learning_content_generate', 'learning_content_validate',
        'learning_content_repair', 'learning_content_validate_repair',
    ]
    assert all(call.kwargs['retry'] is False for call in (generate, audit, repair, reaudit))
    assert generate.kwargs['response_format'] == repair.kwargs['response_format']
    assert audit.kwargs['response_format'] == reaudit.kwargs['response_format']
    assert audit.args[0] == reaudit.args[0]
    context = json.loads(generate.args[1])
    assert json.loads(audit.args[1]) == {'context': context, 'draft': draft}
    assert json.loads(repair.args[1]) == {'context': context, 'draft': draft,
                                        'reasonCodes': model.review['reasons']}
    assert json.loads(reaudit.args[1]) == {'context': context, 'draft': repaired}
    assert prepared.document['spokenInstruction'] == repaired['spokenInstruction']
    assert prepared.review == accepted
    [record] = caplog.records
    metadata = json.loads(record.getMessage().removeprefix('runtime_event '))
    assert metadata['rule'] == 'semantic_rejection' and metadata['attempt'] == 1
    assert metadata['reasonCodes'] == model.review['reasons']
    assert 'PRIVATE_RATIONALE' not in caplog.text
    assert backend.writes == []


@pytest.mark.asyncio
@pytest.mark.parametrize('failed_repair', ['shape', 'unchanged', 'review', 'unchecked', 'budget'])
async def test_repair_cannot_bypass_shape_or_semantic_gate_or_retry(failed_repair, caplog):
    backend, model = Backend(), Model(draft_for('Exercises'))
    rejection = {'passed': False, 'reasons': ['language_error'], 'checkedAlternatives': True,
                 'rationale': 'PRIVATE_RATIONALE'}
    results = [json.dumps(model.draft), json.dumps(rejection)]
    expected = 'semantic_validation_failed'
    if failed_repair == 'shape':
        results.append(json.dumps(dict(model.draft, objective=' ')))
        expected = 'invalid_generated_material'
    elif failed_repair == 'unchanged':
        results.append(json.dumps(model.draft))
    elif failed_repair == 'budget':
        response = httpx.Response(402, request=httpx.Request('POST', 'https://example.test/chat/completions'))
        error = APIStatusError('provider budget exhausted', response=response, body=None)
        results.append(error)
    else:
        second_review = rejection if failed_repair == 'review' else dict(model.review, checkedAlternatives=False)
        repaired = dict(model.draft, spokenInstruction='Say the sentence using the past form.')
        results.extend([json.dumps(repaired), json.dumps(second_review)])
    model.complete_json = AsyncMock(side_effect=results)
    caplog.set_level('INFO', logger='app.diagnostics')
    if failed_repair == 'budget':
        with pytest.raises(APIStatusError) as failure:
            await LearningContentGenerator(backend, model).prepare('student-scope', request())
        assert failure.value is error
    else:
        with pytest.raises(ContentGenerationError, match=expected):
            await LearningContentGenerator(backend, model).prepare('student-scope', request())
    assert model.complete_json.await_count == len(results)
    assert backend.writes == []
    events = [json.loads(record.getMessage().removeprefix('runtime_event ')) for record in caplog.records]
    if failed_repair in {'review', 'unchecked'}:
        assert events[-1]['attempt'] == 2
        assert events[-1]['operation'] == 'learning_content_validate_repair'
    if failed_repair == 'unchanged':
        assert events[-1]['rule'] == 'unchanged_repair'
    assert 'PRIVATE_RATIONALE' not in caplog.text


@pytest.mark.asyncio
@pytest.mark.parametrize('audit_passed', [True, False])
async def test_overlong_instruction_uses_shared_repair_before_audit(audit_passed, caplog):
    backend = Backend('Explanation')
    repaired = draft_for('Explanation')
    draft = dict(repaired, spokenInstruction='S' * 786)
    model = Model(draft)
    verdict = dict(model.review, passed=audit_passed, reasons=[] if audit_passed else ['wrong_topic'])
    model.complete_json = AsyncMock(side_effect=map(json.dumps, [draft, repaired, verdict]))
    caplog.set_level('INFO', logger='app.diagnostics')
    service = LearningContentGenerator(backend, model)
    if audit_passed:
        result = await service.prepare('student-scope', request('comparison'))
        assert result.document['spokenInstruction'] == repaired['spokenInstruction']
    else:
        with pytest.raises(ContentGenerationError, match='semantic_validation_failed'):
            await service.prepare('student-scope', request('comparison'))
    generate, repair, audit = model.complete_json.await_args_list
    assert [call.kwargs['operation'] for call in (generate, repair, audit)] == [
        'learning_content_generate', 'learning_content_repair', 'learning_content_validate_repair',
    ]
    feedback = json.loads(repair.args[1])
    assert feedback['validationIssue'] == {'field': 'spokenInstruction', 'rule': 'too_long', 'type': 'str',
                                           'length': 786, 'count': None, 'maxLength': 400}
    assert feedback['draft'] == draft
    assert generate.kwargs['response_format'] == repair.kwargs['response_format']
    assert 'spokenInstruction: nonblank string, at most 400 UTF-16 units' in repair.args[0]
    assert json.loads(audit.args[1]) == {'context': feedback['context'], 'draft': repaired}
    assert backend.writes == []
    assert draft['spokenInstruction'] not in caplog.text


@pytest.mark.asyncio
async def test_serialized_size_failure_stops_after_single_repair_before_audit(caplog):
    backend = Backend('Explanation')
    draft = draft_for('Explanation')
    # Escaped quotes can hit the serialized cap while each field fits its own limit.
    draft['payload'] = {'explanation': '"' * 1200,
                        'examples': [f'{index}' + '"' * 299 for index in range(4)]}
    draft['answerGuide'] = {field: [f'{index}' + '"' * 299 for index in range(8)]
                            for field in ('criteria', 'alternatives', 'unacceptable')}
    model = Model(draft)
    model.complete_json = AsyncMock(return_value=json.dumps(draft))
    caplog.set_level('INFO', logger='app.diagnostics')
    with pytest.raises(ContentGenerationError, match='content_too_large'):
        await LearningContentGenerator(backend, model).prepare('student-scope', request('comparison'))
    assert [call.kwargs['operation'] for call in model.complete_json.await_args_list] == [
        'learning_content_generate', 'learning_content_repair',
    ]
    events = [json.loads(record.getMessage().removeprefix('runtime_event ')) for record in caplog.records]
    for attempt, event in enumerate(events, start=1):
        assert event['field'] == 'document' and event['rule'] == 'too_long' and event['attempt'] == attempt
        assert event['length'] > event['maxLength'] == 16000
    assert len(events) == 2 and backend.writes == []


@pytest.mark.asyncio
@pytest.mark.parametrize(('override', 'field', 'rule'), [
    ({'PRIVATE_KEY': 'PRIVATE_VALUE'}, 'review', 'unexpected_fields'),
    ({'passed': 1}, 'passed', 'expected_boolean'),
    ({'checkedAlternatives': 'PRIVATE_VALUE'}, 'checkedAlternatives', 'expected_boolean'),
    ({'checkedAlternatives': False}, 'checkedAlternatives', 'alternatives_unchecked'),
    ({'reasons': 'PRIVATE_VALUE'}, 'reasons', 'expected_array'),
    ({'reasons': ['PRIVATE_VALUE']}, 'reasons[0]', 'unknown_reason'),
    ({'reasons': ['language_error', 'language_error']}, 'reasons', 'duplicate_items'),
    ({'reasons': ['wrong_topic']}, 'reasons', 'expected_empty_on_pass'),
    ({'passed': False}, 'reasons', 'expected_nonempty_on_reject'),
    ({'rationale': ' '}, 'rationale', 'blank'),
    ({'rationale': 'PRIVATE_VALUE' * 100}, 'rationale', 'too_long'),
])
async def test_invalid_review_diagnoses_exact_rule_without_repair(override, field, rule, caplog):
    backend, model = Backend(), Model(draft_for('Exercises'))
    model.review.update(override)
    caplog.set_level('INFO', logger='app.diagnostics')
    with diagnostic_context(trace_id='review-test-trace'), pytest.raises(
            ContentGenerationError, match='semantic_validation_failed'):
        await LearningContentGenerator(backend, model).prepare('student-scope', request())
    assert len(model.calls) == 2 and backend.writes == []
    [record] = caplog.records
    metadata = json.loads(record.getMessage().removeprefix('runtime_event '))
    assert metadata['trace_id'] == 'review-test-trace'
    assert metadata['event'] == 'learning_content_review_rejected'
    assert metadata['field'] == field and metadata['rule'] == rule and metadata['attempt'] == 1
    assert 'PRIVATE_KEY' not in caplog.text and 'PRIVATE_VALUE' not in caplog.text


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
    model.complete_json = AsyncMock(return_value=json.dumps(draft))
    with pytest.raises(ContentGenerationError, match='invalid_generated_material'):
        await LearningContentGenerator(backend, model).prepare('student-scope', request())
    assert [call.kwargs['operation'] for call in model.complete_json.await_args_list] == [
        'learning_content_generate', 'learning_content_repair',
    ]
    assert backend.writes == []


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
    model.complete_json = AsyncMock(return_value=json.dumps(draft))

    with diagnostic_context(trace_id='draft-test-trace'):
        with pytest.raises(ContentGenerationError, match='invalid_generated_material'):
            await LearningContentGenerator(backend, model).prepare('student-scope', request('comparison'))

    assert model.complete_json.await_count == 2 and backend.writes == []
    assert len(caplog.records) == 2
    for attempt, record in enumerate(caplog.records, start=1):
        metadata = json.loads(record.getMessage().removeprefix('runtime_event '))
        assert metadata['event'] == 'learning_content_draft_rejected'
        assert metadata['stage'] == 'compute_validation'
        assert metadata['trace_id'] == 'draft-test-trace'
        assert metadata['method'] == 'Explanation' and metadata['attempt'] == attempt
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
