"""Exercise the Python voice callers and Ktor receivers on the same HTTP exchange."""
import base64
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

from app import trial_sp24
from app.foundation_client import FoundationClient
from app.realtime_voice.session import RealtimeTrialSession
from test_foundation_http import actor, now
from test_trial_v55_http import init_data


CONTRACT = json.loads((Path(__file__).resolve().parents[2] / 'contracts/voice/v1/commands.json').read_text())
JSON_TYPES = {
    'boolean': {bool}, 'integer': {int}, 'number': {int, float},
    'string': {str}, 'object': {dict}, 'null': {type(None)},
}


def assert_fields(label, value, required, optional=None):
    assert type(value) is dict, f'{label}: expected an object'
    missing = required.keys() - value.keys()
    assert not missing, f'{label}: missing {sorted(missing)}'
    fields = required | (optional or {})
    extra = value.keys() - fields.keys()
    assert not extra, f'{label}: unexpected {sorted(extra)}'
    for name, item in value.items():
        spec = fields[name]
        if isinstance(spec, list):
            assert any(type(item) is type(choice) and item == choice for choice in spec), f'{label}.{name}: expected one of {spec}'
        else:
            assert any(type(item) in JSON_TYPES[part] for part in spec.split('|')), f'{label}.{name}: expected {spec}'


