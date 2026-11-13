"""SP08: production Python -> Ktor -> isolated PG. Only model content is scripted."""
import hashlib
import hmac
import json
import os
import time
import subprocess
from pathlib import Path
from urllib.parse import urlencode
from uuid import uuid4

import httpx
import pytest
from fastapi import FastAPI

from app.foundation_client import BackendFailure
from app.trial_api import install_trial_api
from integration.test_foundation_http import actor, now
from integration.test_sp06_roadmap_http import Flow, ScriptedContent


class ProgressContent(ScriptedContent):
    def __init__(self):
        super().__init__()
        self.situation = 'work decision'
        self.support = 'independent'
        self.signal = 'strength'

    async def complete_json(self, system, data, **kwargs):
        context = json.loads(data)
        if kwargs['operation'] == 'trial_evaluation':
            event = next(e for e in context['sourceEvents'] if e['role'] == 'user')
            return json.dumps(dict(status='evaluated', facts=[], candidates=[], observations=[dict(
                key='reasons', dimension='autonomy', content='Самостоятельно объясняет выбор', uncertainty='supported',
                sourceId=event['id'], evidence=dict(kind='ai_observation', signal=self.signal, support=self.support,
                    situation=self.situation, quote=event['body']))], notes=[dict(kind='skill',content='Объяснение выбора',sourceIds=[event['id']])]))
        if kwargs['operation'] == 'roadmap_prepare':
            rows = context['progressEvidence']['rows']
            route = context.get('roadmap')
            result = dict(decision='keep' if route else 'revise',reason='Практикуем объяснение выбора',
                steps=[] if route else [dict(id='reasons',outcome='Объяснять свой выбор самостоятельно',tactic='Обсуждаем реальные решения'),
                    dict(id='questions',outcome='Задавать уточняющие вопросы',tactic='Уточняем детали')],sourceIds=list({r['source_event_id'] for r in rows}),progress=[],dimensions=[])
            for dimension in ['fluency','confidence','autonomy','grammar','vocabulary']:
                refs=[r['id'] for r in rows if r['dimension']==dimension]
                result['dimensions'].append(dict(dimension=dimension,trend='observed' if refs else 'insufficient_evidence',
                    reason='Есть наблюдения' if refs else 'Недостаточно данных',comparison='',observationIds=refs))
            if route:
                result['progress']=[dict(stepId='reasons',outcome=route['steps'][0]['outcome'],decision='demonstrated',
                    reason='Самостоятельно объясняет решения в разных ситуациях',transferReason='Перенос с рабочих решений на планирование отдыха',
                    observationIds=[r['id'] for r in rows],dimensions=['autonomy'])]
            return json.dumps(result)
        return await super().complete_json(system,data,**kwargs)


def auth(subject,age=0):
    fields={'auth_date':str(int(time.time())-age),'user':json.dumps({'id':int(subject)})}
    secret=hmac.new(b'WebAppData',b'123:sp02-local-only',hashlib.sha256).digest()
    fields['hash']=hmac.new(secret,'\n'.join(f'{k}={v}' for k,v in sorted(fields.items())).encode(),hashlib.sha256).hexdigest()
    return urlencode(fields)


async def progress(f,subject=None):
    app=FastAPI()
    install_trial_api(app,f.backend,None,None,None,None,None)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),base_url='http://miniapp') as edge:
        response=await edge.post('/miniapp/api',json={'action':'progress','initData':auth(subject or f.subject),'studentId':'ignored-foreign'})
        response.raise_for_status()
        return response.json()


async def setup(http):
    f=Flow(http,os.environ['SP00_TEST_URL'],os.environ['SP00_TEST_TOKEN'])
    await f.init()
    model=ProgressContent()
    f.model=model
    f.engine.analyzer.model=model
    f.engine.roadmap.model=model
    await f.trial()
    await f.run('roadmap.prepare')
    return f


async def second(f,situation='holiday decision'):
    f.model.situation=situation
    # Another free trial avoids mixing SP08 with billing fixtures; same analyzer serves regular.
    lesson=await f.lesson('trial')
    await f.run('lesson.prepare')
    f.last_admitted=await f.open(lesson)
    await f.say(f.last_admitted)
    await f.end(f.last_admitted)
    await f.run('lesson.evaluate')
    job=await f.claim('roadmap.prepare')
    scope,result=await f.compute(job)
    return job,scope,result


