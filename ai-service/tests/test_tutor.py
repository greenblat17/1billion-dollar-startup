import asyncio
import json
import time
from datetime import datetime, timezone

import pytest
from app.tutor import TutorRuntime, TutorRequest
from app.tutor_store import TutorStore, active_booking
from app.tutor_tools import execute, Schedule, resolve_time, TOOLS


def schedule(mode='local', **kwargs):
    return dict(mode=mode, localDateTime=None, timezone=None, offset=None, shiftMinutes=None, date=None) | kwargs


def create_args():
    return dict(intent='explicit_request', evidence='Запиши', schedule=schedule(localDateTime='2030-11-10T19:00:00', timezone='Europe/Berlin'))


async def act(store, state, name, args, request='1', text='Запиши'):
    return await execute(store, state, name, args, request, text, time.time())


@pytest.mark.asyncio
async def test_booking_lifecycle_versions_ownership_and_retries():
    store = TutorStore()
    state = await store.load('tg-1')
    first = await act(store, state, 'create_trial_booking', create_args())
    assert first['ok']
    assert await act(store, state, 'create_trial_booking', create_args()) == first
    assert len(state['bookings']) == 1
    b = first['booking']
    change = dict(intent='explicit_request', evidence='Позже', bookingId=b['id'], expectedVersion=1,
                  schedule=schedule('shift_minutes', shiftMinutes=60))
    moved = await act(store, state, 'reschedule_trial_booking', change, '2', 'Позже')
    assert moved['booking']['id'] == b['id'] and moved['booking']['version'] == 2
    assert datetime.fromisoformat(moved['booking']['startsAtUtc']).hour == 19
    assert await act(store, state, 'reschedule_trial_booking', change, '2', 'Позже') == moved
    assert not (await act(store, state, 'reschedule_trial_booking', change, '3', 'Позже'))['ok']
    other = await store.load('tg-2')
    assert not (await act(store, other, 'reschedule_trial_booking', change, '4', 'Позже'))['ok']
    cancel = dict(intent='explicit_request', evidence='Отмени', bookingId=b['id'], expectedVersion=2)
    assert (await act(store, state, 'cancel_trial_booking', cancel, '5', 'Отмени'))['ok']
    assert active_booking(state) is None
    new = await act(store, state, 'create_trial_booking', create_args(), '6')
    assert new['booking']['id'] != b['id'] and new['booking']['previousBookingId'] == b['id']
    assert state['introduction'] == 'acquainted'


@pytest.mark.parametrize('local,error', [('2030-03-31T02:30:00','nonexistent_dst_time'), ('2030-10-27T02:30:00','ambiguous_dst_time')])
def test_dst_requires_real_unambiguous_time(local, error):
    with pytest.raises(ValueError, match=error):
        resolve_time(Schedule(**schedule(localDateTime=local, timezone='Europe/Berlin')), time.time())


def test_time_offsets_day_boundaries_and_now():
    now = datetime(2030, 1, 1, tzinfo=timezone.utc)
    resolved = resolve_time(Schedule(**schedule(localDateTime='2030-01-02T00:30:00', timezone='UTC+03:00')), 0, now=now)
    assert resolved[0] == '2030-01-01T21:30:00+00:00'
    assert resolve_time(Schedule(**schedule('now')), 0, now=now) == ('2030-01-01T00:00:00+00:00','UTC','now')
    with pytest.raises(ValueError, match='time_in_past'):
        resolve_time(Schedule(**schedule(localDateTime='2020-01-01T19:00:00', timezone='UTC')), 0)


@pytest.mark.asyncio
async def test_memory_correction_preserves_other_facts_and_self_report():
    store = TutorStore(); state = await store.load('tg-1')
    def facts(goal):
        return dict(facts=[dict(memory='learning',key='goal',value=goal,evidence=goal,kind='user_report')],
                    trialPreference=None,preferenceEvidence=None,summary=None)
    assert (await act(store,state,'update_learner_context',facts('работа'),'1','работа'))['ok']
    args = facts('переезд')
    args['facts'].append(dict(memory='learning',key='level',value='B1',evidence='B1',kind='self_assessment'))
    assert (await act(store,state,'update_learner_context',args,'2','переезд B1'))['ok']
    assert state['learning']['goal']['value'] == 'переезд'
    assert state['learning']['level']['kind'] == 'self_assessment'
    assert state['factHistory'][1]['previous']['value'] == 'работа'
    assert not (await act(store,state,'update_learner_context',facts('fiction'),'3','nothing'))['ok']
    assert 'fiction' not in str(state['learning'])


