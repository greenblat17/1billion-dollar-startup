"""The real text tutor produces the pinned scheduling requests for Ktor."""
import json
import os
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from uuid import uuid4

import httpx
import pytest

from app.foundation_client import FoundationClient
from app.foundation_tutor import FoundationTutor
from test_foundation_http import actor, now
from test_voice_command_contract_http import assert_fields


CONTRACT = json.loads((Path(__file__).resolve().parents[2] / 'contracts/scheduling/v1/commands.json').read_text())


@pytest.mark.parametrize('kind', ('trial', 'regular'))
async def test_text_tutor_schedules_the_same_lesson_through_ktor(kind):
    root, token = os.environ['SP00_TEST_URL'], os.environ['SP00_TEST_TOKEN']
    subject = str(time.time_ns())
    wire = []

    async def capture(response):
        request = response.request
        if request.url.path == CONTRACT['path'] and json.loads(request.content)['action'] in (CONTRACT['commands'] | CONTRACT['reads']):
            await response.aread()
            wire.append((request, response))

    async with httpx.AsyncClient(event_hooks={'response': [capture]}) as http, httpx.AsyncClient() as raw:
        backend = FoundationClient(root, token, http)
        tutor = FoundationTutor(backend, None, None)

        async def execute(name, text, args):
            request_id = uuid4().hex
            accepted = await actor(http, root, token, subject, 'text', requestId=request_id, text=text, occurredAt=now())
            scope = accepted['scope']
            source = (await backend.command(scope, uuid4().hex, 'run.claim', {}))['input']
            if kind == 'regular' and 'lessonId' in args:
                listing = await tutor.execute(scope, request_id, source, 'get_lessons', {})
                listing_request, listing_response = wire[-1]
                listing_body = json.loads(listing_request.content)
                assert listing_body['action'] == 'lesson.list'
                assert_fields('lesson.list.args', listing_body['args'], CONTRACT['reads']['lesson.list']['args'])
                assert set(CONTRACT['reads']['lesson.list']['requiredResult']) <= listing.keys()
                assert listing_response.json()['result'] == listing
                assert any(lesson['id'] == args['lessonId'] for lesson in listing['lessons'])
            result = await tutor.execute(scope, request_id, source, name, {'evidence': text, **args})
            assert result['ok'], result
            request, response = wire[-1]
            envelope = json.loads(request.content)
            action = envelope['action']
            command = CONTRACT['commands'][action]
            assert request.method == CONTRACT['method']
            headers = {key: request.headers[key] for key in CONTRACT['headers']}
            assert all(headers.values())
            assert_fields(action, envelope, CONTRACT['request'])
            assert envelope['version'] == CONTRACT['version']
            required = CONTRACT['requestEvidence'] | command[kind]
            assert_fields(action + '.args', envelope['args'], required)
            body = response.json()
            assert_fields(action + '.response', body, CONTRACT['success'])
            lesson = body['result']
            projection = {key: lesson[key] for key in CONTRACT['lesson']}
            assert_fields(action + '.lesson', projection, CONTRACT['lesson'])

            async def send(payload):
                return await raw.post(str(request.url), headers=headers, json=payload)

            repeated = await send(envelope)
            assert repeated.status_code == 200
            assert repeated.json() == body
            assert await backend.receipt(scope, envelope['operationId']) == lesson
            replay = await tutor.execute(scope, request_id, source, name, {'evidence': text, **args})
            assert replay == result

            missing = command['missingFieldCheck']
            invalid = {**envelope['args']}
            del invalid[missing['field']]
            with pytest.raises(AssertionError, match=f"missing .*{missing['field']}"):
                assert_fields(action + '.args', invalid, required)

            async def assert_error(code, arguments):
                failed = await send({**envelope, 'operationId': uuid4().hex, 'args': arguments})
                assert failed.status_code == CONTRACT['errors'][code], failed.text
                assert_fields(action + '.failure', failed.json(), CONTRACT['failure'])
                assert failed.json()['error'] == code

            await assert_error(missing['error'], invalid)
            invalid_evidence = {**envelope['args'], 'evidence': 'not in the current accepted turn'}
            await assert_error('invalid_evidence', invalid_evidence)
            conflict = await send({**envelope, 'args': invalid_evidence})
            assert conflict.status_code == CONTRACT['errors']['operation_conflict']
            assert conflict.json() == {'ok': False, 'error': 'operation_conflict'}
            if action != 'lesson.create':
                await assert_error('version_conflict', envelope['args'])

            await backend.command(scope, uuid4().hex, 'run.complete', {
                'reply': 'Готово.', 'degraded': False, 'bookingId': None,
            })
            return projection

        zone = 'Europe/Moscow'
        if kind == 'trial':
            intent = {'intent': 'explicit_request'}
            created = await execute('create_trial_booking', 'Запиши меня на пробный через час', {
                **intent, 'schedule': {'mode': 'after_minutes', 'delayMinutes': 60, 'timezone': zone},
            })
            moved = await execute('reschedule_trial_booking', 'Перенеси пробный на 30 минут позже', {
                **intent, 'bookingId': created['id'], 'expectedVersion': created['version'],
                'schedule': {'mode': 'shift_minutes', 'shiftMinutes': 30},
            })
            cancelled = await execute('cancel_trial_booking', 'Отмени пробный', {
                **intent, 'bookingId': moved['id'], 'expectedVersion': moved['version'],
            })
        else:
            future = datetime.now(timezone.utc).replace(microsecond=0) + timedelta(hours=1)
            scheduled = {'timing': 'scheduled', 'delayMinutes': None, 'timezone': zone}
            created = await execute('create_extra_lesson', 'Добавь урок через час', {
                **scheduled, 'startsAt': future.isoformat(), 'durationMinutes': 30,
            })
            moved = await execute('reschedule_lesson', 'Перенеси урок на полчаса позже', {
                **scheduled, 'lessonId': created['id'], 'expectedVersion': created['version'],
                'startsAt': (future + timedelta(minutes=30)).isoformat(),
            })
            cancelled = await execute('cancel_lesson', 'Отмени урок', {
                'lessonId': moved['id'], 'expectedVersion': moved['version'],
            })

        assert [created['status'], moved['status'], cancelled['status']] == ['scheduled', 'scheduled', 'cancelled']
        assert created['id'] == moved['id'] == cancelled['id']
        assert moved['version'] == created['version'] + 1
        assert cancelled['version'] == moved['version'] + 1
        assert created['timezone'] == moved['timezone'] == cancelled['timezone'] == zone
        assert datetime.fromisoformat(moved['starts_at']) - datetime.fromisoformat(created['starts_at']) == timedelta(minutes=30)
        owner = await actor(http, root, token, subject)
        stored = await backend.command(owner['scope'], uuid4().hex, 'lesson.get', {'lessonId': cancelled['id']})
        assert {key: stored[key] for key in cancelled} == cancelled