@pytest.mark.asyncio
@pytest.mark.parametrize(('kind', 'duration'), [
    ('trial', 15), ('trial', 20), ('trial', 30), ('regular', 30),
], ids=['basic', 'sp24', 'v55', 'regular'])
async def test_voice_commands_match_v1_on_the_wire(kind, duration):
    root, token = os.environ['SP00_TEST_URL'], os.environ['SP00_TEST_TOKEN']
    commands = CONTRACT['commands']
    observed = set()
    v55 = kind == 'trial' and duration in (20, 30)
    sp24 = kind == 'trial' and duration == 20

    async def check_request(request):
        if request.url.path != CONTRACT['path']:
            return
        envelope = json.loads(request.content)
        action = envelope['action']
        if not action.startswith('voice.'):
            return
        assert request.method == CONTRACT['method']
        assert all(request.headers.get(name) for name in CONTRACT['headers'])
        assert_fields(action, envelope, CONTRACT['request'])
        assert envelope['version'] == CONTRACT['version']
        command = commands[action]
        args = envelope['args']
        required = CONTRACT['sharedArgs'] | command['args']
        optional = dict(command.get('optionalArgs', {}))
        if action == 'voice.append' and v55:
            optional.update(command['trialArgs'])
        for field in command.get('producerRequired', []):
            required[field] = optional.pop(field)
        assert_fields(f'{action}.args', args, required, optional)
        if action == 'voice.append' and 'metadata' in args:
            assert_fields(f'{action}.args.metadata', args['metadata'], {}, command['trialMetadata'])

        if action not in observed:
            await check_missing_field(request, envelope, command, required, optional)

    async def check_missing_field(request, envelope, command, required, optional):
        action = envelope['action']
        check = command['missingFieldCheck']
        args = envelope['args'].copy()
        del args[check['field']]
        with pytest.raises(AssertionError, match=f"missing .*{check['field']}"):
            assert_fields(f'{action}.args', args, required, optional)
        response = await raw.request(CONTRACT['method'], str(request.url), headers={
            name: request.headers[name] for name in CONTRACT['headers']
        }, json={**envelope, 'operationId': uuid4().hex, 'args': args})
        error = check['error']
        errors = CONTRACT['sharedErrors'] | command['errors']
        assert response.status_code == errors[error], action
        failure = response.json()
        assert_fields(f'{action}.failure', failure, CONTRACT['failure'])
        assert failure['error'] == error, action

    async def check_response(response):
        request = response.request
        if request.url.path != CONTRACT['path']:
            return
        action = json.loads(request.content)['action']
        if not action.startswith('voice.'):
            return
        assert response.status_code == 200, action
        await response.aread()
        payload = response.json()
        assert_fields(f'{action}.success', payload, CONTRACT['success'])
        command = commands[action]
        result = payload['result']
        expected = command['result']
        if action == 'voice.append':
            expected = expected | command['initialResult']
            if v55:
                expected |= command['trialResult']
        assert_fields(f'{action}.result', result, expected)
        if action == 'voice.heartbeat':
            if v55:
                execution = command['trialExecution']
                if sp24:
                    execution = execution | command['sp24Execution']
                assert_fields(f'{action}.result.trialExecution', result['trialExecution'], execution)
            else:
                assert result['trialExecution'] is None
        if action == 'voice.end':
            assert result['completed'] is v55
        observed.add(action)

    async with httpx.AsyncClient() as raw, httpx.AsyncClient(event_hooks={
        'request': [check_request], 'response': [check_response],
    }) as http:
        backend = FoundationClient(root, token, http)
        subject = str(time.time_ns() // 1000)
        owner_scope = (await actor(http, root, token, subject))['scope']
        lesson = await backend.command(owner_scope, uuid4().hex, 'lesson.create', {
            'kind': kind, 'launchMode': 'executable', 'durationMinutes': duration, 'startsAt': now(), 'timezone': 'UTC',
        })
        if kind == 'regular':
            await backend.command(owner_scope, uuid4().hex, 'access.confirm_payment', {
                'provider': 'test', 'externalId': uuid4().hex, 'validFrom': now(),
                'validUntil': (datetime.now(timezone.utc) + timedelta(days=1)).isoformat(),
            })
            job = (await backend.command(owner_scope, uuid4().hex, 'job.claim', {'kind': 'lesson.prepare'}))['job']
            await backend.command(owner_scope, uuid4().hex, 'job.apply', {
                'jobId': job['id'], 'fence': job['fence'],
                'result': {'objective': 'Practice', 'topic': 'Work', 'strategy': 'Conversation'},
            })
        admitted = await backend._request('POST', '/trial/access', '', json={
            'action': 'open', 'lessonId': lesson['id'], 'attemptId': uuid4().hex, 'initData': init_data(subject),
        })
        socket = SimpleNamespace(send_json=AsyncMock(), send_bytes=AsyncMock())
        store = SimpleNamespace(put=AsyncMock())
        session = RealtimeTrialSession(socket, backend, None, store, admitted,
                                       SimpleNamespace(trial_realtime_model='fixture'))

        await session.renew_voice_lease()
        utterance = 'contract-reply'
        pcm = bytes(480)
        session.trial_response_stage = trial_sp24.Stage.GREETING if sp24 else trial_sp24.Stage.CONVERSATION
        await session.provider_event({'type': 'response.output_item.added',
                                      'item': {'type': 'message', 'id': utterance}})
        await session.provider_event({'type': 'response.output_audio.delta', 'item_id': utterance,
                                      'content_index': 0, 'delta': base64.b64encode(pcm).decode()})
        key, archived = store.put.await_args.args
        assert archived == pcm
        socket.send_bytes.assert_awaited_once_with(pcm)
        await session.provider_event({'type': 'response.output_audio.done', 'item_id': utterance})
        await session.provider_event({'type': 'response.output_audio_transcript.done', 'item_id': utterance,
                                      'transcript': 'Try speaking in full sentences.'})
        await session.provider_event({'type': 'response.done', 'response': {'status': 'completed'}})
        await session.settle(utterance, {'complete': True, 'playedMs': 10})
        objects = (await backend.trial_audio(admitted['scope'], lesson['id']))['objects']
        assert [(item['object_key'], item['metadata']['playbackComplete']) for item in objects] == [(key, True)]

        session.trial_finish_reason = 'learner_requested' if sp24 else 'end_button'
        session.want_finish = True
        await session.finish_if_ready()
        assert session.ended
        assert observed == commands.keys()
