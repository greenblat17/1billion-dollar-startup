"""Same socket replay against the loopback Ktor harness and PostgreSQL."""
import json
import os
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock
from uuid import uuid4

import httpx
import pytest

from app.context_builder import ContextBuilder
from app.foundation_client import BackendFailure, FoundationClient
from app.learning_engine import LearningEngine
from app.trial_policy import TrialAnalyzer
from test_foundation_http import actor, now
from test_trial_v55_http import init_data
from tests.lesson_replay import FIRST, HISTORY, replay


BASELINE = json.loads(Path(__file__).with_name('lesson_replay_baseline.json').read_text(encoding='utf-8'))


@pytest.mark.parametrize('kind', ('trial', 'regular'))
async def test_lesson_replay_persists_terminal_state(monkeypatch, kind):
    root, token = os.environ['SP00_TEST_URL'], os.environ['SP00_TEST_TOKEN']
    async with httpx.AsyncClient() as http:
        backend = FoundationClient(root, token, http)
        subject = str(time.time_ns() // 1000)
        owner = await actor(http, root, token, subject)
        scope = owner['scope']
        auth = init_data(subject)

        async def command(action, **args):
            return await backend.command(scope, uuid4().hex, action, args)

        if kind == 'regular':
            await command('access.confirm_payment', provider='fixture', externalId=uuid4().hex,
                          validFrom=now(), validUntil=(datetime.now(timezone.utc) + timedelta(days=1)).isoformat())
        lesson = await command('lesson.create', kind=kind, launchMode='executable',
                               durationMinutes=20 if kind == 'trial' else 30, startsAt=now(), timezone='UTC')
        lesson_id = lesson['id']
        if kind == 'regular':
            job = (await command('job.claim', kind='lesson.prepare'))['job']
            assert job['target_id'] == lesson_id
            await command('job.apply', jobId=job['id'], fence=job['fence'], result={
                'objective': 'Explain preferences with reasons', 'topic': 'Hiking routes',
                'strategy': 'Discover interests, explore a choice, recap and ask feedback',
            })

        async def snapshot():
            return await backend.snapshot(scope, 'voice', lesson_id)

        interrupted_state = None

        async def disconnected():
            nonlocal interrupted_state
            state = await snapshot()
            assert state['lesson']['id'] == lesson_id
            assert state['lesson']['status'] == 'in_progress'
            assert [(item['role'], item['body']) for item in state['events']] == [('user', FIRST)]
            interrupted_state = state['lesson']

        observed = await replay(monkeypatch, backend, ContextBuilder(backend), lesson_id, auth, kind, disconnected)
        state = await snapshot()
        reason = 'learner_requested' if kind == 'trial' else 'natural_goodbye'
        assert state['lesson']['id'] == lesson_id
        assert state['lesson']['status'] == 'completed' and state['lesson']['outcome_reason'] == reason
        assert state['lesson']['ended_early'] is True
        assert state['lesson']['execution_snapshot'] == interrupted_state['execution_snapshot']
        assert [(item['role'], item['body']) for item in state['events']] == HISTORY
        sequences = [item['sequence'] for item in state['events']]
        assert sequences == sorted(set(sequences))
        assert state['lessonAnalysis']['status'] == 'queued'
        with pytest.raises(BackendFailure, match='lesson_unavailable'):
            await backend._request('POST', '/trial/access', '', json={
                'action': 'open', 'lessonId': lesson_id, 'initData': auth, 'attemptId': uuid4().hex,
            })
        audio = (await backend.trial_audio(scope, lesson_id))['objects']
        assert set(item['object_key'] for item in audio) == set(key for key, _ in observed.archived)
        by_key = {item['object_key']: item for item in audio}
        ordered_audio = [by_key[key] for key, _ in observed.archived]
        first_session, second_session = ordered_audio[0]['call_session_id'], ordered_audio[-1]['call_session_id']
        assert first_session != second_session
        assert [item['call_session_id'] for item in ordered_audio] == [first_session] * 2 + [second_session] * 3
        assert [item['direction'] for item in ordered_audio] == ['assistant', 'user', 'assistant', 'user', 'assistant']
        assert all(item['lesson_id'] == lesson_id and item['status'] == 'stored' for item in audio)
        assert [item['metadata']['playbackComplete'] for item in ordered_audio if item['direction'] == 'assistant'] == [False, True, True]
        if kind == 'trial':
            raw = (await backend.trial_events(scope, lesson_id))['events']
            assert [item['body'] for item in raw if item['kind'] == 'transcript'] == [body for _, body in HISTORY]
            assert [(item['callSessionId'], item['metadata']['reason']) for item in raw if item['kind'] == 'ended'] == [
                (first_session, 'technical_disconnect'), (second_session, reason),
            ]
        trace = {
            'browserEvents': observed.browser_events,
            'history': [[item['role'], item['body']] for item in state['events']],
            'sequences': sequences,
            'disconnectedStatus': interrupted_state['status'],
            'finalStatus': state['lesson']['status'],
            'outcome': state['lesson']['outcome_reason'],
            'endedEarly': state['lesson']['ended_early'],
            'analysisStatus': state['lessonAnalysis']['status'],
        }
        assert trace == BASELINE[kind]
        print(json.dumps({'kind': kind, 'lessonId': lesson_id, 'sessions': [first_session, second_session],
                          **trace}, ensure_ascii=False, sort_keys=True))

        if kind == 'regular':
            await verify_regular_result(http, root, token, backend, scope, lesson_id, state['events'][0])


async def verify_regular_result(http, root, token, backend, scope, lesson_id, learner_event):
    async def claim(kind):
        job = (await backend.command(scope, uuid4().hex, 'job.claim', {'kind': kind}))['job']
        assert job['target_id'] == lesson_id
        response = await http.post(root + '/test/job-scope', headers={'X-Test-Token': token}, json=job)
        response.raise_for_status()
        return job, response.json()['scope']

    contexts = ContextBuilder(backend)
    job, worker_scope = await claim('lesson.evaluate')
    model = SimpleNamespace(complete_json=AsyncMock(return_value=json.dumps({
        'status': 'evaluated',
        'observations': [{
            'key': 'hiking-preference', 'dimension': 'vocabulary', 'content': 'Used vocabulary about hiking.',
            'uncertainty': 'limited', 'sourceId': learner_event['id'],
        }],
        'facts': [], 'candidates': [],
        'notes': [{'kind': 'topic', 'content': 'Hiking', 'sourceIds': [learner_event['id']]}],
    })))
    engine = LearningEngine(backend, contexts, analyzer=TrialAnalyzer(model))
    result = await engine.compute(worker_scope, job)
    assert learner_event['id'] in model.complete_json.await_args.args[1]
    with pytest.raises(BackendFailure, match='invalid_result'):
        await engine.apply(worker_scope, job, {key: value for key, value in result.items() if key != 'observations'})
    assert (await backend.snapshot(scope, 'voice', lesson_id))['lessonAnalysis']['status'] == 'running'
    assert await engine.apply(worker_scope, job, result) == {'status': 'succeeded'}
    stored = (await backend.snapshot(scope, 'voice', lesson_id))['observations']
    assert [item['source_event_id'] for item in stored] == [learner_event['id']]
    assert await engine.apply(worker_scope, job, result) == {'status': 'succeeded'}
    assert (await backend.snapshot(scope, 'voice', lesson_id))['observations'] == stored

    # Evaluation changes the evidence digest, so an earlier review can be superseded.
    for _ in range(2):
        review, worker_scope = await claim('lesson.review')
        result = await engine.compute(worker_scope, review)
        assert result == {}
        applied = await engine.apply(worker_scope, review, result)
        if applied == {'status': 'succeeded'}:
            break
        assert applied == {'status': 'superseded'}
    else:
        pytest.fail('current review was not applied')
    saved = (await backend.snapshot(scope, 'learning'))['postLessonReview']
    assert saved['document']['evaluationRef']['sourceRevision'] == job['input_refs']['transcriptRevision']
    assert saved['revision'] == 1
    assert await engine.apply(worker_scope, review, result) == {'status': 'succeeded'}
    assert (await backend.snapshot(scope, 'learning'))['postLessonReview'] == saved
