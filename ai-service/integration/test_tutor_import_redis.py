"""Explicit migration suite: real Redis source and PostgreSQL target, synthetic data only."""
import os
import time
from uuid import uuid4
from datetime import datetime, timezone
import httpx
import pytest
from app.foundation_client import FoundationClient, BackendFailure
from app.context_builder import ContextBuilder
from app.foundation_tutor import FoundationTutor
from app.tutor import TutorRequest
from integration.test_foundation_http import GoalModel, actor, now


@pytest.mark.asyncio
async def test_import_existing_tutor_preserves_goal_booking_receipts_and_source_expiry():
    import json
    from app.tutor import TutorRuntime
    from app.tutor_store import TutorStore
    from redis.asyncio import Redis
    from scripts.export_tutor_state import snapshot as export_snapshot
    root, token = os.environ['SP00_TEST_URL'], os.environ['SP00_TEST_TOKEN']
    async with Redis.from_url(os.environ['SP00_IMPORT_REDIS_URL'], decode_responses=True) as redis, httpx.AsyncClient() as http:
        subject = str(time.time_ns())
        session = 'tg-' + subject
        stamp = int(time.time())
        store = TutorStore(redis)
        first = TutorRequest(sessionId=session, requestId='message:1', text='relocation', receivedAt=stamp)
        await TutorRuntime(store, GoalModel()).respond(first)

        class BookingModel:
            count = 0
            async def complete(self, messages):
                self.count += 1
                if self.count == 1:
                    return {'role': 'assistant', 'tool_calls': [{'id': 'book', 'type': 'function', 'function': {
                        'name': 'create_trial_booking', 'arguments': json.dumps({'intent': 'explicit_request', 'evidence': 'book now',
                            'schedule': {'mode': 'now', 'localDateTime': None, 'timezone': None, 'offset': None,
                                         'shiftMinutes': None, 'date': None}})}}]}
                return {'role': 'assistant', 'content': '{"reply":"Записал на пробный урок.","trialOffered":false}'}

        request = TutorRequest(sessionId=session, requestId='message:2', text='book now', receivedAt=stamp + 1)
        answer = await TutorRuntime(store, BookingModel()).respond(request)
        booking = answer['booking']
        await store.record_delivery(session, request.requestId, reply_message_id=100,
                                    booking_id=booking['id'], card_message_id=101, version=booking['version'])
        state = await store.load(session)
        state['personal']['interest'] = {'value': 'cycling', 'kind': 'user_report',
            'source': {'requestId': 'expired-proof', 'text': 'old cycling message', 'at': stamp - 8 * 86400}}
        await store.save(session, state)
        exported = await export_snapshot(redis)
        source = next(item for item in exported if item['subject'] == subject)
        state = json.loads(source['stateJson'])
        headers = {'X-Test-Token': token}
        imported = await http.post(root + '/test/import-tutor', headers=headers, json={'subject': subject, 'state': state})
        imported.raise_for_status()
        repeat = await http.post(root + '/test/import-tutor', headers=headers, json={'subject': subject, 'state': state})
        assert repeat.json() == imported.json()
        changed = await http.post(root + '/test/import-tutor', headers=headers, json={'subject': subject, 'state': {**state, 'revision': state['revision'] + 1}})
        assert changed.json()['error'] == 'import_source_changed'
        backend = FoundationClient(root, token, http)
        operator = await actor(http, root, token, subject)
        snapshot = await backend.snapshot(operator['scope'], 'text')
        assert snapshot['goal']['content']['value'] == 'relocation'
        assert snapshot['booking']['id'] == booking['id']
        assert snapshot['booking']['launch_mode'] == 'placeholder'
        fact = next(fact for fact in snapshot['facts'] if fact['fact_key'] == 'interest')
        assert fact['value'] == 'cycling'
        assert fact['source_content_expired'] is True
        assert all('old cycling message' not in event['body'] for event in snapshot['events'])
        text = await actor(http, root, token, subject, 'text', requestId=request.requestId, text=request.text,
                           occurredAt=datetime.fromtimestamp(request.receivedAt, timezone.utc).isoformat())

        class NeverModel:
            async def complete(self, messages):
                raise AssertionError('imported reply must not call a model')

        recovered = await FoundationTutor(backend, NeverModel(), ContextBuilder(backend)).respond(request, text['scope'])
        assert recovered['booking']['id'] == booking['id']
        assert recovered['reply'] == answer['reply']