class Model:
    def __init__(self, replies): self.replies=list(replies); self.messages=[]
    async def complete(self, messages):
        self.messages.append(messages.copy())
        response=self.replies.pop(0)
        if isinstance(response, Exception): raise response
        if response.get("content"):
            response["content"]=json.dumps(dict(reply=response["content"],trialOffered=False))
        return response


def call(name,args):
    return dict(role='assistant',tool_calls=[dict(id='tool-1',type='function',function=dict(name=name,arguments=json.dumps(args)))])


def req(n,text='Запиши'): return TutorRequest(sessionId='tg-1',requestId=str(n),text=text,receivedAt=time.time())


@pytest.mark.asyncio
async def test_native_tool_result_fallback_duplicate_and_continuing_after_booking():
    store=TutorStore(); model=Model([call('create_trial_booking',create_args()),RuntimeError('provider'),dict(role='assistant',content='Обсудим твоё опасение.')])
    runtime=TutorRuntime(store,model)
    request=req(1)
    first=await runtime.respond(request)
    assert first['degraded'] and first['booking']['status']=='scheduled'
    assert '10.11.2030' in first['reply']
    assert await runtime.respond(request)==first
    second=await runtime.respond(req(2,'А если я растеряюсь?'))
    assert second['reply']=='Обсудим твоё опасение.' and second['booking'] is None
    assert len(model.messages)==3
    assert model.messages[1][-1]['role']=='tool'


@pytest.mark.asyncio
async def test_concurrent_duplicate_and_start_do_not_reset_context():
    store=TutorStore(); model=Model([dict(role='assistant',content='Привет, я Саша, AI-репетитор.'),dict(role='assistant',content='Продолжим!')])
    runtime=TutorRuntime(store,model); request=req(1,'/start')
    results=await asyncio.gather(runtime.respond(request,True),runtime.respond(request,True))
    assert results[0]==results[1] and len(model.messages)==1
    response=await TutorRuntime(store,model).respond(req(2,'/start'),True)
    assert response['conversationId']==results[0]['conversationId']
    assert len((await store.load('tg-1'))['history'])==4


@pytest.mark.asyncio
async def test_strict_schema_and_retention():
    store=TutorStore(); state=await store.load('tg-1')
    assert not (await act(store,state,'create_trial_booking',create_args() | {'learnerId':'other'}))['ok']
    state['history']=[dict(role='user',content='expired',at=time.time()-8*86400)]
    state['summary']='expired';state['summaryAt']=0
    await store.save('tg-1',state)
    loaded=await store.load('tg-1')
    assert loaded['history']==[] and loaded['summary']==''
    for tool in TOOLS:
        assert tool['function']['strict'] and tool['function']['parameters']['additionalProperties'] is False

@pytest.mark.asyncio
async def test_redis_restart_and_concurrent_workers_preserve_booking_and_delivery():
    from fakeredis import FakeAsyncRedis
    redis=FakeAsyncRedis(decode_responses=True)
    first_store=TutorStore(redis); second_store=TutorStore(redis)
    model=Model([call('create_trial_booking',create_args()),dict(role='assistant',content='Записал.')])
    request=req(50)
    first,second=await asyncio.gather(TutorRuntime(first_store,model).respond(request),TutorRuntime(second_store,model).respond(request))
    assert first==second and len(model.messages)==2
    b=first['booking']
    await first_store.record_delivery('tg-1','50',101,b['id'],102,b['version'])
    resumed=await TutorRuntime(TutorStore(redis),Model([])).respond(request)
    assert resumed['replyMessageId']==101 and resumed['cardMessageId']==102
    assert len((await second_store.load('tg-1'))['bookings'])==1
    await redis.aclose()


@pytest.mark.asyncio
async def test_crash_recovery_cannot_repeat_relative_move_or_accept_changed_identity():
    store=TutorStore();state=await store.load('tg-1')
    booking=(await act(store,state,'create_trial_booking',create_args()))['booking']
    change=dict(intent='explicit_request',evidence='Позже',bookingId=booking['id'],expectedVersion=1,
                schedule=schedule('shift_minutes',shiftMinutes=60))
    await act(store,state,'reschedule_trial_booking',change,'2','Позже')
    runtime=TutorRuntime(store,Model([]))
    result=await runtime.respond(req(2,'Позже'))
    assert result['degraded'] and result['booking']['version']==2
    with pytest.raises(ValueError,match='request_id_reused'):
        await runtime.respond(req(2,'Ещё позже'))


