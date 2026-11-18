import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.trial_observations import TrialObservationExtractor


def event(source_id, role, body, sequence=1, uncertainty=None):
    return {'eventId': 'raw-' + source_id, 'sourceEventId': source_id, 'sourceSequence': sequence,
            'lessonId': 'lesson', 'callSessionId': 'call', 'revision': 1, 'kind': 'transcript',
            'occurredAt': '2026-11-16T10:00:00Z', 'body': body, 'sourceContentAvailable': True,
            'metadata': {'role': role, 'finality': 'final', 'uncertaintySignals': uncertainty or []}}


def backend(events):
    return SimpleNamespace(
        trial_events=AsyncMock(return_value={'schemaVersion': 1,
            'lesson': {'trial_version': 'v5.5', 'status': 'completed', 'transcript_revision': 2},
            'events': events, 'nextOffset': None}),
        catalog=AsyncMock(return_value={'schemaVersion': 2, 'version': 'published',
            'topics': [{'id': 'grammar_topic', 'skillId': 'grammar', 'title': 'Grammar', 'cefr': 'A2'}],
            'problems': [{'id': 'grammar_application', 'skillId': 'grammar', 'title': 'Application'}]}))


def candidate(**changes):
    value = {'anchorEventId': 'user-1', 'quote': 'I work here', 'skillId': 'grammar',
             'topicId': 'grammar_topic', 'problemId': 'grammar_application',
             'outcome': 'independent_correct', 'support': 'independent', 'initialOutcome': 'correct',
             'contextEventId': 'assistant-1', 'hintEventId': None, 'rationale': 'Correct use'}
    return dict(value, **changes)


@pytest.mark.asyncio
async def test_trial_produces_scoped_observation_without_listening():
    b = backend([event('assistant-1', 'assistant', 'Where do you work?'), event('user-1', 'user', 'I work here')])
    model = SimpleNamespace(complete_json=AsyncMock(return_value=json.dumps({'observations': [candidate()]})))
    result = await TrialObservationExtractor(b, model).extract('scope', 'lesson', {'transcriptRevision': 2})
    observation = result['observations'][0]
    assert observation['skillId'] == 'grammar'
    assert observation['sources'] == [
        {'eventId': 'user-1', 'revision': 1, 'role': 'user', 'purpose': 'answer', 'span': {'start': 0, 'end': 11}},
        {'eventId': 'assistant-1', 'revision': 1, 'role': 'assistant', 'purpose': 'context'}]
    assert observation['route'] == 'assessment_only'
    assert observation['quality']['status'] == 'usable'
    assert 'NEVER listening' in model.complete_json.call_args.args[0]


@pytest.mark.asyncio
async def test_uncertain_source_and_missing_answer_do_not_create_false_evidence():
    b = backend([event('assistant-1', 'assistant', 'Talk to me'),
                 event('user-1', 'user', 'I work here', uncertainty=['provider-signal'])])
    model = SimpleNamespace(complete_json=AsyncMock(return_value=json.dumps({'observations': [candidate()]})))
    observation = (await TrialObservationExtractor(b, model).extract('scope', 'lesson', {'transcriptRevision': 2}))['observations'][0]
    assert observation['quality']['status'] == 'uncertain'
    assert observation['quality']['signals'][0]['reason'] == 'uncertain_asr'
    b = backend([event('assistant-1', 'assistant', 'Talk to me')])
    assert (await TrialObservationExtractor(b, model).extract('scope', 'lesson', {'transcriptRevision': 2}))['observations'] == []
    model.complete_json.assert_awaited_once()


@pytest.mark.asyncio
async def test_extractor_rejects_unowned_quote_and_listening():
    b = backend([event('user-1', 'user', 'I work here')])
    model = SimpleNamespace(complete_json=AsyncMock(return_value=json.dumps({'observations': [candidate(quote='not spoken')]})))
    with pytest.raises(ValueError, match='invalid_trial_evidence'):
        await TrialObservationExtractor(b, model).extract('scope', 'lesson', {'transcriptRevision': 2})
    model.complete_json.return_value = json.dumps({'observations': [candidate(skillId='listening')]})
    with pytest.raises(ValueError):
        await TrialObservationExtractor(b, model).extract('scope', 'lesson', {'transcriptRevision': 2})


