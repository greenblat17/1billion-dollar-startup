import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.listening_conversation import TeachingActionError, TeachingActionSession
from app.trial_realtime import RealtimeTrialSession
from app.explanation_exercises import TeachingActionSession as ExerciseActionSession
from tests.test_explanation_exercises import handoff as exercise_handoff, Model as ExerciseModel
from tests.test_trial_realtime import ADMITTED, SETTINGS, output, sent, session


def handoff(method='Exposure', kind=None):
    return {'schemaVersion': 1, 'lessonId': 'lesson', 'taskId': 'task',
            'stageRef': {'taskId': 'task', 'planId': 'plan', 'planRevision': 2, 'stageId': 'stage'},
            'contentRef': {'contentId': 'content', 'revision': 3}, 'catalogVersion': 'catalog-v1',
            'skillId': 'listening' if method == 'Exposure' else 'speaking',
            'topicId': 'topic', 'problemId': 'problem', 'method': method,
            'actionKind': kind or ('dialogue' if method == 'Exposure' else 'personalized'),
            'cefr': 'A2', 'objective': 'Understand a trip story',
            'spokenInstruction': 'Listen and explain.',
            'payload': ({'segments': ['I missed the train.', 'I called a taxi.'],
                         'checks': ['reconstruction', 'meaning']} if method == 'Exposure' else
                        {'openingPrompt': 'What happened on your trip?',
                         'targetOpportunities': ['Use the past tense naturally.']}),
            'answerGuide': {'criteria': ['Understands missed train.'], 'alternatives': [], 'unacceptable': []}}


def verdict(request='answer', decision='demonstrated', asr=False, support='independent',
            feedback='That makes sense. Tell me more.'):
    return json.dumps({'request': request, 'decision': decision, 'asrUncertain': asr,
                       'support': support, 'feedback': feedback, 'rationale': 'Meaning was explained.'})


def model(*answers):
    return SimpleNamespace(complete_json=AsyncMock(side_effect=list(answers)))


def answer(number, text='I missed the train.'):
    return {'eventId': f'user-{number}', 'revision': 1, 'text': text}


@pytest.mark.asyncio
async def test_listening_repeats_then_checks_words_and_meaning_per_segment():
    m = model(verdict(request='repeat', decision='none'), verdict(), verdict(), verdict(), verdict())
    action = TeachingActionSession(handoff(), m)
    assert 'I missed the train.' in action.start()
    repeated = await action.respond(answer(1, 'Could you say that again?'), [])
    assert repeated['event'] is None and action.phase == 'reconstruct'
    assert 'I missed the train.' in repeated['spokenFeedback']
    first = await action.respond(answer(2), [])
    assert first['event']['phase'] == 'reconstruct' and action.phase == 'meaning'
    assert first['event']['score'] is None
    meaning = await action.respond(answer(3, 'Я опоздал на поезд.'), [])
    assert meaning['event']['phase'] == 'meaning'
    assert 'I called a taxi.' in meaning['spokenFeedback'] and action.phase == 'reconstruct'
    await action.respond(answer(4, 'I called a taxi.'), [])
    final = await action.respond(answer(5, 'Я вызвал такси.'), [])
    assert action.done and len(action.events) == 4
    assert 'continue with the conversation' in final['spokenFeedback']
    assert all(e['contentRef'] == {'contentId': 'content', 'revision': 3} for e in action.events)


@pytest.mark.asyncio
async def test_listening_uncertain_asr_and_hint_do_not_create_independent_success():
    action = TeachingActionSession(handoff(), model(verdict(asr=True), verdict(support='independent')))
    action.start()
    uncertain = await action.respond(answer(1, 'I miss...'), [])
    assert uncertain['next'] == 'help' and action.phase == 'reconstruct'
    assert uncertain['event']['quality'] == 'uncertain'
    hint = {'eventId': 'assistant-1', 'revision': 1, 'text': 'The word was missed.',
            'role': 'assistant', 'purpose': 'hint'}
    helped = await action.respond(answer(2), [hint])
    assert helped['event']['independence'] == 'hinted'
    assert helped['event']['decision'] == 'demonstrated'


@pytest.mark.asyncio
async def test_repetition_without_explained_meaning_cannot_finish_segment():
    action = TeachingActionSession(handoff(), model(verdict(),
        verdict(decision='not_demonstrated', feedback='You repeated the sounds. What do they mean?'),
        verdict()))
    action.start()
    await action.respond(answer(1), [])
    repeated = await action.respond(answer(2), [])
    assert repeated['event']['phase'] == 'meaning'
    assert repeated['next'] == 'help' and action.phase == 'meaning' and action.segment == 0
    explained = await action.respond(answer(3, 'Я опоздал на поезд.'), [])
    assert action.segment == 1 and 'I called a taxi.' in explained['spokenFeedback']


@pytest.mark.asyncio
@pytest.mark.parametrize('kind,mode', [('personalized', 'free'), ('topic_driven', 'guided'),
                                      ('situational', 'role_play')])
