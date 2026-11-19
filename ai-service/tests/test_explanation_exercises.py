import json
from unittest.mock import AsyncMock

import httpx
import pytest
from openai import APIStatusError

from app.diagnostics import diagnostic_context
from app.explanation_exercises import TeachingActionError, TeachingActionSession
from app.learning_content_generation import issued_handoff


class Model:
    def __init__(self, verdict='correct', *, previous_wrong=False, switch_target=None, error_evidence=None):
        self.verdict, self.previous_wrong, self.switch_target = verdict, previous_wrong, switch_target
        self.error_evidence = error_evidence
        self.calls = []

    async def complete_json(self, system, data, **kwargs):
        self.calls.append((system, json.loads(data), kwargs))
        return json.dumps({'verdict': self.verdict, 'feedback': 'That conveys the past event well.',
                           'rationale': 'The meaning satisfies the past-event criterion.',
                           'previousAnswerWasIncorrect': self.previous_wrong,
                           'switchTarget': self.switch_target, 'errorEvidence': self.error_evidence})


def handoff(method='Exercises', kind='sentence_transformation'):
    payload = ({'explanation': 'Use the past form for a finished event.',
                'examples': ['I went to work yesterday.']} if method == 'Explanation' else
               {'stimulus': 'I go to work yesterday.',
                'targetOperation': 'Express the same event naturally in the past.'})
    document = {'taskId': 'task-1', 'stageRef': {'taskId': 'task-1', 'planId': 'plan-1',
                'planRevision': 2, 'stageId': 'stage-1'},
                'catalogVersion': 'published-v1', 'skillId': 'grammar', 'topicId': 'past',
                'problemId': 'grammar_application', 'method': method, 'actionKind': kind,
                'cefr': 'A2', 'objective': 'Use past form for a completed event.',
                'spokenInstruction': 'Say your answer aloud.', 'payload': payload,
                'answerGuide': {'criteria': ['Express a completed event in past tense.'],
                                'alternatives': ['I went to work yesterday.',
                                                 'Yesterday, I travelled to my workplace.'],
                                'unacceptable': ['I go to work yesterday.']},
                'validation': {'status': 'passed'}}
    return issued_handoff({'id': 'content-1', 'revision': 3, 'status': 'issued',
                           'issuedLessonId': 'lesson-1', 'document': document})


def answer(event_id='answer-1', text='Yesterday, I travelled to my workplace.', **changes):
    return {'eventId': event_id, 'revision': 1, 'text': text, **changes}


def earlier(event_id='earlier', text='I go to work yesterday.', **changes):
    return answer(event_id, text, role='user', purpose='answer', **changes)


def hint():
    return {'eventId': 'hint-1', 'revision': 1, 'text': 'Try the past form of go.',
            'role': 'assistant', 'purpose': 'hint'}


@pytest.mark.parametrize('kind', [
    'sentence_construction', 'sentence_transformation', 'sentence_correction', 'translation',
    'word_recall', 'meaning_recognition', 'meaning_matching', 'listening_reconstruction',
    'listening_comprehension', 'rapid_response', 'sentence_expansion', 'paraphrasing'])
@pytest.mark.asyncio
async def test_each_exercise_family_can_run_by_voice_and_accept_semantic_alternative(kind):
    model = Model()
    action = TeachingActionSession(handoff(kind=kind), model)
    assert 'I go to work yesterday.' in action.start()
    result = await action.respond(answer(), [])
    assert result['event']['outcome'] == 'independent_correct'
    assert result['event']['route'] == 'stage_progress'
    assert result['event']['contentRef'] == {'contentId': 'content-1', 'revision': 3}
    assert model.calls[0][1]['answerGuide']['alternatives'][1] == answer()['text']
    assert model.calls[0][2]['operation'] == 'sp14_teaching_action'
    assert model.calls[0][2]['retry'] is False