@pytest.mark.asyncio
@pytest.mark.parametrize('bad_reference,reason,role', [
    ('missing-source', 'source_unavailable', 'unavailable'),
    ('user-1', 'wrong_source_role', 'user'),
    ('user-2', 'learner_anchor_reused', 'user'),
])
async def test_bad_assistant_reference_isolated_on_first_materialization(bad_reference, reason, role, caplog):
    b = backend([event('user-1', 'user', 'I work here'),
                 event('user-2', 'user', 'I worked there', sequence=2)])
    good = candidate(contextEventId=None)
    bad = candidate(anchorEventId='user-2', quote='I worked there', outcome='hinted_correct',
                    support='hinted', initialOutcome='error', contextEventId=None, hintEventId=bad_reference)
    model = SimpleNamespace(complete_json=AsyncMock(return_value=json.dumps({'observations': [bad, good]})))

    with caplog.at_level('INFO', logger='app.diagnostics'):
        result = await TrialObservationExtractor(b, model).extract('scope', 'lesson', {'transcriptRevision': 2})

    assert result['initialMaterializationOnly'] is True
    only, = result['observations']
    assert only['sources'] == [
        {'eventId': 'user-1', 'revision': 1, 'role': 'user', 'purpose': 'answer', 'span': {'start': 0, 'end': 11}},
    ]
    assert only['support'] == 'independent'
    logs = [json.loads(record.message.removeprefix('runtime_event ')) for record in caplog.records]
    assert logs == [{'service': 'ai', 'event': 'trial_observation_candidate_rejected', 'reason': reason,
                     'reference_purpose': 'hint', 'source_role': role}]
    model.complete_json.assert_awaited_once()
    assert model.complete_json.call_args.kwargs['retry'] is False


@pytest.mark.asyncio
async def test_all_invalid_assistant_references_fail_instead_of_returning_empty_analysis():
    b = backend([event('user-1', 'user', 'I work here')])
    model = SimpleNamespace(complete_json=AsyncMock(return_value=json.dumps({
        'observations': [candidate(contextEventId='missing-source')],
    })))
    with pytest.raises(ValueError, match='invalid_trial_evidence'):
        await TrialObservationExtractor(b, model).extract('scope', 'lesson', {'transcriptRevision': 2})
    model.complete_json.assert_awaited_once()


@pytest.mark.asyncio
async def test_corrected_source_keeps_slot_and_same_hint_can_supply_context():
    b = backend([event('assistant-1', 'assistant', 'Try the present tense'),
                 event('user-1', 'user', 'I works here', sequence=4)])
    hinted = candidate(quote='I works here', outcome='hinted_correct', support='hinted',
                       initialOutcome='error', contextEventId='assistant-1', hintEventId='assistant-1')
    model = SimpleNamespace(complete_json=AsyncMock(return_value=json.dumps({'observations': [hinted]})))
    first = (await TrialObservationExtractor(b, model).extract('scope', 'lesson', {'transcriptRevision': 2}))['observations'][0]
    assert [ref['purpose'] for ref in first['sources']] == ['answer', 'hint']
    b.trial_events.return_value['events'][1] = event('user-1', 'user', 'I work here', sequence=5)
    model.complete_json.return_value = json.dumps({'observations': [candidate()]})
    second = (await TrialObservationExtractor(b, model).extract('scope', 'lesson', {'transcriptRevision': 2}))['observations'][0]
    assert second['sources'][0]['revision'] == 5
    assert second['evidenceSlot'] == first['evidenceSlot']


@pytest.mark.asyncio
async def test_all_trial_skills_share_sp17a_contract_without_listening():
    b = backend([event('user-1', 'user', 'I work here')])
    candidates = [candidate(skillId=skill, topicId=None, problemId=None, contextEventId=None)
                  for skill in ('speaking', 'grammar', 'vocabulary')]
    model = SimpleNamespace(complete_json=AsyncMock(return_value=json.dumps({'observations': candidates})))
    observations = (await TrialObservationExtractor(b, model).extract(
        'scope', 'lesson', {'transcriptRevision': 2}))['observations']
    assert {item['skillId'] for item in observations} == {'speaking', 'grammar', 'vocabulary'}
    assert len({item['evidenceSlot'] for item in observations}) == 3
    assert all(item['route'] == 'assessment_only' and item['sources'][0]['eventId'] == 'user-1'
               for item in observations)
