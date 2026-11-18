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
async def test_trial_produces_scoped_grammar_observation():
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
    assert 'Do not infer CEFR, Topic Knowledge' in model.complete_json.call_args.args[0]


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
async def test_extractor_rejects_unowned_quote_and_listening_catalog_claim():
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
    [record] = logs
    assert (record['service'], record['event'], record['reason_code']) == (
        'ai', 'trial_observation_candidate_rejected', reason)
    assert (record['reference_purpose'], record['source_role']) == ('hint', role)
    assert record['failure_class'] == 'expected'
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
async def test_classified_trial_skills_share_sp17a_contract():
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


def spoken(source_id, role, body, sequence, playback=True, session='call', uncertainty=None):
    row = event(source_id, role, body, sequence, uncertainty)
    row.update(occurredAt=f'2026-11-16T10:00:{sequence:02d}Z', callSessionId=session)
    if role == 'assistant':
        row['metadata'].update(playbackComplete=playback, playedMs=1500)
    return row


def listening_events():
    return [
        {'kind': 'phase', 'occurredAt': '2026-11-16T10:00:01Z', 'metadata': {'phase': 'conversation'}},
        spoken('assistant-1', 'assistant', 'If you could revisit one place, where would you go and why?', 2),
        spoken('user-1', 'user', 'Could you say that more simply?', 3),
        spoken('hint-1', 'assistant', 'What place would you like to visit again? Why?', 4),
        spoken('user-2', 'user', 'I would go back to Rome to see my old friends.', 5),
        spoken('assistant-2', 'assistant', 'What do your friends most enjoy doing in Rome?', 6),
        spoken('user-3', 'user', 'They love taking long walks along the Tiber.', 7),
    ]


@pytest.mark.asyncio
async def test_listening_uses_played_prompt_learner_quote_and_actual_repeat():
    request = candidate(skillId='listening', topicId=None, problemId=None, quote='Could you say that more simply?',
                        outcome='unresolved', support='unknown', initialOutcome='unknown')
    helped = candidate(skillId='listening', topicId=None, problemId=None, anchorEventId='user-2',
                       quote='I would go back to Rome', outcome='hinted_correct', support='hinted',
                       initialOutcome='unknown', hintEventId='hint-1')
    independent = candidate(skillId='listening', topicId=None, problemId=None, anchorEventId='user-3',
                            quote='They love taking long walks', contextEventId='assistant-2')
    model = SimpleNamespace(complete_json=AsyncMock(return_value=json.dumps({
        'observations': [request, helped, independent],
    })))
    result = await TrialObservationExtractor(backend(listening_events()), model).extract(
        'scope', 'lesson', {'transcriptRevision': 2})
    observed_request, observed_help, observed_independent = result['observations']
    assert observed_request['outcome'] == 'unresolved'
    assert [(ref['eventId'], ref['revision'], ref['purpose']) for ref in observed_help['sources']] == [
        ('user-2', 5, 'answer'), ('assistant-1', 2, 'context'), ('hint-1', 4, 'hint'),
    ]
    assert observed_help['initialOutcome'] == 'unknown'
    assert observed_independent['outcome'] == 'independent_correct'
    assert all(row['topicId'] is None and row['problemId'] is None and row['route'] == 'assessment_only'
               and row['quality']['confidence'] is None for row in result['observations'])
    model_input = json.loads(model.complete_json.call_args.args[1])
    assert model_input['events'][-1]['listeningPromptId'] == 'assistant-2'