@pytest.mark.asyncio
@pytest.mark.parametrize(('method', 'kind'), [('Explanation', 'direct'), ('Exercises', 'sentence_transformation')])
async def test_assessment_requests_strict_schema_with_required_nullable_branches(method, kind):
    model = Model()
    result = await TeachingActionSession(handoff(method, kind), model).respond(answer(), [])
    assert result['event']['outcome'] == 'independent_correct'
    prompt, _, kwargs = model.calls[0]
    assert 'null for errorEvidence' in prompt and 'null for other verdicts' in prompt
    assert kwargs['retry'] is False
    response_format = kwargs['response_format']
    assert response_format['type'] == 'json_schema' and response_format['json_schema']['strict'] is True
    schema = response_format['json_schema']['schema']
    properties = schema['properties']
    assert set(schema['required']) == set(properties) == {
        'verdict', 'feedback', 'rationale', 'previousAnswerWasIncorrect', 'switchTarget', 'errorEvidence',
    }
    assert schema['additionalProperties'] is False
    assert set(properties['switchTarget']['enum']) == {None, 'unclear', 'different_action', 'free_conversation'}
    assert properties['previousAnswerWasIncorrect'] == {'type': 'boolean'}
    evidence, null_type = properties['errorEvidence']['anyOf']
    assert null_type == {'type': 'null'}
    assert evidence['additionalProperties'] is False
    assert set(evidence['required']) == set(evidence['properties']) == {'criterion', 'quote'}


@pytest.mark.asyncio
async def test_explanation_is_spoken_and_acknowledgment_is_not_demonstration():
    action = TeachingActionSession(handoff('Explanation', 'direct'), Model('acknowledgment'))
    assert 'Use the past form' in action.start()
    assert 'I went to work yesterday.' in action.start()
    result = await action.respond(answer(text='Yes, I understand.'), [])
    assert result['event'] is None
    assert result['next'] == 'continue'


@pytest.mark.asyncio
async def test_self_correction_needs_prior_error_and_no_hint():
    action = TeachingActionSession(handoff(), Model(previous_wrong=True))
    result = await action.respond(answer('answer-2'), [earlier()])
    assert result['event']['outcome'] == 'self_corrected_error'
    assert result['event']['initialOutcome'] == 'error'
    assert len(result['event']['sources']) == 2
    assert result['event']['sources'][1]['purpose'] == 'correction'
    no_proof = await TeachingActionSession(handoff(), Model()).respond(answer('answer-2'), [earlier()])
    assert no_proof['event']['outcome'] == 'independent_correct'


@pytest.mark.asyncio
async def test_hint_takes_precedence_over_self_correction():
    action = TeachingActionSession(handoff(), Model(previous_wrong=True))
    result = await action.respond(answer('answer-2'), [earlier(), hint()])
    event = result['event']
    assert event['outcome'] == 'hinted_correct' and event['support'] == 'hinted'
    assert event['sources'][1] == {'eventId': 'hint-1', 'revision': 1,
                                   'role': 'assistant', 'purpose': 'hint'}


@pytest.mark.asyncio
@pytest.mark.parametrize('verdict,next_action', [
    ('question', 'help'), ('help', 'help'), ('switch', 'switch'), ('stop', 'stop'),
    ('acknowledgment', 'continue')])
async def test_learner_control_is_not_graded(verdict, next_action):
    result = await TeachingActionSession(handoff(), Model(verdict)).respond(answer(), [])
    assert result['event'] is None and result['next'] == next_action


@pytest.mark.asyncio
@pytest.mark.parametrize('target,expected', [
    ('different_action', 'switch_action'), ('free_conversation', 'switch_conversation')])
async def test_switch_destination_is_preserved_without_grading(target, expected):
    result = await TeachingActionSession(handoff(), Model('switch', switch_target=target)).respond(answer(), [])
    assert result['event'] is None and result['next'] == expected


@pytest.mark.asyncio
@pytest.mark.parametrize('verdict,expected,quality', [
    ('incorrect', 'uncorrected_error', 'usable'),
    ('uncertain', 'unresolved', 'uncertain'),
    ('defective', 'invalid', 'invalid')])