def test_api_auth_validation_and_removed_voice_onboarding():
    from fastapi.testclient import TestClient
    from tests.conftest import build_app
    app,*_=build_app()
    app.state.tutor.model=Model([dict(role='assistant',content='Привет, я Саша, AI-репетитор.')])
    with TestClient(app) as client:
        payload=req(100,'/start').model_dump()
        assert client.post('/internal/tutor/start',json=payload).status_code==401
        headers={'X-Internal-Token':'test-internal-token'}
        result=client.post('/internal/tutor/start',json=payload,headers=headers)
        assert result.status_code==200 and 'Саша' in result.json()['reply']
        assert client.post('/internal/tutor/turn',json=payload | {'text':''},headers=headers).status_code==400
        assert client.post('/internal/tutor/turn',json=payload | {'receivedAt':'yesterday'},headers=headers).status_code==422
        assert client.post('/internal/onboarding/actions',json={},headers=headers).status_code==404
        assert client.post('/internal/onboarding/state',json={},headers=headers).status_code==404
        assert client.get('/internal/learners/tg-1/context',headers=headers).json()['learnerId']=='tg-1'


@pytest.mark.asyncio
async def test_round_limit_has_no_success_and_decline_is_nonterminal():
    store=TutorStore()
    model=Model([call('get_trial_booking',{}) for _ in range(6)])
    answer=await TutorRuntime(store,model).respond(req(1,'Расскажи про trial'))
    assert answer['degraded'] and answer['booking'] is None
    model=Model([call('update_learner_context',dict(facts=[],trialPreference='declined',preferenceEvidence='Не хочу',summary='Пока не хочет trial.')),
                 dict(role='assistant',content='Конечно, можем просто общаться.'),dict(role='assistant',content='Да, объясню слово.')])
    runtime=TutorRuntime(store,model)
    await runtime.respond(req(2,'Не хочу'))
    after=await runtime.respond(req(3,'Объясни слово'))
    assert not after['degraded'] and (await store.load('tg-1'))['trialPreference']=='declined'

@pytest.mark.asyncio
async def test_late_redelivery_uses_current_booking_version():
    store=TutorStore();model=Model([call('create_trial_booking',create_args()),dict(role='assistant',content='Записал.')])
    runtime=TutorRuntime(store,model);request=req(1)
    first=await runtime.respond(request)
    state=await store.load('tg-1');booking=first['booking']
    await act(store,state,'cancel_trial_booking',dict(intent='explicit_request',evidence='Отмени',bookingId=booking['id'],expectedVersion=1),'2','Отмени')
    repeated=await runtime.respond(request)
    assert repeated['booking']['status']=='cancelled' and 'отменена' in repeated['reply']

@pytest.mark.asyncio
async def test_conversion_uses_started_cohort_and_saved_booking_not_reschedules():
    store=TutorStore()
    await store.context('reader')
    assert (await store.analytics(0,time.time()+1))['introductions']==0
    model=Model([call('create_trial_booking',create_args()),dict(role='assistant',content='Записал.')])
    await TutorRuntime(store,model).respond(req(1))
    state=await store.load('tg-1');b=active_booking(state)
    await act(store,state,'reschedule_trial_booking',dict(intent='explicit_request',evidence='Позже',bookingId=b['id'],expectedVersion=1,
              schedule=schedule('shift_minutes',shiftMinutes=60)),'2','Позже')
    report=await store.analytics(0,time.time()+1)
    assert report['introductions']==report['converted']==report['activeBookings']==report['reschedules']==1
    assert report['conversion']==1.0


def test_model_context_is_bounded_and_keeps_relevant_facts():
    from app.tutor import bounded_history,relevant_facts
    history=[dict(role='user',content='x'*4000) for _ in range(40)]
    assert sum(len(x['content']) for x in bounded_history(history))<=24000
    facts={f'fact_{i}':dict(value=f'hobby {i}',source=dict(at=i)) for i in range(100)}
    facts['goal']=dict(value='relocate',source=dict(at=0))
    facts['wanted']=dict(value='astronomy',source=dict(at=1))
    selected=relevant_facts(facts,'astronomy')
    assert len(selected)==20 and 'goal' in selected and 'wanted' in selected

