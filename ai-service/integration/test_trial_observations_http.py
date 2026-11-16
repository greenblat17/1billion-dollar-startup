"""SP-17B handoff through real Foundation HTTP and PostgreSQL, using a fixed model result."""
import json
import os
import time
from types import SimpleNamespace
from unittest.mock import AsyncMock
from uuid import uuid4

import httpx
import pytest

from app.foundation_client import FoundationClient
from app.trial_observations import TrialObservationExtractor
from test_foundation_http import actor, now
from test_trial_v55_http import init_data


@pytest.mark.asyncio
async def test_completed_trial_raw_events_become_scoped_observations_without_listening():
    root, token = os.environ['SP00_TEST_URL'], os.environ['SP00_TEST_TOKEN']
    async with httpx.AsyncClient() as http:
        backend = FoundationClient(root, token, http)
        subject = str(time.time_ns() // 1000)
        owner = await actor(http, root, token, subject)
        lesson = await backend.command(owner['scope'], uuid4().hex, 'lesson.create', {
            'kind': 'trial', 'launchMode': 'executable', 'durationMinutes': 30,
            'startsAt': now(), 'timezone': 'UTC'})
        admitted = await backend._request('POST', '/trial/access', '', json={
            'action': 'open', 'lessonId': lesson['id'], 'attemptId': uuid4().hex,
            'initData': init_data(subject)})
        voice = admitted['scope']
        base = {'lessonId': lesson['id'], 'callSessionId': admitted['callSessionId']}
        await backend.command(voice, uuid4().hex, 'trial.ready', base)
        source = await backend.command(voice, uuid4().hex, 'voice.append', dict(base,
            sourceId='spoken-1', role='user', body='I work here', occurredAt=now(), revision=1,
            metadata={'providerItemId': 'provider-1', 'finality': 'final'}))
        await backend.command(voice, uuid4().hex, 'voice.end', dict(base, reason='end_button'))
        assert (await backend.trial_events(owner['scope'], lesson['id']))['observationStatus'] == 'queued'
        job = (await backend.command(owner['scope'], uuid4().hex, 'job.claim', {'kind': 'trial.observe'}))['job']
        response = await http.post(root + '/test/job-scope', headers={'X-Test-Token': token}, json=job)
        response.raise_for_status()
        scope = response.json()['scope']
        model = SimpleNamespace(complete_json=AsyncMock(return_value=json.dumps({'observations': [{
            'anchorEventId': source['eventId'], 'quote': 'I work here', 'skillId': 'grammar',
            'topicId': None, 'problemId': None, 'outcome': 'independent_correct',
            'support': 'independent', 'initialOutcome': 'correct', 'contextEventId': None,
            'hintEventId': None, 'rationale': 'Correct use in the learner reply'}]})))
        result = await TrialObservationExtractor(backend, model).extract(scope, lesson['id'], job['input_refs'])
        assert result['observations'][0]['sources'][0]['revision'] == source['sequence']
        applied = await backend.command(scope, 'apply-' + job['id'], 'job.apply', {
            'jobId': job['id'], 'fence': job['fence'], 'result': result})
        assert applied['status'] == 'succeeded'
        assert (await backend.trial_events(owner['scope'], lesson['id']))['observationStatus'] == 'succeeded'
        items = (await backend.learning_observations(owner['scope'], lesson_id=lesson['id']))['items']
        assert len(items) == 1 and items[0]['document']['skillId'] == 'grammar'
        assert items[0]['evidenceUsable'] is True
        assert (await backend.command(scope, 'apply-' + job['id'], 'job.apply', {
            'jobId': job['id'], 'fence': job['fence'], 'result': result})) == applied