async def test_errors_and_defects_keep_distinct_outcomes(verdict, expected, quality):
    evidence = {'criterion': 'Express a completed event in past tense.', 'quote': 'I go'} if verdict == 'incorrect' else None
    result = await TeachingActionSession(handoff(), Model(verdict, error_evidence=evidence)).respond(
        answer(text='I go to work yesterday.'), [])
    event = result['event']
    assert event['outcome'] == expected and event['quality']['status'] == quality
    assert event['route'] == ('stage_progress' if quality == 'usable' else 'unresolved')
    assert 'score' not in event and 'progress' not in event


@pytest.mark.asyncio
@pytest.mark.parametrize('evidence', [
    None,
    {'criterion': 'Unknown criterion', 'quote': 'we are already addicted'},
    {'criterion': 'Express a completed event in past tense.', 'quote': 'we have already fixed it'},
    {'criterion': 'Express a completed event in past tense.', 'quote': 'addict'},
])
async def test_uncorroborated_exercise_error_requests_repeat_without_hint(evidence):
    from app.teaching_observations import observation_from_action, observation_operation_id

    model = Model('incorrect', error_evidence=evidence)
    action = TeachingActionSession(handoff(), model)
    saved = answer(text='We found them on yesterday, and we are already addicted.')
    result = await action.respond(saved, [])
    observation = observation_from_action(result['event'], saved, [])
    assert observation['outcome'] == 'unresolved' and observation['route'] == 'unresolved'
    assert observation['initialOutcome'] == observation['support'] == 'unknown'
    assert observation['quality'] == {'status': 'uncertain', 'signals': [{
        'reason': 'ambiguous_interpretation', 'component': 'sp14.explanation_exercises',
        'sourceEventId': saved['eventId'],
    }]}
    assert result['next'] == 'continue' and 'Повтори' in result['spokenFeedback']
    assert 'An unrelated or incoherent transcript' in model.calls[0][0]
    repeat = await action.respond(saved, [])
    assert observation_operation_id(observation) == observation_operation_id(
        observation_from_action(repeat['event'], saved, []))


@pytest.mark.asyncio
async def test_uncertain_asr_and_repeated_answer_are_not_independent_successes():
    action = TeachingActionSession(handoff(), Model())
    result = await action.respond(answer(asrUncertain=True), [])
    assert result['event']['outcome'] == 'unresolved'
    assert result['event']['quality']['signals'][0]['reason'] == 'uncertain_asr'
    assert 'неверно расслышать' in result['spokenFeedback']
    repeated = await action.respond(answer('answer-2'), [earlier(text=answer()['text'], attemptId='attempt-1')])
    assert repeated['event'] is None and repeated['repeatOf'] == 'attempt-1'


