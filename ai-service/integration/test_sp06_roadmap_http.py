"""Real Ktor/PG + production Python adapters; only model content is scripted, no paid calls."""
import hashlib
import hmac
import json
import os
import time
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace
from urllib.parse import urlencode
from uuid import uuid4

import httpx
import pytest

from app.context_builder import ContextBuilder
from app.foundation_client import BackendFailure, FoundationClient
from app.learning_engine import LearningEngine
from app.regular_lesson import LessonPlanner
from app.roadmap import RoadmapPlanner
from app.trial_policy import TrialAnalyzer
from integration.test_foundation_http import actor, now


class ScriptedContent:
    def __init__(self):
        self.contexts = []

    async def complete_json(self, system, data, **kwargs):
        data = json.loads(data)
        self.contexts.append((kwargs['operation'], data))
        assert kwargs['response_format']['json_schema']['strict'] is True
        assert 'max_tokens' not in kwargs
        if kwargs['operation'] == 'trial_evaluation':
            return json.dumps({'status': 'evaluated', 'observations': [{
                'key': 'reasons', 'dimension': 'autonomy', 'content': 'Needs a prompt to explain a choice',
                'uncertainty': 'tentative', 'sourceId': data['sourceEvents'][0]['id']}]})
        if kwargs['operation'] == 'roadmap_prepare':
            return json.dumps({'decision': 'revise', 'reason': 'Practise giving reasons in work conversations',
                'steps': [{'id': 'reasons', 'outcome': 'Explain a choice at work',
                           'tactic': 'Offer a short cue, then withdraw help' if data['roadmap'] else 'Start with familiar work choices'}],
                'sourceIds': list(dict.fromkeys(o['source_event_id'] for o in data['observations']))})
        assert kwargs['operation'] == 'regular_lesson_prepare'
        assert data['roadmap']['steps'][0]['outcome'] == 'Explain a choice at work'
        return json.dumps({'objective': 'Explain a choice with a reason', 'topic': 'Choosing a team activity',
            'strategy': 'Use known work context; offer one short cue; explore alternatives or priorities. No invented facts.'})


class Flow:
    def __init__(self, http, root, token):
        self.http, self.root, self.token = http, root, token
        self.subject = str(time.time_ns())
        self.backend = FoundationClient(root, token, http)
        self.contexts = ContextBuilder(self.backend)
        self.model = ScriptedContent()
        self.engine = LearningEngine(self.backend, self.contexts, analyzer=TrialAnalyzer(self.model),
            planner=LessonPlanner(self.model), roadmap=RoadmapPlanner(self.model))

    async def init(self):
        self.operator = (await actor(self.http, self.root, self.token, self.subject))['scope']
        await self.fact('goal', 'Work meetings', 0, 'learning')

    async def command(self, action, args, scope=None, operation=None):
        return await self.backend.command(scope or self.operator, operation or uuid4().hex, action, args)

    async def fact(self, key, value, revision, category='personal'):
        source = await actor(self.http, self.root, self.token, self.subject, 'operator',
            requestId=uuid4().hex, text=value, occurredAt=now())
        return await self.command('memory.record', {'key': key, 'value': value, 'category': category,
            'kind': 'user_report', 'expectedVersion': revision, 'sourceId': source['input']['event_id'], 'evidence': value})

    async def lesson(self, kind='regular', future=False):
        return await self.command('lesson.create', {'kind': kind, 'launchMode': 'executable',
            'startsAt': (datetime.now(timezone.utc) + timedelta(hours=1)).isoformat() if future else now(),
            'timezone': 'UTC', 'durationMinutes': 30})

    async def claim(self, kind):
        return (await self.command('job.claim', {'kind': kind}))['job']

    async def compute(self, job):
        response = await self.http.post(self.root + '/test/job-scope', headers={'X-Test-Token': self.token}, json=job)
        response.raise_for_status()
        scope = response.json()['scope']
        result = await self.engine.compute(scope, job)
        return scope, result

    async def apply(self, job, result, scope=None, operation=None):
        return await self.command('job.apply', {'jobId': job['id'], 'fence': job['fence'], 'result': result}, scope, operation)

    async def run(self, kind):
        job = await self.claim(kind)
        assert job, kind
        scope, result = await self.compute(job)
        operation = uuid4().hex
        first = await self.apply(job, result, scope, operation)
        assert first == await self.apply(job, result, scope, operation)
        assert first['status'] == 'succeeded'
        return job

    async def open(self, lesson):
        fields = {'auth_date': str(int(time.time())), 'user': json.dumps({'id': int(self.subject)})}
        secret = hmac.new(b'WebAppData', b'123:sp02-local-only', hashlib.sha256).digest()
        fields['hash'] = hmac.new(secret, '\n'.join(f'{k}={v}' for k, v in sorted(fields.items())).encode(), hashlib.sha256).hexdigest()
        return await self.backend._request('POST', '/trial/access', '', json={
            'initData': urlencode(fields), 'action': 'open', 'lessonId': lesson['id'], 'attemptId': uuid4().hex})

    async def say(self, admitted):
        return await self.command('voice.append', {'lessonId': admitted['lesson']['id'],
            'callSessionId': admitted['callSessionId'], 'sourceId': uuid4().hex, 'role': 'user',
            'body': 'I choose a quiet team activity because I can talk.', 'occurredAt': now()}, admitted['scope'])

    async def end(self, admitted, reason='natural_goodbye'):
        return await self.command('voice.end', {'lessonId': admitted['lesson']['id'],
            'callSessionId': admitted['callSessionId'], 'reason': reason}, admitted['scope'])

    async def trial(self):
        trial = await self.lesson('trial')
        await self.run('lesson.prepare')
        admitted = await self.open(trial)  # Free trial, no entitlement.
        await self.say(admitted)
        await self.end(admitted)
        await self.run('lesson.evaluate')
        return trial

    async def pay(self):
        await self.command('access.confirm_payment', {'provider': 'local-test', 'externalId': uuid4().hex,
            'validFrom': now(), 'validUntil': (datetime.now(timezone.utc) + timedelta(days=1)).isoformat()})