async def test_conversation_modes_emit_unscored_transfer_evidence(kind, mode):
    action = TeachingActionSession(handoff('Conversation', kind), model(verdict()))
    assert action.start() == 'What happened on your trip?'
    result = await action.respond(answer(1, 'I missed a train, so I called a taxi.'), [])
    assert result['event']['mode'] == mode
    assert result['event']['skillId'] == 'speaking'
    assert result['event']['independence'] == 'independent'
    assert result['event']['score'] is None


@pytest.mark.asyncio
async def test_direct_change_and_stop_take_precedence_over_action():
    for request, expected in [('change_topic', 'switch'), ('stop', 'stop')]:
        action = TeachingActionSession(handoff('Conversation'), model(verdict(request=request, decision='none')))
        result = await action.respond(answer(1, 'Can we do something else?'), [])
        assert result['next'] == expected and result['event'] is None and action.done


@pytest.mark.asyncio
async def test_invalid_or_duplicate_saved_answer_is_rejected():
    action = TeachingActionSession(handoff('Conversation'), model(verdict()))
    with pytest.raises(TeachingActionError, match='unsaved_answer'):
        await action.respond({'text': 'hello'}, [])
    await action.respond(answer(1), [])
    with pytest.raises(TeachingActionError, match='duplicate_answer'):
        await action.respond(answer(1), [])


@pytest.mark.asyncio
async def test_malformed_assessment_does_not_create_evidence_or_advance():
    action = TeachingActionSession(handoff(), model('{broken'))
    action.start()
    result = await action.respond(answer(1), [])
    assert result['event'] is None and result['next'] == 'help'
    assert action.phase == 'reconstruct' and not action.events


def regular_action_session():
    base = session()
    base.backend.record_learning_observation = AsyncMock(return_value={'created': True})
    base.backend.learning_problems = AsyncMock(return_value={'items': []})
    admitted = {**ADMITTED, 'lesson': {**ADMITTED['lesson'], 'kind': 'regular'},
                'issuedTeachingContent': handoff()}
    m = model(verdict())
    s = RealtimeTrialSession(base.socket, base.backend, base.contexts, base.store,
                             admitted, SETTINGS, m)
    s.provider = base.provider
    s.practice_started = True
    s.regular_phase = lambda: 'practice'
    return s, m


@pytest.mark.asyncio
async def test_regular_voice_hook_waits_for_saved_transcript_before_response():
    s, m = regular_action_session()
    s.backend.command.return_value = {'eventId': 'saved-user', 'sequence': 7}
    await s.provider_event({'type': 'input_audio_buffer.committed', 'item_id': 'turn-1'})
    assert not any(x['type'] == 'response.create' for x in sent(s))
    await s.provider_event({'type': 'conversation.item.input_audio_transcription.completed',
                            'item_id': 'turn-1', 'transcript': 'I missed the train.'})
    assert m.complete_json.await_count == 1
    assert s.action.events[0]['answerSource'] == {'eventId': 'saved-user', 'revision': 7}
    assert s.action_events[0]['outcome'] == 'independent_correct'
    assert s.action_events[0]['actionId'] == 'content:3'
    s.backend.record_learning_observation.assert_awaited_once()
    assert sent(s)[-1]['type'] == 'response.create'
    assert 'What does that part mean?' in sent(s)[-1]['response']['instructions']


def test_trial_ignores_issued_teaching_content():
    base = session()
    admitted = {**ADMITTED, 'issuedTeachingContent': handoff()}
    s = RealtimeTrialSession(base.socket, base.backend, base.contexts, base.store,
                             admitted, SETTINGS)
    assert s.action is None


def test_regular_voice_hook_dispatches_issued_exercise_to_sp14():
    base = session()
    issued = {**exercise_handoff(), 'lessonId': 'lesson'}
    admitted = {**ADMITTED, 'lesson': {**ADMITTED['lesson'], 'kind': 'regular'},
                'issuedTeachingContent': issued}
    s = RealtimeTrialSession(base.socket, base.backend, base.contexts, base.store,
                             admitted, SETTINGS, ExerciseModel())
    assert isinstance(s.action, ExerciseActionSession)
    assert 'I go to work yesterday.' in s.action.start()


@pytest.mark.asyncio
async def test_voice_hint_is_available_only_after_saved_complete_playback():
    s, _ = regular_action_session()
    s.backend.command.return_value = {'eventId': 'assistant-1', 'sequence': 9, 'objectKey': 'trial/key'}
    s.action_reply_purpose = 'hint'
    await output(s)
    await s.provider_event({'type': 'response.output_audio.done', 'item_id': 'reply'})
    await s.provider_event({'type': 'response.output_audio_transcript.done',
                            'item_id': 'reply', 'transcript': 'The word was missed.'})
    assert not s.action_history
    await s.settle('reply', {'complete': True, 'playedMs': 100})
    assert s.action_history == [{'eventId': 'assistant-1', 'revision': 9,
                                 'text': 'The word was missed.', 'role': 'assistant', 'purpose': 'hint'}]