@pytest.mark.asyncio
async def test_analysis_evidence_progress_public_api_and_dispute_replay_forgetting():
    async with httpx.AsyncClient(timeout=30) as http:
        f=await setup(http)
        first=await progress(f)
        assert first['steps'][0]['status']=='current'
        assert first['dimensions'][0]['trend']=='insufficient_evidence'
        snapshot=await f.backend.snapshot(f.operator,'learning')
        assert snapshot['trialResult']['result']['notes'][0]['kind']=='skill'
        assert 'quote' not in snapshot['progressEvidence']['rows'][0]['evidence']
        job,scope,result=await second(f)
        operation=uuid4().hex
        assert (await f.apply(job,result,scope,operation))['status']=='succeeded'
        assert await f.apply(job,result,scope,operation)=={'status':'succeeded'}
        completed=await progress(f)
        assert [s['status'] for s in completed['steps']]==['completed','current']
        subprocess.run(['node',str(Path(__file__).resolve().parents[2]/'mini-app/tests/helpers/render-api-progress.mjs')],
                       input=json.dumps(completed),text=True,capture_output=True,check=True,timeout=10)
        assert (await f.contexts.build(f.operator,'text')).data['progress']==completed
        assert (await progress(f,str(time.time_ns())))['steps']==[]
        assert (await progress(f,str(time.time_ns())))['status']=='no_roadmap'
        denial=await actor(http,f.root,f.token,f.subject,requestId=uuid4().hex,text='Я читал готовый текст',occurredAt=now())
        await f.command('memory.review',dict(sourceId=denial['input']['event_id'],evidence='Я читал готовый текст',key='reasons',
            expectedVersion=0,status='disputed',explanation='Читал готовый текст'))
        assert (await progress(f))['steps'][0]['status']=='needs_review'
        # Fresh model result cannot close a disputed skill.
        pending=await f.claim('roadmap.prepare')
        pending_scope,pending_result=await f.compute(pending)
        with pytest.raises(BackendFailure,match='insufficient_progress_evidence'):
            await f.apply(pending,pending_result,pending_scope)
        await f.command('memory.forget',dict(category='learning',key='goal',expectedVersion=1))
        # New identity scope after epoch revocation; public progress uses current identity automatically.
        cleared=await progress(f)
        assert cleared['status']=='no_roadmap' and cleared['steps']==[]


@pytest.mark.asyncio
@pytest.mark.parametrize('mutation,error',[
    ('one_lesson','insufficient_progress_evidence'),('foreign','invalid_evidence'),
    ('duplicate','invalid_evidence'),('outcome','invalid_progress_step'),
    ('confidence','invalid_evidence'),('fluency','insufficient_progress_evidence'),
])
async def test_backend_rejects_unproven_progress_even_if_model_claims_success(mutation,error):
    async with httpx.AsyncClient(timeout=30) as http:
        f=await setup(http)
        job,scope,result=await second(f)
        a=result['progress'][0]
        if mutation=='one_lesson': a['observationIds']=a['observationIds'][:1]
        if mutation=='foreign': a['observationIds'][0]='another-student-observation'
        if mutation=='duplicate': a['observationIds']=[a['observationIds'][0]]*2
        if mutation=='outcome': a['outcome']='A different skill'
        if mutation=='confidence': a['dimensions']=['confidence']
        if mutation=='fluency': a['dimensions']=['fluency']
        with pytest.raises(BackendFailure,match=error): await f.apply(job,result,scope)
        assert (await progress(f))['steps'][0]['status']=='current'


@pytest.mark.asyncio
async def test_prompted_evidence_and_late_review_do_not_complete_stage():
    async with httpx.AsyncClient(timeout=30) as http:
        f=await setup(http)
        f.model.support='prompted'
        job,scope,result=await second(f)
        with pytest.raises(BackendFailure,match='insufficient_progress_evidence'): await f.apply(job,result,scope)
        await f.fact('work','Teacher',0)
        with pytest.raises(BackendFailure,match='stale_job'): await f.apply(job,result,scope)
        assert (await progress(f))['steps'][0]['status']=='current'


@pytest.mark.asyncio
async def test_auth_and_empty_state_do_not_schedule_paid_jobs_or_create_lessons():
    async with httpx.AsyncClient(timeout=30) as http:
        f=Flow(http,os.environ['SP00_TEST_URL'],os.environ['SP00_TEST_TOKEN'])
        await f.init()
        assert (await progress(f))['status']=='no_roadmap'
        assert (await f.backend.snapshot(f.operator,'text'))['booking'] is None
        assert await f.claim('roadmap.prepare') is None
        with pytest.raises(BackendFailure,match='invalid_telegram_auth'):
            await f.backend._request('POST','/trial/access','',json={'action':'progress','initData':'bad'})
        with pytest.raises(BackendFailure,match='telegram_auth_expired'):
            await f.backend._request('POST','/trial/access','',json={'action':'progress','initData':auth(f.subject,90000)})