@pytest.mark.asyncio
@pytest.mark.parametrize('problem', ['wrong_prompt', 'cross_session', 'not_played', 'partial', 'outside_phase', 'error'])
async def test_listening_rejects_unproven_pairing_and_deficit_claims(problem):
    events = listening_events()
    row = candidate(skillId='listening', topicId=None, problemId=None, anchorEventId='user-3',
                    quote='They love taking long walks', contextEventId='assistant-2')
    if problem == 'wrong_prompt':
        row['contextEventId'] = 'assistant-1'
    elif problem == 'cross_session':
        events[-1]['callSessionId'] = 'reconnect'
    elif problem == 'not_played':
        events[-2]['metadata']['playbackComplete'] = False
    elif problem == 'partial':
        events[-2]['metadata']['finality'] = 'partial'
    elif problem == 'outside_phase':
        events[0]['metadata']['phase'] = 'wrapup'
    else:
        row.update(outcome='uncorrected_error', initialOutcome='error')
    model = SimpleNamespace(complete_json=AsyncMock(return_value=json.dumps({'observations': [row]})))
    with pytest.raises(ValueError, match='invalid_trial_evidence'):
        await TrialObservationExtractor(backend(events), model).extract('scope', 'lesson', {'transcriptRevision': 2})


@pytest.mark.asyncio
async def test_listening_retains_explicit_asr_uncertainty():
    events = listening_events()
    events[-1]['metadata']['uncertaintySignals'] = ['provider-signal']
    model = SimpleNamespace(complete_json=AsyncMock(return_value=json.dumps({'observations': [
        candidate(skillId='listening', topicId=None, problemId=None, anchorEventId='user-3',
                  quote='They love taking long walks', contextEventId='assistant-2'),
    ]})))
    result = await TrialObservationExtractor(backend(events), model).extract('scope', 'lesson', {'transcriptRevision': 2})
    assert result['observations'][0]['quality'] == {'status': 'uncertain', 'confidence': None, 'signals': [
        {'reason': 'uncertain_asr', 'component': 'trial-call-source', 'sourceEventId': 'user-3'},
    ]}


def test_compute_handoff_passes_listening_sources_to_assessment_without_promotion():
    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    from app.foundation_api import install_foundation_api
    from app.learning_engine import LearningEngine
    from app.trial_assessment import TrialAssessment
    from tests.test_trial_assessment import backend as assessment_backend

    raw_backend = backend(listening_events())
    extract_model = SimpleNamespace(complete_json=AsyncMock(return_value=json.dumps({'observations': [
        candidate(skillId='listening', topicId=None, problemId=None, anchorEventId='user-3',
                  quote='They love taking long walks', contextEventId='assistant-2'),
    ]})))
    assessment = assessment_backend([])
    assessment.trial_events.return_value['lesson']['transcript_revision'] = 2
    assessment.catalog.return_value['version'] = 'published'
    assessment_model = SimpleNamespace(complete_json=AsyncMock())
    app = FastAPI()
    install_foundation_api(app, None, LearningEngine(
        raw_backend, None, trial_observations=TrialObservationExtractor(raw_backend, extract_model),
        trial_assessment=TrialAssessment(assessment, assessment_model),
    ))
    path = '/internal/foundation/compute'
    headers = {'X-Execution-Scope': 'test-scope'}
    job = {'schema_version': 1, 'target_id': 'lesson', 'input_refs': {'transcriptRevision': 2}}
    with TestClient(app) as client:
        extracted = client.post(path, headers=headers, json=dict(job, kind='trial.observe'))
        assert extracted.status_code == 200
        document, = extracted.json()['observations']
        assessment.learning_observations.return_value['items'] = [
            {'id': 'stored', 'revision': 1, 'status': 'active', 'evidenceUsable': True, 'document': document},
        ]
        job['input_refs']['catalogVersion'] = 'published'
        reviewed = client.post(path, headers=headers, json=dict(job, kind='trial.assess'))

    assert reviewed.status_code == 200
    result = reviewed.json()
    assert result['observationRefs'] == [{'id': 'stored', 'revision': 1}]
    assert result['levels']['listening']['level'] == result['levels']['overall']['level'] == 'Unknown'
    assert result['topics'] == []
    assessment_model.complete_json.assert_not_awaited()