@pytest.mark.asyncio
async def test_trial_roadmap_plan_voice_accumulated_observations_revision_and_corrections():
    async with httpx.AsyncClient(timeout=20) as http:
        f = Flow(http, os.environ['SP00_TEST_URL'], os.environ['SP00_TEST_TOKEN'])
        await f.init()
        await f.trial()
        await f.run('roadmap.prepare')
        roadmap = (await f.contexts.build(f.operator, 'text')).data['roadmap']
        assert roadmap['revision'] == 1 and roadmap['sources']
        regular = await f.lesson()
        future = await f.lesson(future=True)
        await f.run('lesson.prepare')
        await f.run('lesson.prepare')
        with pytest.raises(BackendFailure, match='subscription_required'):
            await f.open(regular)
        await f.pay()
        admitted = await f.open(regular)
        frozen = admitted['lesson']['execution_snapshot']
        assert frozen['plan']['roadmap_revision'] == 1
        context = await f.contexts.build(admitted['scope'], 'voice', regular['id'])
        assert context.data['roadmap'] == roadmap
        next_lesson = (await f.command('lesson.next', {}, admitted['scope']))['lesson']
        assert next_lesson['id'] == future['id']
        assert next_lesson['preparedTopic'] == 'Choosing a team activity'
        await f.say(admitted)
        assert (await f.command('lesson.next', {}, admitted['scope']))['lesson']['preparedTopic'] is None
        await f.end(admitted, 'technical_disconnect')
        await f.fact('work', 'I work in design', 0)
        # A single trial cannot cause adaptation; preserve the route while acknowledging no new evidence.
        job = await f.claim('roadmap.prepare')
        scope, result = await f.compute(job)
        with pytest.raises(BackendFailure, match='insufficient_evidence'):
            await f.apply(job, result, scope)
        await f.apply(job, {'decision': 'keep', 'reason': 'No repeated observations yet', 'steps': [], 'sourceIds': []}, scope)
        resumed = await f.open(regular)
        assert resumed['lesson']['execution_snapshot'] == frozen
        assert (await f.contexts.build(resumed['scope'], 'voice', regular['id'])).data['facts'][0]['value'] == 'I work in design'
        await f.end(resumed)
        await f.run('lesson.evaluate')
        job = await f.claim('roadmap.prepare')
        scope, result = await f.compute(job)
        invalid = dict(result, sourceIds=['foreign-source'])
        with pytest.raises(BackendFailure, match='invalid_evidence'):
            await f.apply(job, invalid, scope)
        invalid = dict(result, steps=[dict(result['steps'][0], outcome='A different direction')])
        with pytest.raises(BackendFailure, match='confirmation_required'):
            await f.apply(job, invalid, scope)
        operation = uuid4().hex
        assert await f.apply(job, result, scope, operation) == await f.apply(job, result, scope, operation)
        current = (await f.contexts.build(f.operator, 'text')).data['roadmap']
        assert current['revision'] == 2
        assert len({source['lessonId'] for source in current['sources']}) == 2
        assert current['steps'][0]['outcome'] == roadmap['steps'][0]['outcome']
        await f.run('lesson.prepare')
        # A correction arriving during computation fences that worker and regenerates dependencies.
        await f.fact('work', 'I work in medicine', 1)
        job = await f.claim('roadmap.prepare')
        scope, result = await f.compute(job)
        await f.fact('goal', 'Travel conversations', 1, 'learning')
        with pytest.raises(BackendFailure, match='stale_job'):
            await f.apply(job, result, scope)
        assert (await f.contexts.build(f.operator, 'text')).data['roadmap'] is None
        assert (await f.command('lesson.next', {}))['lesson']['preparedTopic'] is None


