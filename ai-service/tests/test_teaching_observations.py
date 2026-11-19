from types import SimpleNamespace
from unittest.mock import AsyncMock
import json

import pytest

from app.explanation_exercises import TeachingActionSession as Sp14
from app.listening_conversation import TeachingActionSession as Sp15
from app.teaching_observations import observation_from_action, observation_operation_id
from app.topic_knowledge_advisory import topic_knowledge_advisory
from tests.test_explanation_exercises import Model, answer, earlier, handoff as sp14_handoff, hint
from tests.test_listening_conversation import handoff as sp15_handoff, model, verdict


@pytest.mark.asyncio
async def test_sp14_real_exercise_proposals_preserve_identity_and_support():
    current = answer('answer-2')
    prior = [earlier(), hint()]
    action = Sp14(sp14_handoff(), Model(previous_wrong=True))
    proposal = (await action.respond(current, prior))['event']
    row = observation_from_action(proposal, {**current, 'occurredAt': '2026-11-17T00:00:00Z'}, prior)
    assert row['outcome'] == 'hinted_correct'
    assert row['support'] == 'hinted'
    assert row['taskId'] == 'task-1'
    assert row['stageRef'] == {'planId': 'plan-1', 'planRevision': 2, 'stageId': 'stage-1'}
    assert row['actionId'] == 'content-1:3'
    assert row['sources'][-1]['eventId'] == 'hint-1'
    assert row['occurredAt'] == '2026-11-17T00:00:00Z'
    assert observation_operation_id(row) == observation_operation_id(row.copy())


@pytest.mark.asyncio
async def test_sp14_self_correction_and_asr_remain_distinct():
    corrected = (await Sp14(sp14_handoff(), Model(previous_wrong=True)).respond(answer('answer-2'), [earlier()]))['event']
    row = observation_from_action(corrected, {**answer('answer-2'), 'occurredAt': '2026-11-17T00:00:00Z'}, [earlier()])
    assert row['outcome'] == 'self_corrected_error'
    assert row['sources'][-1]['purpose'] == 'correction'
    uncertain = (await Sp14(sp14_handoff(), Model('incorrect')).respond(answer(asrUncertain=True), []))['event']
    row = observation_from_action(uncertain, {**answer(), 'occurredAt': '2026-11-17T00:00:00Z'}, [])
    assert row['quality']['status'] == 'uncertain'
    assert row['outcome'] == 'unresolved'


@pytest.mark.asyncio
async def test_sp15_real_listening_proposal_uses_same_contract():
    current = answer('meaning-1', 'Я опоздал на поезд.')
    action = Sp15(sp15_handoff(), model(verdict()))
    action.phase = 'meaning'
    proposal = (await action.respond(current, []))['event']
    row = observation_from_action(proposal, {**current, 'occurredAt': '2026-11-17T00:00:00Z'}, [])
    assert row['outcome'] == 'independent_correct'
    assert row['method'] == 'exposure'
    assert row['actionId'] == 'content:3'
    assert row['sources'] == [{'eventId': 'meaning-1', 'revision': 1, 'role': 'user', 'purpose': 'answer'}]


@pytest.mark.asyncio
async def test_sp15_hint_asr_and_repeated_answer_boundaries():
    current = answer('heard-2', 'Я опоздал на поезд.')
    hint_turn = {'eventId': 'hint-1', 'revision': 2, 'text': 'The train left.',
                 'role': 'assistant', 'purpose': 'hint'}
    helped = Sp15(sp15_handoff(), model(verdict(support='hinted')))
    helped.phase = 'meaning'
    proposal = (await helped.respond(current, [hint_turn]))['event']
    row = observation_from_action(proposal, {**current, 'occurredAt': '2026-11-17T00:00:00Z'}, [hint_turn])
    assert row['outcome'] == 'hinted_correct' and row['sources'][-1]['purpose'] == 'hint'
    uncertain = Sp15(sp15_handoff(), model(verdict(asr=True)))
    proposal = (await uncertain.respond(answer('heard-3'), []))['event']
    row = observation_from_action(proposal, {**answer('heard-3'), 'occurredAt': '2026-11-17T00:00:00Z'}, [])
    assert row['outcome'] == 'unresolved' and row['quality']['status'] == 'uncertain'
    repeated = await helped.respond(answer('heard-4', current['text']), [
        {'eventId': 'heard-2', 'revision': 1, 'text': current['text'], 'role': 'user', 'purpose': 'answer'}])
    assert repeated['event'] is None


@pytest.mark.asyncio
async def test_sp15_explicit_self_correction_keeps_original_answer():
    previous = {'eventId': 'wrong-1', 'revision': 1, 'text': 'Wrong meaning',
                'role': 'user', 'purpose': 'answer'}
    result = json.loads(verdict())
    result['selfCorrected'] = True
    current = answer('fixed-2', 'Я опоздал на поезд.')
    action = Sp15(sp15_handoff(), model(json.dumps(result)))
    proposal = (await action.respond(current, [previous]))['event']
    row = observation_from_action(proposal, {**current, 'occurredAt': '2026-11-17T00:00:00Z'}, [previous])
    assert row['outcome'] == 'self_corrected_error'
    assert row['initialOutcome'] == 'error'
    assert row['sources'][-1]['eventId'] == 'wrong-1'


@pytest.mark.asyncio
async def test_topic_advisory_reads_history_without_classifying():
    page = {'items': [{'id': 'observation-1', 'revision': 2,
                       'document': {'lessonId': 'lesson-1', 'outcome': 'independent_correct',
                                    'quality': {'status': 'usable'}}, 'evidenceUsable': True,
                       'sources': [{'eventId': 'turn-1'}]}], 'nextOffset': None}
    backend = SimpleNamespace(learning_observations=AsyncMock(return_value=page))
    result = await topic_knowledge_advisory(backend, 'scope', 'topic-1')
    assert result['decision'] == 'insufficient_evidence' and result['value'] is None
    assert result['evidence'][0]['revision'] == 2
    backend.learning_observations.assert_awaited_once_with('scope', topic_id='topic-1', offset=0)
