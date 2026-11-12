"""SP07 production adapters over local Ktor/PostgreSQL. No paid models or deliveries."""
import json
import os
import time
from types import SimpleNamespace
from unittest.mock import AsyncMock
from uuid import uuid4

import httpx
import pytest

from app.context_builder import ContextBuilder
from app.foundation_client import BackendFailure
from app.foundation_tutor import FoundationTutor
from app.memory_context import MemorySelector
from app.memory_tools import execute_memory
from app.trial_policy import TrialAnalyzer
from app.tutor import TutorRequest
from integration.test_foundation_http import actor, now
from integration.test_sp06_roadmap_http import Flow


@pytest.mark.asyncio
async def test_voice_facts_policy_and_disputed_observations_reach_text_and_planner():
    async with httpx.AsyncClient() as http:
        f = Flow(http, os.environ['SP00_TEST_URL'], os.environ['SP00_TEST_TOKEN'])
        await f.init()
        lesson = await f.lesson('trial')
        await f.run('lesson.prepare')
        admitted = await f.open(lesson)
        frozen = admitted['lesson']['execution_snapshot']
        text = 'I work as a designer. Do not discuss my work.'
        event = await f.command('voice.append', {'lessonId': lesson['id'], 'callSessionId': admitted['callSessionId'],
            'sourceId': uuid4().hex, 'role': 'user', 'body': text, 'occurredAt': now()}, admitted['scope'])
        source = {'id': event['eventId'], 'body': text}
        await execute_memory(f.backend, admitted['scope'], 'record', source, 'record_memory', dict(
            category='personal', key='work', expectedVersion=0, value='designer', kind='user_report', evidence='designer'))
        await execute_memory(f.backend, admitted['scope'], 'restrict', source, 'set_memory_policy', dict(
            category='personal', key='work', expectedVersion=1, mentionPolicy='context_only', validUntil=None,
            evidence='Do not discuss my work'))
        assert (await f.backend.snapshot(admitted['scope'], 'voice', lesson['id']))['lesson']['execution_snapshot'] == frozen
        await f.end(admitted)
        await f.run('lesson.evaluate')
        denial = await actor(http, f.root, f.token, f.subject, requestId=uuid4().hex,
                             text='I was tired', occurredAt=now())
        await execute_memory(f.backend, f.operator, 'review', {'id': denial['input']['event_id'], 'body': 'I was tired'},
            'review_observation', dict(key='reasons', expectedVersion=0, evidence='tired', status='disputed', explanation='Was tired'))
        for purpose in ('text', 'learning'):
            ctx = await f.contexts.build(f.operator, purpose)
            assert ctx.data['facts'][0]['mention_policy'] == 'context_only'
            assert ctx.data['observationPatterns'][0]['review']['status'] == 'disputed'
        search = await execute_memory(f.backend, f.operator, 'read', None, 'search_memory', dict(query='reasons', offset=0))
        assert search['observations'][0]['review']['explanation'] == 'Was tired'
        model = SimpleNamespace(complete_json=AsyncMock(return_value='{"facts":[],"observations":[0]}'))
        selected = await ContextBuilder(f.backend, selector=MemorySelector(model)).build(f.operator, 'text')
        assert selected.data['memoryRestrictions'][0]['key'] == 'work'
        assert not selected.data['facts']
        assert selected.data['observations'][0]['review']['status'] == 'disputed'


@pytest.mark.asyncio
async def test_post_lesson_personal_extraction_respects_concurrent_correction_and_sources():
    async with httpx.AsyncClient() as http:
        f = Flow(http, os.environ['SP00_TEST_URL'], os.environ['SP00_TEST_TOKEN'])
        await f.init()
        lesson = await f.lesson('trial')
        await f.run('lesson.prepare')
        admitted = await f.open(lesson)
        speech = 'I am a designer and I like hiking'
        event = await f.command('voice.append', {'lessonId': lesson['id'], 'callSessionId': admitted['callSessionId'],
            'sourceId': uuid4().hex, 'role': 'user', 'body': speech, 'occurredAt': now()}, admitted['scope'])
        await f.end(admitted)
        job = await f.claim('lesson.evaluate')
        model = SimpleNamespace(complete_json=AsyncMock(return_value=json.dumps(dict(status='evaluated', observations=[], facts=[
            dict(key='work', value='designer', sourceId=event['eventId'], evidence='designer', expectedVersion=0),
            dict(key='leisure', value='hiking', sourceId=event['eventId'], evidence='hiking', expectedVersion=0)],
            candidates=[dict(key='likes_creativity', value='enjoys creative tasks', sourceId=event['eventId'])]))))
        f.engine.analyzer = TrialAnalyzer(model)
        scope, result = await f.compute(job)
        await f.fact('work', 'teacher', 0)  # A newer Telegram correction while analysis was in flight.
        forged = json.loads(json.dumps(result))
        forged['facts'][1]['evidence'] = 'invented'
        with pytest.raises(BackendFailure, match='invalid_evidence'):
            await f.apply(job, forged, scope)
        operation = uuid4().hex
        await f.apply(job, result, scope, operation)
        await f.apply(job, result, scope, operation)
        facts = (await f.backend.snapshot(f.operator, 'text'))['facts']
        assert {x['fact_key']: x['value'] for x in facts} == {'work': 'teacher', 'leisure': 'hiking'}
        assert all(x['revision'] == 1 for x in facts)
        # Evidence quotes are not copied into the long-lived evaluation result.
        assert 'facts' not in (await f.backend.snapshot(f.operator, 'text'))['trialResult']['result']
        voice = await actor(http, f.root, f.token, f.subject, 'voice', lessonId=lesson['id'])
        assert len((await f.contexts.build(voice['scope'], 'voice', lesson['id'])).data['facts']) == 2
        candidates = await f.command('memory.discuss', {'query': 'creativity', 'offset': 0})
        assert len(candidates['candidates']) == 1
        assert 'candidates' not in (await f.backend.snapshot(f.operator, 'text'))['trialResult']['result']