@pytest.mark.asyncio
async def test_insufficient_evidence_and_terminal_failures_are_explicit():
    async with httpx.AsyncClient(timeout=20) as http:
        f = Flow(http, os.environ['SP00_TEST_URL'], os.environ['SP00_TEST_TOKEN'])
        await f.init()
        await f.trial()
        job = await f.claim('roadmap.prepare')
        await f.apply(job, {'decision': 'insufficient_evidence', 'reason': 'Not enough evidence for a route', 'steps': [], 'sourceIds': []})
        context = await f.contexts.build(f.operator, 'text')
        assert context.data['roadmap'] is None
        assert context.data['roadmapPreparation']['decision'] == 'insufficient_evidence'
        lesson = await f.lesson()
        await f.pay()
        job = await f.claim('lesson.prepare')
        await f.command('job.fail', {'jobId': job['id'], 'fence': job['fence'], 'code': 'invalid_result'})
        with pytest.raises(BackendFailure, match='plan_failed'):
            await f.open(lesson)
        await f.fact('work', 'Healthcare', 0)
        job = await f.claim('roadmap.prepare')
        await f.command('job.fail', {'jobId': job['id'], 'fence': job['fence'], 'code': 'budget_exhausted'})
        with pytest.raises(BackendFailure, match='roadmap_failed'):
            await f.open(lesson)
        assert await f.claim('roadmap.prepare') is None  # No automatic paid retry after 402.


@pytest.mark.asyncio
async def test_failed_analysis_and_reschedule_do_not_admit_or_apply_obsolete_plans():
    async with httpx.AsyncClient(timeout=20) as http:
        f = Flow(http, os.environ['SP00_TEST_URL'], os.environ['SP00_TEST_TOKEN'])
        await f.init()
        trial = await f.lesson('trial')
        await f.run('lesson.prepare')
        admitted = await f.open(trial)
        await f.say(admitted)
        await f.end(admitted)
        job = await f.claim('lesson.evaluate')
        await f.command('job.fail', {'jobId': job['id'], 'fence': job['fence'], 'code': 'invalid_result'})
        lesson = await f.lesson()
        await f.pay()
        with pytest.raises(BackendFailure, match='analysis_failed'):
            await f.open(lesson)
        # Operator retry is explicit; the learner is never admitted with a fabricated evaluation.
        await f.command('job.retry', {'jobId': job['id']})
        await f.run('lesson.evaluate')
        await f.run('roadmap.prepare')
        job = await f.claim('lesson.prepare')
        scope, result = await f.compute(job)
        moved = await f.command('lesson.reschedule', {'lessonId': lesson['id'], 'expectedVersion': lesson['version'],
            'timezone': 'UTC', 'timing': 'after', 'delayMinutes': 10})
        with pytest.raises(BackendFailure, match='stale_job'):
            await f.apply(job, result, scope)
        assert (await f.command('lesson.next', {}))['lesson']['preparedTopic'] is None
        await f.run('lesson.prepare')
        assert (await f.command('lesson.next', {}))['lesson']['preparedTopic'] == 'Choosing a team activity'
        # A current ready plan becomes stale immediately when this meeting changes again.
        await f.command('lesson.reschedule', {'lessonId': moved['id'], 'expectedVersion': moved['version'],
            'timezone': 'UTC', 'timing': 'after', 'delayMinutes': 20})
        assert (await f.command('lesson.next', {}))['lesson']['preparedTopic'] is None


@pytest.mark.asyncio
async def test_changed_transcript_rejects_old_observation_sources():
    async with httpx.AsyncClient(timeout=20) as http:
        f = Flow(http, os.environ['SP00_TEST_URL'], os.environ['SP00_TEST_TOKEN'])
        await f.init()
        trial = await f.lesson('trial')
        await f.run('lesson.prepare')
        admitted = await f.open(trial)
        await f.say(admitted)
        await f.end(admitted)
        await f.run('lesson.evaluate')
        job = await f.claim('roadmap.prepare')
        scope, result = await f.compute(job)
        # A late archived transcript changes the source revision after the computation began.
        await f.command('lesson.append', {'lessonId': trial['id'], 'callSessionId': admitted['callSessionId'],
            'sourceId': uuid4().hex, 'role': 'user', 'body': 'That transcript was incomplete.', 'occurredAt': now()})
        with pytest.raises(BackendFailure, match='invalid_evidence'):
            await f.apply(job, result, scope)
        assert (await f.contexts.build(f.operator, 'text')).data['observations'] == []
