"""Real Ktor/PostgreSQL boundary. Invoked explicitly; missing services are failures."""
import os
import time
from datetime import datetime, timezone
from uuid import uuid4

import httpx
import pytest

from app.context_builder import ContextBuilder
from app.foundation_client import BackendFailure, FoundationClient
from app.foundation_tutor import FoundationTutor
from app.lesson_voice import LessonVoice
from app.tutor import TutorRequest


def now():
    return datetime.now(timezone.utc).isoformat()


async def actor(http, root, token, subject, role='operator', **data):
    response = await http.post(root + '/test/student', headers={'X-Test-Token': token}, json={
        'subject': subject, 'role': role, **data,
    })
    response.raise_for_status()
    return response.json()


class GoalModel:
    def __init__(self):
        self.count = 0

    async def complete(self, messages):
        self.count += 1
        if self.count == 1:
            import json
            return {'role': 'assistant', 'tool_calls': [{'id': 'goal-tool', 'type': 'function', 'function': {
                'name': 'update_learner_context', 'arguments': json.dumps({
                    'facts': [{'memory': 'learning', 'key': 'goal', 'value': 'relocation',
                               'evidence': 'relocation', 'kind': 'user_report'}],
                    'trialPreference': None, 'preferenceEvidence': None, 'summary': None,
                }),
            }}]}
        return {'role': 'assistant', 'content': '{"reply":"Понял, готовимся к переезду.","trialOffered":false}'}


@pytest.mark.asyncio
async def test_text_tool_commits_in_ktor_and_voice_reads_same_goal():
    root = os.environ['SP00_TEST_URL']
    token = os.environ['SP00_TEST_TOKEN']
    async with httpx.AsyncClient() as http:
        subject = str(time.time_ns())
        request = uuid4().hex
        text = await actor(http, root, token, subject, 'text', requestId=request, text='relocation', occurredAt=now())
        backend = FoundationClient(root, token, http)
        contexts = ContextBuilder(backend)
        model = GoalModel()
        tutor = FoundationTutor(backend, model, contexts)
        payload = TutorRequest(sessionId='tg-' + subject, requestId=request, text='relocation', receivedAt=time.time())
        answer = await tutor.respond(payload, text['scope'])
        assert 'переезду' in answer['reply']
        assert (await contexts.build(text['scope'], 'text')).data['goal']['content']['value'] == 'relocation'
        repeat = await tutor.respond(payload, text['scope'])
        assert repeat == answer
        assert model.count == 2
        operator = await actor(http, root, token, subject)
        lesson = await backend.command(operator['scope'], uuid4().hex, 'lesson.create', {
            'kind': 'trial', 'launchMode': 'executable', 'durationMinutes': 15, 'startsAt': now(), 'timezone': 'UTC',
        })
        voice = await actor(http, root, token, subject, 'voice', lessonId=lesson['id'])
        context = await contexts.build(voice['scope'], 'voice', lesson['id'])
        assert context.data['goal']['content']['value'] == 'relocation'
        assert 'relocation' in context.instructions('voice')
        assert context.data['preparation']['status'] == 'blocked'
        other = await actor(http, root, token, str(time.time_ns()))
        with pytest.raises(BackendFailure, match='not_owned'):
            await contexts.build(other['scope'], 'voice', lesson['id'])


