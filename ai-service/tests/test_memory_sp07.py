import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.context_builder import ContextBuilder
from app.memory_context import MemorySelector, observation_patterns
from app.memory_tools import execute_memory
from app.trial_policy import TrialAnalyzer


def fact(key, value, **extra):
    return dict(category='personal', fact_key=key, value=value, kind='user_report', revision=1,
                source_event_id='s1', **extra)


def snapshot(facts=(), observations=()):
    return dict(version=1, student=dict(id='s', tutor_id='sasha', tutor_version=1, version=1, data_epoch=1),
                facts=list(facts), observations=list(observations), events=[], candidates=[{'value': 'hidden inference'}])


@pytest.mark.asyncio
async def test_selection_keeps_core_and_restrictions_without_leaking_unrelated_facts_or_candidates():
    data = snapshot([fact('work', 'private work', mention_policy='context_only'),
                     fact('name', 'Alex'), fact('leisure', 'hiking'), fact('pet', 'cat')])
    backend = SimpleNamespace(snapshot=AsyncMock(return_value=data))
    model = SimpleNamespace(complete_json=AsyncMock(return_value='{"facts":[2],"observations":[]}'))
    ctx = await ContextBuilder(backend, selector=MemorySelector(model)).build('scope', 'text')
    assert [f['fact_key'] for f in ctx.data['facts']] == ['name', 'leisure']
    assert ctx.data['memoryRestrictions'] == [{'key': 'work', 'policy': 'context_only'}]
    assert ctx.data['memorySelection']['partial']
    assert 'private work' not in ctx.instructions('text')
    assert 'hidden inference' not in ctx.instructions('text')
    assert model.complete_json.call_args.kwargs['retry'] is False


@pytest.mark.asyncio
async def test_invalid_selection_preserves_core_and_reports_degraded_selection_without_retry():
    model = SimpleNamespace(complete_json=AsyncMock(return_value='{"facts":[900],"observations":[]}'))
    backend = SimpleNamespace(snapshot=AsyncMock(return_value=snapshot([fact('name', 'Alex'), fact('work', 'artist')])))
    ctx = await ContextBuilder(backend, selector=MemorySelector(model)).build('scope', 'voice', 'lesson')
    assert [f['fact_key'] for f in ctx.data['facts']] == ['name']
    assert ctx.data['memorySelection']['status'] == 'unavailable_core_only'
    model.complete_json.assert_awaited_once()


def test_repeated_observation_counts_distinct_lessons_and_sources_not_copies():
    one = dict(observation_key='past', lesson_id='l1', source_event_id='s1', review={'status': 'disputed'})
    two = dict(one, lesson_id='l2', source_event_id='s2')
    group = observation_patterns([one, one, two])[0]
    assert group['lessonCount'] == 2
    assert group['sourceIds'] == ['s1', 's2']
    assert group['review']['status'] == 'disputed'


@pytest.mark.asyncio
async def test_tools_require_current_evidence_and_mutation_replay_keeps_identity():
    backend = SimpleNamespace(command=AsyncMock(return_value={'updated': True}))
    args = dict(category='personal', key='work', value='designer', kind='user_report',
                expectedVersion=1, evidence='designer')
    with pytest.raises(ValueError):
        await execute_memory(backend, 'scope', 'req', {'id': 's', 'body': 'hello'}, 'record_memory', args)
    backend.command.assert_not_awaited()
    for _ in range(2):
        await execute_memory(backend, 'scope', 'req', {'id': 's', 'body': 'I am a designer'}, 'record_memory', args)
    assert backend.command.call_args_list[0] == backend.command.call_args_list[1]


@pytest.mark.asyncio
async def test_lesson_analyzer_returns_direct_personal_fact_with_exact_evidence():
    payload = dict(status='evaluated', observations=[], facts=[dict(key='work', value='designer',
                   sourceId='s', evidence='I am a designer', expectedVersion=1)])
    model = SimpleNamespace(complete_json=AsyncMock(return_value=json.dumps(payload)))
    ctx = SimpleNamespace(data={'sourceEvents': [{'id': 's', 'role': 'user', 'body': 'I am a designer'}]})
    assert (await TrialAnalyzer(model).evaluate(ctx, {}))['facts'] == payload['facts']
    payload['facts'][0]['evidence'] = 'invented quote'
    model.complete_json.return_value = json.dumps(payload)
    with pytest.raises(ValueError):
        await TrialAnalyzer(model).evaluate(ctx, {})


@pytest.mark.asyncio
async def test_long_lesson_extracts_early_facts_and_reconciles_latest_statement():
    sources = [{'id': f's{i}', 'role': 'user', 'body': f'report {i}'} for i in range(85)]
    seen = []
    async def complete(system, data, **kwargs):
        events = json.loads(data)['sourceEvents']
        seen.extend(e['id'] for e in events)
        return json.dumps(dict(status='evaluated', observations=[], candidates=[], facts=[dict(
            key='work', value=e['body'], sourceId=e['id'], evidence=e['body'], expectedVersion=0)
            for e in [events[-1]]] + ([dict(key='leisure', value='report 0', sourceId='s0', evidence='report 0', expectedVersion=0)]
                                     if events[0]['id'] == 's0' else [])))
    result = await TrialAnalyzer(SimpleNamespace(complete_json=complete)).evaluate(SimpleNamespace(data={'sourceEvents': sources}), {})
    assert seen == [s['id'] for s in sources]
    assert {f['key']: f['value'] for f in result['facts']} == {'work': 'report 84', 'leisure': 'report 0'}


@pytest.mark.asyncio
async def test_budget_exhaustion_in_selection_is_not_hidden_by_fallback():
    error = RuntimeError('budget exhausted')
    error.status_code = 402
    model = SimpleNamespace(complete_json=AsyncMock(side_effect=error))
    backend = SimpleNamespace(snapshot=AsyncMock(return_value=snapshot([fact('work', 'artist')])))
    with pytest.raises(RuntimeError, match='budget exhausted'):
        await ContextBuilder(backend, selector=MemorySelector(model)).build('scope', 'text')
    model.complete_json.assert_awaited_once()