@pytest.mark.asyncio
async def test_stale_store_snapshot_cannot_overwrite_newer_booking():
    from fakeredis import FakeAsyncRedis
    redis=FakeAsyncRedis(decode_responses=True)
    store=TutorStore(redis)
    stale=await store.load('tg-1');current=await store.load('tg-1')
    await act(store,current,'create_trial_booking',create_args())
    with pytest.raises(RuntimeError,match='stale_tutor_state'):
        await store.save('tg-1',stale)
    assert active_booking(await store.load('tg-1')) is not None
    await redis.aclose()

@pytest.mark.asyncio
@pytest.mark.parametrize('base_url', ['https://provider.test/v1', 'https://openrouter.ai/api/v1'])
async def test_openai_transport_uses_native_tools_and_returns_actual_tool_result(base_url):
    import httpx
    from openai import AsyncOpenAI
    from app.llm import OpenAiChatModel
    from app.tutor import TutorModel
    sent=[]
    def provider(request):
        payload=json.loads(request.content); sent.append(payload)
        if len(sent)==1:
            message=call('create_trial_booking',create_args());finish='tool_calls'
        else:
            message=dict(role='assistant',content=json.dumps(dict(reply='Запись сохранена.',trialOffered=False)))
            finish='stop'
        return httpx.Response(200,json=dict(id='synthetic',object='chat.completion',created=1,model='test',
             choices=[dict(index=0,message=message,finish_reason=finish)],usage=dict(prompt_tokens=10,completion_tokens=10,total_tokens=20)))
    client=AsyncOpenAI(api_key='test',base_url=base_url,http_client=httpx.AsyncClient(transport=httpx.MockTransport(provider)))
    try:
        response=await TutorRuntime(TutorStore(),TutorModel(OpenAiChatModel(client,'test'))).respond(req(1))
        assert response['booking']['status']=='scheduled' and not response['degraded']
        assert len(sent[0]['tools'])==5 and sent[0]['tool_choice']=='auto'
        # Claude endpoints reject this optional routing parameter. Runtime executes calls sequentially.
        assert 'parallel_tool_calls' not in sent[0]
        assert sent[0]['response_format']['json_schema']['strict'] is True
        assert all(t['function']['strict'] for t in sent[0]['tools'])
        if 'openrouter.ai' in base_url:
            assert sent[0]['provider']['require_parameters'] is True
        assert sent[1]['messages'][-1]['role']=='tool'
        assert json.loads(sent[1]['messages'][-1]['content'])['booking']['id']==response['booking']['id']
    finally:
        await client.close()


@pytest.mark.asyncio
async def test_model_timeout_does_not_claim_booking_success():
    class Slow:
        async def complete(self,messages): await asyncio.sleep(1)
    store=TutorStore()
    result=await TutorRuntime(store,Slow(),timeout=.01).respond(req(1))
    assert result['degraded'] and result['booking'] is None
    assert active_booking(await store.load('tg-1')) is None


@pytest.mark.asyncio
async def test_now_tool_needs_no_confirmation_timezone_or_profile():
    # Tests execution of model-supplied intent, not real-model interpretation of Russian.
    store = TutorStore()
    state = await store.load('tg-now')
    text = 'Хочу урок прямо сейчас.'
    args = dict(intent='explicit_request', evidence=text, schedule=schedule('now'))
    result = await act(store, state, 'create_trial_booking', args, text=text)
    assert result['ok']
    assert result['booking']['timing'] == 'now'
    assert result['booking']['timezone'] == 'UTC'
    assert state['personal'] == {} and state['learning'] == {}
    assert len(state['bookings']) == 1


@pytest.mark.asyncio
@pytest.mark.parametrize('intent', ['hypothetical', 'tentative', 'availability_question'])
async def test_tool_rejects_non_explicit_intent_labels(intent):
    store = TutorStore()
    state = await store.load('tg-intent')
    text = 'А если бы я захотел урок прямо сейчас?'
    args = dict(intent=intent, evidence=text, schedule=schedule('now'))
    result = await act(store, state, 'create_trial_booking', args, text=text)
    assert not result['ok']
    assert state['bookings'] == []
