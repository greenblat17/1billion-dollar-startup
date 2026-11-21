import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.foundation_client import BackendFailure
from app.regular_evidence import detect_problem
from app.trial_realtime import RealtimeTrialSession


CATALOG = {'version': 'v1', 'topics': [
    {'id': 'past', 'skillId': 'grammar', 'title': 'Past tense'},
    {'id': 'travel', 'skillId': 'speaking', 'title': 'Travel'}],
    'problems': [{'id': 'application', 'skillId': 'grammar', 'title': 'Applying past tense'}]}
TURN = {'eventId': 'event-1', 'revision': 4, 'text': 'Yesterday I go to work.',
        'occurredAt': '2026-11-17T10:00:00+00:00'}


def classifier(result):
    return SimpleNamespace(complete_json=AsyncMock(return_value=json.dumps(result)))


@pytest.mark.asyncio
async def test_free_conversation_evidence_uses_only_supported_catalog_pair_and_saved_event():
    result = {'topicId': 'past', 'problemId': 'application', 'outcome': 'uncorrected_error',
              'asrUncertain': False, 'evidence': 'Yesterday I go', 'rationale': 'Past tense form was not used.'}
    observation = await detect_problem(classifier(result), CATALOG, TURN, 'regular-1')
    assert observation['route'] == 'problem_detection'
    assert observation['taskId'] is None and observation['stageRef'] is None
    assert observation['sources'] == [{'eventId': 'event-1', 'revision': 4,
                                       'role': 'user', 'purpose': 'answer',
                                       'span': {'start': 0, 'end': 14}}]
    assert observation['quality']['status'] == 'usable'
    assert await detect_problem(classifier(result), CATALOG, TURN, 'regular-1',
                                {('past', 'application')}) is None


@pytest.mark.asyncio
async def test_uncertain_asr_and_missing_or_cross_skill_classification_never_score():
    base = {'topicId': 'past', 'problemId': 'application', 'outcome': 'uncorrected_error',
            'asrUncertain': True, 'evidence': 'Yesterday I go', 'rationale': 'Possible error'}
    assert await detect_problem(classifier(base), CATALOG, TURN, 'regular-1') is None
    assert await detect_problem(classifier(dict.fromkeys(base, None)), CATALOG, TURN, 'regular-1') is None
    with pytest.raises(ValueError, match='^invalid_problem_detection_skill_mismatch$'):
        await detect_problem(classifier({**base, 'asrUncertain': False, 'topicId': 'travel'}),
                             CATALOG, TURN, 'regular-1')
    with pytest.raises(ValueError, match='^invalid_problem_detection_evidence_quote$'):
        await detect_problem(classifier({**base, 'asrUncertain': False, 'evidence': 'I went'}),
                             CATALOG, TURN, 'regular-1')


def regular_session():
    backend = SimpleNamespace(record_learning_observation=AsyncMock(),
                              command=AsyncMock(return_value={'ok': True}))
    socket = SimpleNamespace(send_json=AsyncMock())
    admitted = {'scope': 'scope', 'callSessionId': 'episode',
                'lesson': {'id': 'regular-1', 'kind': 'regular',
                           'starts_at': '2026-11-17T10:00:00+00:00', 'duration_minutes': 30}}
    s = RealtimeTrialSession(socket, backend, None, None, admitted, None, classifier({}))
    s.provider = SimpleNamespace(send=AsyncMock())
    return s


@pytest.mark.asyncio
async def test_observation_write_unknown_outcome_retries_same_receipt_then_reports_recovery():
    s = regular_session()
    s.backend.record_learning_observation.side_effect = BackendFailure('transport_outcome_unknown', 503)
    observation = {'lessonId': 'regular-1', 'anchorEventId': 'event-1',
                   'evidenceSlot': 'regular_free_conversation'}
    with pytest.raises(RuntimeError, match='learning_observation_recovery_required'):
        await s.save_learning_observation(observation)
    calls = s.backend.record_learning_observation.call_args_list
    assert len(calls) == 2 and calls[0].args == calls[1].args
    assert s.socket.send_json.call_args.args[0]['code'] == 'learning_observation_pending'
    assert s.action_events == []


@pytest.mark.asyncio
@pytest.mark.parametrize('model_result', ['{"topicId":', '{}'])
async def test_invalid_problem_analysis_preserves_the_saved_turn_and_continues_voice(model_result):
    s = regular_session()
    s.catalog = CATALOG
    s.model.complete_json.return_value = model_result
    s.remaining_seconds = lambda: 30 * 60
    s.append = AsyncMock(return_value={'eventId': 'event-1', 'sequence': 4})
    s.maybe_respond = AsyncMock()

    await s.provider_event({'type': 'conversation.item.input_audio_transcription.completed',
                            'item_id': 'turn-1', 'transcript': TURN['text']})

    s.append.assert_awaited_once()
    assert s.memory_sources['event-1']['body'] == TURN['text']
    s.backend.record_learning_observation.assert_not_awaited()
    s.socket.send_json.assert_not_awaited()
    s.maybe_respond.assert_awaited_once()


def test_regular_phase_is_guided_by_duration_and_preserves_trial_isolation():
    s = regular_session()
    s.remaining_seconds = lambda: 30 * 60
    assert s.regular_phase() == 'conversation'
    s.remaining_seconds = lambda: 15 * 60
    assert s.regular_phase() == 'practice'
    s.remaining_seconds = lambda: 2 * 60
    assert s.regular_phase() == 'wrap_up'


@pytest.mark.asyncio
async def test_live_proposal_requires_saved_learner_answer_before_sp09_decision():
    s = regular_session()
    s.proposal = {'topicId': 'past', 'problemId': 'application'}
    s.memory_sources['event-1'] = {'id': 'event-1', 'body': 'Yes, put it in my plan.'}
    call = {'call_id': 'decision-1', 'name': 'decide_learning_problem'}
    args = {'topicId': 'past', 'problemId': 'application', 'skillId': 'grammar',
            'decision': 'accepted', 'source': 'learner_response',
            'sourceId': 'missing-event', 'learnerWords': 'Yes, put it in my plan.',
            'reason': 'The learner agreed.'}
    await s.tool({**call, 'arguments': json.dumps(args)})
    s.backend.command.assert_not_awaited()
    assert json.loads(s.provider.send.call_args.args[0])['item']['output'] == (
        '{"ok": false, "error": "invalid_learning_decision"}')
    await s.tool({**call, 'call_id': 'saved-source', 'arguments': json.dumps({**args, 'sourceId': 'event-1'})})
    assert s.backend.command.call_args.args[2] == 'learning.roadmap.problem.decide'
    assert s.backend.command.call_args.args[3]['decisionId'] == 'event-1'
    assert ('past', 'application') in s.active_problem_pairs


@pytest.mark.asyncio
async def test_refusal_is_recorded_without_task_assignment_by_voice_layer():
    s = regular_session()
    s.proposal = {'topicId': 'past', 'problemId': 'application'}
    s.memory_sources['event-1'] = {'id': 'event-1', 'body': 'No thanks.'}
    await s.tool({'call_id': 'decision-2', 'name': 'decide_learning_problem', 'arguments': json.dumps({
        'topicId': 'past', 'problemId': 'application', 'skillId': 'grammar',
        'decision': 'declined', 'source': 'learner_response',
        'sourceId': 'event-1', 'learnerWords': 'No thanks.',
        'reason': 'The learner declined.'})})
    assert s.backend.command.call_args.args[3]['decision'] == 'declined'
    assert s.active_problem_pairs == set()
