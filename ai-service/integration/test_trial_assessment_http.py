"""The post-Trial handoff keeps supported skills when a model overstates another skill."""
import json
import os
import time
from types import SimpleNamespace
from unittest.mock import AsyncMock
from uuid import uuid4

import httpx
import pytest

from app.foundation_client import FoundationClient
from app.trial_assessment import TrialAssessment
from app.trial_observations import TrialObservationExtractor
from test_foundation_http import actor, now
from test_trial_v55_http import init_data


@pytest.mark.asyncio
async def test_unsupported_skill_is_unknown_and_supported_skill_reaches_state():
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
        utterances = [
            ('grammar', 'I work here.'),
            ('speaking', 'I enjoy talking about books.'),
            ('speaking', 'Yesterday I described a story to my friend.'),
        ]
        sources = []
        for index, (_, body) in enumerate(utterances):
            sources.append(await backend.command(voice, uuid4().hex, 'voice.append', dict(base,
                sourceId=f'assessment-{index}', role='user', body=body, occurredAt=now(), revision=1,
                metadata={'providerItemId': f'provider-{index}', 'finality': 'final'})))
        await backend.command(voice, uuid4().hex, 'voice.end', dict(base, reason='end_button'))

        observed = (await backend.command(owner['scope'], uuid4().hex, 'job.claim',
                                          {'kind': 'trial.observe'}))['job']
        response = await http.post(root + '/test/job-scope', headers={'X-Test-Token': token}, json=observed)
        response.raise_for_status()
        observe_scope = response.json()['scope']
        candidates = [{
            'anchorEventId': source['eventId'], 'quote': body, 'skillId': skill,
            'topicId': None, 'problemId': None, 'outcome': 'independent_correct',
            'support': 'independent', 'initialOutcome': 'correct', 'contextEventId': None,
            'hintEventId': None, 'rationale': 'Independent utterance in the Trial conversation',
        } for source, (skill, body) in zip(sources, utterances)]
        extractor_model = SimpleNamespace(complete_json=AsyncMock(return_value=json.dumps(
            {'observations': candidates})))
        extracted = await TrialObservationExtractor(backend, extractor_model).extract(
            observe_scope, lesson['id'], observed['input_refs'])
        assert (await backend.command(observe_scope, uuid4().hex, 'job.apply', {
            'jobId': observed['id'], 'fence': observed['fence'], 'result': extracted}))['status'] == 'succeeded'
        observations = (await backend.learning_observations(owner['scope'], lesson_id=lesson['id']))['items']
        grammar = next(row['id'] for row in observations if row['document']['skillId'] == 'grammar')
        speaking = [row['id'] for row in observations if row['document']['skillId'] == 'speaking']
        assert len(speaking) == 2

        assessment_job = (await backend.command(owner['scope'], uuid4().hex, 'job.claim',
                                                {'kind': 'trial.assess'}))['job']
        response = await http.post(root + '/test/job-scope', headers={'X-Test-Token': token}, json=assessment_job)
        response.raise_for_status()
        assessment_scope = response.json()['scope']
        def level(skill, value, ids):
            return {'skillId': skill, 'level': value, 'observationIds': ids,
                    'explanation': 'Provisional model proposal from Trial evidence.'}
        assessment_model = SimpleNamespace(complete_json=AsyncMock(return_value=json.dumps({'levels': [
            level('overall', 'A2', [grammar, *speaking]),
            level('speaking', 'A2', speaking), level('grammar', 'B1', [grammar]),
            level('vocabulary', 'Unknown', []),
        ]})))
        result = await TrialAssessment(backend, assessment_model).assess(
            assessment_scope, lesson['id'], assessment_job['input_refs'])
        assert result['levels']['speaking']['level'] == 'A2'
        assert result['levels']['grammar']['level'] == 'Unknown'
        assert result['levels']['overall']['level'] == 'Unknown'
        assert (await backend.command(assessment_scope, uuid4().hex, 'job.apply', {
            'jobId': assessment_job['id'], 'fence': assessment_job['fence'], 'result': result}))['status'] == 'succeeded'
        levels = (await backend.learning_state(owner['scope']))['profile']['currentLevels']['speaky']
        assert levels['skills']['speaking']['level'] == 'A2'
        assert levels['skills']['grammar']['level'] == 'Unknown'
        assert levels['skills']['listening']['level'] == 'Unknown'
        assert levels['overall']['level'] == 'Unknown'
        assert (await backend.trial_events(owner['scope'], lesson['id']))['assessmentStatus'] == 'succeeded'