@pytest.mark.asyncio
async def test_text_forgetting_finishes_durably_and_new_report_does_not_revive_old_history():
    async with httpx.AsyncClient() as http:
        f = Flow(http, os.environ['SP00_TEST_URL'], os.environ['SP00_TEST_TOKEN'])
        await f.init()
        await f.fact('work', 'designer', 0)
        # Operator-created input fixtures are marked handled before beginning a real text run.
        # Use a separate fresh student whose fact is recorded through the real tutor path.
        subject = str(time.time_ns())
        request = uuid4().hex
        text = await actor(http, f.root, f.token, subject, 'text', requestId=request, text='I am a designer', occurredAt=now())
        calls = [{'role': 'assistant', 'tool_calls': [{'id': 'save', 'type': 'function', 'function': {
            'name': 'record_memory', 'arguments': json.dumps(dict(category='personal', key='work', expectedVersion=0,
                value='designer', kind='user_report', evidence='designer'))}}]}, {'content': '{"reply":"Понял."}'}]
        tutor = FoundationTutor(f.backend, SimpleNamespace(complete=AsyncMock(side_effect=calls)), f.contexts)
        await tutor.respond(TutorRequest(sessionId='tg-' + subject, requestId=request, text='I am a designer', receivedAt=time.time()), text['scope'])
        request = uuid4().hex
        deleted_at = now()
        deletion = await actor(http, f.root, f.token, subject, 'text', requestId=request, text='Forget my work', occurredAt=deleted_at)
        model = SimpleNamespace(complete=AsyncMock(return_value={'role': 'assistant', 'tool_calls': [{'id': 'delete', 'type': 'function', 'function': {
            'name': 'forget_memory', 'arguments': json.dumps(dict(targets=[dict(category='personal', key='work', expectedVersion=1)], evidence='Forget my work'))}}]}))
        tutor = FoundationTutor(f.backend, model, f.contexts)
        payload = TutorRequest(sessionId='tg-' + subject, requestId=request, text='Forget my work', receivedAt=time.time())
        answer = await tutor.respond(payload, deletion['scope'])
        model.complete.assert_awaited_once()
        retry = await actor(http, f.root, f.token, subject, 'text', requestId=request, text='Forget my work', occurredAt=deleted_at)
        assert await tutor.respond(payload, retry['scope']) == answer
        operator = (await actor(http, f.root, f.token, subject))['scope']
        state = await f.backend.snapshot(operator, 'text')
        assert not state['facts'] and not state['events']
        request = uuid4().hex
        fresh = await actor(http, f.root, f.token, subject, requestId=request, text='I am a teacher', occurredAt=now())
        await execute_memory(f.backend, operator, request, {'id': fresh['input']['event_id'], 'body': 'I am a teacher'},
            'record_memory', dict(category='personal', key='work', expectedVersion=0, value='teacher', kind='user_report', evidence='teacher'))
        assert (await f.backend.snapshot(operator, 'text'))['facts'][0]['value'] == 'teacher'


@pytest.mark.asyncio
async def test_bulk_forgetting_rolls_back_if_any_target_version_is_wrong():
    async with httpx.AsyncClient() as http:
        f = Flow(http, os.environ['SP00_TEST_URL'], os.environ['SP00_TEST_TOKEN'])
        await f.init()
        await f.fact('work', 'designer', 0)
        await f.fact('leisure', 'hiking', 0)
        source = await actor(http, f.root, f.token, f.subject, requestId=uuid4().hex,
                             text='Forget both', occurredAt=now())
        args = dict(sourceId=source['input']['event_id'], evidence='Forget both', targets=[
            dict(category='personal', key='work', expectedVersion=1),
            dict(category='personal', key='leisure', expectedVersion=99)])
        with pytest.raises(BackendFailure, match='version_conflict'):
            await f.command('memory.forget_requested', args)
        assert len((await f.backend.snapshot(f.operator, 'text'))['facts']) == 2
        args['targets'][1]['expectedVersion'] = 1
        assert (await f.command('memory.forget_requested', args))['contextRevoked']
        fresh = (await actor(http, f.root, f.token, f.subject))['scope']
        state = await f.backend.snapshot(fresh, 'text')
        assert not state['facts'] and not state['events']
