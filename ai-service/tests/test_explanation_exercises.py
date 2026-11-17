import json

import pytest

from app.explanation_exercises import TeachingActionError, TeachingActionSession
from app.learning_content_generation import issued_handoff


class Model:
    def __init__(self, verdict='correct', *, previous_wrong=False, switch_target=None):
        self.verdict, self.previous_wrong, self.switch_target = verdict, previous_wrong, switch_target
        self.calls = []

    async def complete_json(self, system, data, **kwargs):
        self.calls.append((system, json.loads(data), kwargs))
        return json.dumps({'verdict': self.verdict, 'feedback': 'That conveys the past event well.',
                           'rationale': 'The meaning satisfies the past-event criterion.',
                           'previousAnswerWasIncorrect': self.previous_wrong,
                           **({'switchTarget': self.switch_target} if self.switch_target else {})})


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
    result = await TeachingActionSession(handoff(), Model(verdict)).respond(answer(), [])
    event = result['event']
    assert event['outcome'] == expected and event['quality']['status'] == quality
    assert event['route'] == ('stage_progress' if quality == 'usable' else 'unresolved')
    assert 'score' not in event and 'progress' not in event


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
async def test_malformed_assessment_produces_no_evidence_or_false_feedback():
    class BadModel:
        async def complete_json(self, *args, **kwargs):
            return '{not json'

    result = await TeachingActionSession(handoff(), BadModel()).respond(answer(), [])
    assert result['event'] is None and result['next'] == 'help'


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