@pytest.mark.asyncio
async def test_voice_transport_receives_context_only_after_backend_admission():
    root, token = os.environ['SP00_TEST_URL'], os.environ['SP00_TEST_TOKEN']
    async with httpx.AsyncClient() as http:
        subject = str(time.time_ns())
        operator = await actor(http, root, token, subject)
        backend = FoundationClient(root, token, http)
        scope = operator['scope']
        lesson = await backend.command(scope, uuid4().hex, 'lesson.create', {
            'kind': 'trial', 'launchMode': 'executable', 'durationMinutes': 15, 'startsAt': now(), 'timezone': 'UTC',
        })
        import json
        from app.realtime import OpenAiRealtimeGateway
        sessions = []

        def provider(request):
            if request.url.path.endswith('/client_secrets'):
                sessions.append(json.loads(request.content)['session'])
                return httpx.Response(200, json={'value': 'local-ephemeral'})
            return httpx.Response(201, text='answer-sdp', headers={'Location': '/v1/realtime/calls/test-call'})

        transport = OpenAiRealtimeGateway('local-test-key', transport=httpx.MockTransport(provider))
        voice = LessonVoice(ContextBuilder(backend), transport)
        with pytest.raises(BackendFailure, match='admission_required'):
            await voice.start(scope, lesson['id'], 'offer', 'marin')
        assert sessions == []
        job = (await backend.command(scope, uuid4().hex, 'job.claim', {}))['job']
        await backend.command(scope, uuid4().hex, 'job.apply', {
            'jobId': job['id'], 'fence': job['fence'],
            'result': {'objective': 'get acquainted', 'topic': 'interests', 'strategy': 'gentle conversation'},
        })
        await backend.command(scope, uuid4().hex, 'lesson.episode', {'lessonId': lesson['id'], 'attemptId': 'one'})
        assert await voice.start(scope, lesson['id'], 'offer', 'marin') == ('answer-sdp', 'test-call')
        assert 'Sasha' in sessions[0]['instructions']
        assert 'get acquainted' in sessions[0]['instructions']


@pytest.mark.asyncio
async def test_memory_extraction_commits_only_hidden_candidates_from_its_durable_source():
    from app.learning_engine import LearningEngine, PersonalCandidateExtractor

    root, token = os.environ['SP00_TEST_URL'], os.environ['SP00_TEST_TOKEN']
    async with httpx.AsyncClient() as http:
        backend = FoundationClient(root, token, http)
        subject, request = str(time.time_ns()), uuid4().hex
        text = await actor(http, root, token, subject, 'text', requestId=request, text='I work as a doctor', occurredAt=now())
        await backend.command(text['scope'], uuid4().hex, 'run.claim', {})
        await backend.command(text['scope'], uuid4().hex, 'run.complete', {'reply': 'Understood', 'degraded': False})
        operator = await actor(http, root, token, subject)
        job = (await backend.command(operator['scope'], uuid4().hex, 'job.claim', {'kind': 'memory.extract'}))['job']
        issued = await http.post(root + '/test/job-scope', headers={'X-Test-Token': token}, json=job)
        issued.raise_for_status()
        scope = issued.json()['scope']

        class Extractor:
            async def update_person_once(self, existing, transcripts):
                assert transcripts == ['I work as a doctor']
                return {'name': None, 'work': 'doctor', 'leisure': None, 'goal': None, 'facts': []}

        learning = LearningEngine(backend, ContextBuilder(backend), extractor=PersonalCandidateExtractor(Extractor()))
        result = await learning.compute(scope, job)
        assert (await learning.apply(scope, job, result))['status'] == 'succeeded'
        assert (await learning.apply(scope, job, result))['status'] == 'succeeded'
        context = await ContextBuilder(backend).build(operator['scope'], 'text')
        assert context.data['facts'] == []
        assert 'doctor' not in str(context.data['facts'])