@pytest.mark.asyncio
@pytest.mark.parametrize(('invalid', 'patch', 'stage', 'expected'), [
    ('{"PRIVATE_TEXT"', {}, 'parse', {'reason': 'invalid_assessment_json', 'line': 1}),
    ('[]', {}, 'shape', {'field': 'verdict_response', 'rule': 'expected_object'}),
    ('{"verdict":"correct"}', {}, 'shape', {'field': 'verdict_response', 'rule': 'unexpected_fields',
      'missingFields': ['errorEvidence', 'feedback', 'previousAnswerWasIncorrect', 'rationale', 'switchTarget']}),
    (None, {'PRIVATE_FIELD': 'PRIVATE_TEXT'}, 'shape',
     {'field': 'verdict_response', 'rule': 'unexpected_fields', 'unexpectedCount': 1}),
    (None, {'feedback': 'PRIVATE_TEXT' * 60}, 'shape',
     {'field': 'feedback', 'rule': 'too_long', 'maxLength': 500}),
    (None, {'previousAnswerWasIncorrect': 1}, 'shape',
     {'field': 'previousAnswerWasIncorrect', 'rule': 'expected_boolean'}),
    (None, {'switchTarget': []}, 'shape', {'field': 'switchTarget', 'rule': 'invalid_enum'}),
    (None, {'switchTarget': 'different_action'}, 'shape', {'field': 'switchTarget', 'rule': 'non_switch_target'}),
    (None, {'errorEvidence': {'criterion': 'PRIVATE_TEXT', 'quote': 'PRIVATE_TEXT'}}, 'shape',
     {'field': 'errorEvidence', 'rule': 'unexpected_evidence'}),
    (None, {'verdict': 'incorrect', 'errorEvidence': {'criterion': 'PRIVATE_TEXT', 'quote': []}}, 'shape',
     {'field': 'errorEvidence.quote', 'rule': 'expected_string'}),
])
async def test_rejected_assessment_preserves_exact_fallback_and_logs_safe_metadata(invalid, patch, stage, expected, caplog):
    model = Model()
    if invalid is None:
        valid = json.loads(await model.complete_json('', '{}'))
        invalid = json.dumps({**valid, **patch})
    model.complete_json = AsyncMock(return_value=invalid)
    caplog.set_level('INFO', logger='app.diagnostics')
    with diagnostic_context(trace_id='assessment-test'):
        result = await TeachingActionSession(handoff(), model).respond(answer(text='PRIVATE_ANSWER'), [])
    assert result == {
        'spokenFeedback': 'Я не уверен, что верно понял ответ. Повтори, пожалуйста, или попроси другой пример.',
        'event': None, 'next': 'help',
    }
    model.complete_json.assert_awaited_once()
    [record] = caplog.records
    metadata = json.loads(record.getMessage().removeprefix('runtime_event '))
    assert metadata['event'] == 'sp14_assessment_rejected'
    assert metadata['stage'] == stage and metadata['trace_id'] == 'assessment-test'
    assert metadata['reason'] == ('invalid_assessment_json' if stage == 'parse' else 'invalid_assessment')
    assert metadata['source_event_id'] == 'answer-1'
    assert {key: metadata[key] for key in expected} == expected
    assert 'PRIVATE_' not in caplog.text


@pytest.mark.asyncio
@pytest.mark.parametrize('status', [400, 401, 402, 422, 429, 503])
async def test_assessment_provider_failure_returns_ungraded_help_and_logs_status_without_retry(status, caplog):
    response = httpx.Response(status, request=httpx.Request('POST', 'https://example.test/chat/completions'))
    error = APIStatusError('PRIVATE_PROVIDER_MESSAGE', response=response, body={'message': 'PRIVATE_PROVIDER_BODY'})
    model = Model()
    model.complete_json = AsyncMock(side_effect=error)
    caplog.set_level('INFO', logger='app.diagnostics')
    outcome = await TeachingActionSession(handoff(), model).respond(answer(), [])
    assert outcome == {
        'spokenFeedback': 'Не получилось проверить ответ. Можешь повторить его или попросить другое упражнение.',
        'event': None, 'next': 'help', 'assessmentUnavailable': True,
    }
    model.complete_json.assert_awaited_once()
    assert model.complete_json.await_args.kwargs['retry'] is False
    [record] = caplog.records
    metadata = json.loads(record.getMessage().removeprefix('runtime_event '))
    assert metadata['stage'] == 'provider' and metadata['http_status'] == status
    assert metadata['reason'] == 'assessment_provider_error'
    assert 'PRIVATE_PROVIDER_' not in caplog.text


@pytest.mark.asyncio
async def test_unsaved_or_duplicate_events_cannot_become_evidence():
    action = TeachingActionSession(handoff(), Model())
    with pytest.raises(TeachingActionError, match='saved_event_required'):
        await action.respond({'text': 'I went.'}, [])
    with pytest.raises(TeachingActionError, match='invalid_action_history'):
        await action.respond(answer(), [earlier('answer-1')])


def test_unissued_or_other_method_cannot_start():
    with pytest.raises(Exception, match='content_not_issued'):
        issued_handoff({'id': 'content-1', 'revision': 1, 'status': 'prepared',
                        'issuedLessonId': None, 'document': {}})
    with pytest.raises(TeachingActionError, match='unsupported_action'):
        TeachingActionSession(handoff('Explanation', 'direct') | {'method': 'Exposure'}, Model())