@pytest.mark.asyncio
async def test_transcript_revision_adaptation_and_new_goal_preserve_stage_meaning():
    async with httpx.AsyncClient(timeout=30) as http:
        f=await setup(http)
        job,scope,result=await second(f)
        result['decision']='revise'
        roadmap=(await f.contexts.build(f.operator,'text')).data['roadmap']
        result['steps']=list(reversed(roadmap['steps']))
        await f.apply(job,result,scope)
        changed=await progress(f)
        assert changed['roadmapRevision']==2
        assert {s['id']:s['status'] for s in changed['steps']}=={'reasons':'completed','questions':'current'}
        # Late transcript appended by an operator creates a new revision, invalidating old evidence immediately.
        admitted=f.last_admitted
        await f.command('lesson.append',dict(lessonId=admitted['lesson']['id'],callSessionId=admitted['callSessionId'],
            sourceId=uuid4().hex,role='user',body='I was reading a prepared answer',occurredAt=now()))
        assert next(s for s in (await progress(f))['steps'] if s['id']=='reasons')['status']=='needs_review'
        await f.fact('goal','Travel conversations',1,'learning')
        new_goal=await progress(f)
        assert new_goal['steps']==[] and new_goal['goal']['value']=='Travel conversations'


@pytest.mark.asyncio
async def test_resolving_dispute_cannot_reuse_the_old_performance_as_new_confirmation():
    async with httpx.AsyncClient(timeout=30) as http:
        f=await setup(http)
        job,scope,result=await second(f)
        await f.apply(job,result,scope)
        for version,status in enumerate(['disputed','resolved']):
            report=await actor(http,f.root,f.token,f.subject,requestId=uuid4().hex,text='Check again',occurredAt=now())
            await f.command('memory.review',dict(sourceId=report['input']['event_id'],evidence='Check again',
                key='reasons',expectedVersion=version,status=status,explanation='Check again in conversation'))
        assert (await progress(f))['steps'][0]['status']=='needs_review'
        job=await f.claim('roadmap.prepare')
        scope,result=await f.compute(job)
        with pytest.raises(BackendFailure,match='insufficient_progress_evidence'): await f.apply(job,result,scope)
        # Close this review honestly, then collect genuinely new performances after the clarification.
        result['progress'][0].update(decision='insufficient_evidence',observationIds=[])
        for dimension in result['dimensions']:
            dimension.update(trend='insufficient_evidence',observationIds=[])
        await f.apply(job,result,scope)
        new_sources=set()
        for index,situation in enumerate(['family decision','travel decision']):
            job,scope,result=await second(f,situation)
            rows=(await f.backend.snapshot(f.operator,'learning'))['progressEvidence']['rows']
            new_sources.update(o['id'] for o in rows if o['evidence']['situation']==situation)
            result['progress'][0]['observationIds']=list(new_sources)
            if index==0: result['progress'][0]['decision']='insufficient_evidence'
            for dimension in result['dimensions']:
                dimension['observationIds']=[i for i in dimension['observationIds'] if i in new_sources]
            await f.apply(job,result,scope)
        assert (await progress(f))['steps'][0]['status']=='completed'


@pytest.mark.asyncio
async def test_repeated_counterevidence_cannot_be_ignored_and_single_error_does_not_erase_history():
    async with httpx.AsyncClient(timeout=30) as http:
        f=await setup(http)
        job,scope,result=await second(f)
        await f.apply(job,result,scope)
        f.model.signal='difficulty'
        for number in range(2):
            job,scope,result=await second(f)
            observations=(await f.backend.snapshot(f.operator,'learning'))['progressEvidence']['rows']
            result['progress'][0]['observationIds']=[o['id'] for o in observations if o['evidence']['signal']=='strength']
            if number==0:
                # Preserve old achievement, record uncertainty; an isolated error is not loss of mastery.
                result['progress'][0]['decision']='insufficient_evidence'
                await f.apply(job,result,scope)
                assert (await progress(f))['steps'][0]['status']=='completed'
            else:
                with pytest.raises(BackendFailure,match='contradictory_progress_evidence'): await f.apply(job,result,scope)


@pytest.mark.asyncio
async def test_public_preparation_states_are_real_and_legacy_observations_do_not_become_mastery():
    async with httpx.AsyncClient(timeout=30) as http:
        f=Flow(http,os.environ['SP00_TEST_URL'],os.environ['SP00_TEST_TOKEN'])
        await f.init()
        await f.trial()  # Old scripted schema, without SP08 evidence fields.
        assert (await progress(f))['status']=='preparing'
        job=await f.claim('roadmap.prepare')
        await f.apply(job,dict(decision='insufficient_evidence',reason='Недостаточно данных',steps=[],sourceIds=[]))
        assert (await progress(f))['status']=='insufficient_evidence'
        await f.fact('work','Designer',0)
        job=await f.claim('roadmap.prepare')
        await f.command('job.fail',dict(jobId=job['id'],fence=job['fence'],code='invalid_result'))
        assert (await progress(f))['status']=='error'
        assert all(d['trend']=='insufficient_evidence' for d in (await progress(f))['dimensions'])
