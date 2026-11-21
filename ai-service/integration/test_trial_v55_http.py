"""Real HTTP storage/admission/raw producer handoff. Provider/Telegram delivery are not used."""
import hashlib
import hmac
import json
import os
import time
from urllib.parse import urlencode
from uuid import uuid4

import httpx
import pytest

from app.foundation_client import FoundationClient, BackendFailure
from test_foundation_http import actor, now


def init_data(subject):
    fields = {'auth_date': str(int(time.time())), 'user': json.dumps({'id': int(subject)})}
    check = '\n'.join(f'{key}={fields[key]}' for key in sorted(fields))
    secret = hmac.new(b'WebAppData', b'123:sp02-local-only', hashlib.sha256).digest()
    fields['hash'] = hmac.new(secret, check.encode(), hashlib.sha256).hexdigest()
    return urlencode(fields)


@pytest.mark.asyncio
async def test_trial_workload_raw_sources_and_terminal_handoff_over_http():
    root, token = os.environ['SP00_TEST_URL'], os.environ['SP00_TEST_TOKEN']
    async with httpx.AsyncClient() as http:
        backend = FoundationClient(root, token, http)
        subject = str(time.time_ns() // 1000)
        owner = await actor(http, root, token, subject)
        lesson = await backend.command(owner['scope'], uuid4().hex, 'lesson.create', {
            'kind': 'trial', 'launchMode': 'executable', 'durationMinutes': 30, 'startsAt': now(), 'timezone': 'UTC'})
        admitted = await backend._request('POST', '/trial/access', '', json={
            'action': 'open', 'lessonId': lesson['id'], 'attemptId': uuid4().hex, 'initData': init_data(subject)})
        scope = admitted['scope']
        base = {'lessonId': lesson['id'], 'callSessionId': admitted['callSessionId']}
        await backend.command(scope, uuid4().hex, 'trial.ready', base)
        source = await backend.command(scope, uuid4().hex, 'voice.append', dict(base, sourceId='user-one', role='user',
            body='I can do three lessons. No homework.', occurredAt=now(), revision=1,
            metadata={'providerItemId': 'item-one', 'finality': 'final'}))
        saved = await backend.command(scope, uuid4().hex, 'trial.preferences', dict(base,
            sourceId=source['eventId'], expectedRevision=0, changes=[
                {'field': 'lessonsPerWeek', 'value': 3, 'evidence': 'three lessons'},
                {'field': 'consent', 'value': 'no', 'evidence': 'No homework'}]))
        assert saved['profile']['intensity']['lessonsPerWeek'] == 3
        assert saved['profile']['homework']['consent'] == 'no'
        raw = await backend.trial_events(scope, lesson['id'])
        event = next(e for e in raw['events'] if e['kind'] == 'transcript')
        assert event['sourceEventId'] == source['eventId']
        assert event['eventId'] == source['rawEventId'] and event['revision'] == 1
        assert event['metadata']['asrConfidence'] is None
        assert event['metadata']['uncertaintySignals'] == []
        assert (await backend.trial_audio(scope, lesson['id']))['objects'] == []
        other = await actor(http, root, token, str(int(subject) + 1))
        with pytest.raises(BackendFailure, match='not_owned'):
            await backend.trial_events(other['scope'], lesson['id'])
        ended = await backend.command(scope, uuid4().hex, 'voice.end', dict(base, reason='end_button'))
        assert ended['completed'] is True
        raw = await backend.trial_events(owner['scope'], lesson['id'])
        assert raw['lesson']['ended_early'] is True
        assert raw['assessmentStatus'] == 'not_started'
        headers = {'X-Internal-Token': token, 'X-Execution-Scope': owner['scope']}
        response = await http.get(root + '/internal/foundation/v1/trial/events', headers=headers,
                                  params={'lessonId': lesson['id'], 'offset': 'invalid'})
        assert response.status_code == 400