@pytest.mark.asyncio
async def test_learning_result_returns_to_chat_without_reverting_a_new_goal():
    from app.learning_engine import LearningEngine
    root, token = os.environ['SP00_TEST_URL'], os.environ['SP00_TEST_TOKEN']
    async with httpx.AsyncClient() as http:
        backend = FoundationClient(root, token, http)
        subject = str(time.time_ns())
        operator = await actor(http, root, token, subject)
        scope = operator['scope']
        lesson = await backend.command(scope, uuid4().hex, 'lesson.create', {
            'kind': 'trial', 'launchMode': 'executable', 'durationMinutes': 15, 'startsAt': now(), 'timezone': 'UTC'})
        prepare = (await backend.command(scope, uuid4().hex, 'job.claim', {'kind': 'lesson.prepare'}))['job']
        await backend.command(scope, uuid4().hex, 'job.apply', {'jobId': prepare['id'], 'fence': prepare['fence'],
            'result': {'objective': 'practice', 'topic': 'work', 'strategy': 'conversation'}})
        episode = await backend.command(scope, uuid4().hex, 'lesson.episode', {'lessonId': lesson['id'], 'attemptId': 'first'})
        event = await backend.command(scope, uuid4().hex, 'lesson.append', {
            'lessonId': lesson['id'], 'callSessionId': episode['id'], 'sourceId': 'utterance-1', 'role': 'user',
            'body': 'Yesterday I go to work', 'occurredAt': now()})
        await backend.command(scope, uuid4().hex, 'lesson.end_episode', {
            'lessonId': lesson['id'], 'callSessionId': episode['id'], 'reason': 'natural_goodbye'})
        current = (await backend.snapshot(scope, 'voice', lesson['id']))['lesson']
        await backend.command(scope, uuid4().hex, 'lesson.complete', {
            'lessonId': lesson['id'], 'expectedVersion': current['version'], 'reason': 'observed_completion'})
        job = (await backend.command(scope, uuid4().hex, 'job.claim', {'kind': 'lesson.evaluate'}))['job']
        issued = await http.post(root + '/test/job-scope', headers={'X-Test-Token': token}, json=job)
        issued.raise_for_status()
        worker_scope = issued.json()['scope']

        class Analyzer:
            async def evaluate(self, context, inputs):
                assert any(source['id'] == event['eventId'] for source in context.data['sourceEvents'])
                return {'status': 'evaluated', 'observations': [{'key': 'past-tense', 'dimension': 'grammar',
                    'content': 'Past tense needs practice', 'uncertainty': 'limited', 'sourceId': event['eventId']}]}

        engine = LearningEngine(backend, ContextBuilder(backend), analyzer=Analyzer())
        result = await engine.compute(worker_scope, job)
        request = uuid4().hex
        text = await actor(http, root, token, subject, 'text', requestId=request, text='relocation', occurredAt=now())
        await FoundationTutor(backend, GoalModel(), ContextBuilder(backend)).respond(
            TutorRequest(sessionId='tg-' + subject, requestId=request, text='relocation', receivedAt=time.time()), text['scope'])
        await engine.apply(worker_scope, job, result)
        await engine.apply(worker_scope, job, result)
        chat = await ContextBuilder(backend).build(scope, 'text')
        assert chat.data['goal']['content']['value'] == 'relocation'
        assert len(chat.data['observations']) == 1
        assert chat.data['observations'][0]['source_content_expired'] is False


@pytest.mark.asyncio
async def test_shipping_python_app_uses_real_backend_scope_and_native_tools(monkeypatch):
    from dataclasses import replace
    from app.config import Settings
    from app.speaky_main import create_app
    root, token = os.environ['SP00_TEST_URL'], os.environ['SP00_TEST_TOKEN']
    async with httpx.AsyncClient() as http:
        subject, request = str(time.time_ns()), uuid4().hex
        text = await actor(http, root, token, subject, 'text', requestId=request, text='relocation', occurredAt=now())
        model = GoalModel()
        monkeypatch.setattr('app.speaky_main.TutorModel', lambda _: model)
        settings = replace(Settings.from_env(), foundation_backend_url=root, ai_internal_token=token,
                           openai_api_key='local-test', redis_url=None)
        app = create_app(settings)
        async with app.router.lifespan_context(app):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://ai-test') as ai:
                headers = {'X-Internal-Token': token, 'X-Execution-Scope': text['scope']}
                payload = {'sessionId': 'tg-' + subject, 'requestId': request, 'text': 'relocation', 'receivedAt': time.time()}
                response = await ai.post('/internal/foundation/tutor/turn', headers=headers, json=payload)
                assert response.status_code == 200, response.text
                assert 'переезду' in response.json()['reply']
                again = await ai.post('/internal/foundation/tutor/turn', headers=headers, json=payload)
                assert again.json() == response.json()
                assert model.count == 2
                headers['X-Execution-Scope'] = 'forged'
                assert (await ai.post('/internal/foundation/tutor/turn', headers=headers, json=payload)).status_code == 403
        backend = FoundationClient(root, token, http)
        assert (await backend.snapshot(text['scope'], 'text'))['goal']['content']['value'] == 'relocation'
