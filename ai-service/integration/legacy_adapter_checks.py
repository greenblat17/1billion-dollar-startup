"""Archived PostgreSQL adapter checks; excluded from the Speaky runtime suite."""
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
async def test_legacy_learner_cas_and_trace_retention_use_real_backend():
    import asyncio
    from app.backend_learner_store import BackendLearnerStore

    root, token = os.environ['SP00_TEST_URL'], os.environ['SP00_TEST_TOKEN']
    async with httpx.AsyncClient() as http:
        backend = FoundationClient(root, token, http)
        first, second = BackendLearnerStore(backend), BackendLearnerStore(backend)
        session = 'legacy-' + uuid4().hex
        assert await first.get_learner(session) is None
        original = {'person': {'name': 'Alex'}}
        assert await first.save_learner_if_current(session, None, original)
        outcomes = await asyncio.gather(
            first.save_learner_if_current(session, original, {'person': {'name': 'Alice'}}),
            second.save_learner_if_current(session, original, {'person': {'name': 'Bob'}}),
        )
        assert sorted(outcomes) == [False, True]
        current = await BackendLearnerStore(backend).get_learner(session)
        assert current['person']['name'] in {'Alice', 'Bob'}
        await first.set_goal(session, 15)
        assert await second.get_goal(session) == 15
        await first.save_assessment(session, {'cefr': 'B1'})
        assert await second.get_assessment(session) == {'cefr': 'B1'}
        expired, valid = uuid4().hex, uuid4().hex
        await first.save_vocabulary_trace('call', expired, [{'receivedAt': time.time() - 8 * 86400}], [{'word': 'old'}])
        assert await second.get_vocabulary_trace('call', expired) is None
        await first.save_vocabulary_trace('call', valid, [{'receivedAt': time.time()}], [{'word': 'hello'}])
        assert (await second.get_vocabulary_trace('call', valid))['entries'] == [{'word': 'hello'}]


@pytest.mark.asyncio
async def test_legacy_preferences_survive_adapter_recreation_and_artifacts_keep_deadlines():
    from app.backend_voice_storage import BackendSpeechSpeedStore, BackendCharacterStore, BackendAuditArtifacts, BackendSpeechTimeRollout
    root, token = os.environ['SP00_TEST_URL'], os.environ['SP00_TEST_TOKEN']
    async with httpx.AsyncClient() as http:
        backend = FoundationClient(root, token, http)
        session = 'tg-' + str(time.time_ns())
        characters = BackendCharacterStore(backend, telegram_default='kora_h')
        assert await characters.get(session) == 'kora_h'
        await characters.set(session, 'speaky')
        await BackendSpeechSpeedStore(backend).set(session, 0.8)
        assert await BackendCharacterStore(backend, telegram_default='kora_h').get(session) == 'speaky'
        assert await BackendSpeechSpeedStore(backend).get(session) == 0.8
        rollout = BackendSpeechTimeRollout(backend)
        assert await rollout.activation_day() == await BackendSpeechTimeRollout(backend).activation_day()
        audit = BackendAuditArtifacts(backend)
        expired, valid = uuid4().hex, uuid4().hex
        await audit.record(expired, time.time() - 8 * 86400, 'transcript', 'old')
        assert await audit.get(expired) == {}
        await audit.record(valid, time.time(), 'transcript', 'hello')
        await audit.record(valid, time.time(), 'reply', 'hi')
        assert await BackendAuditArtifacts(backend).get(valid) == {'transcript': 'hello', 'reply': 'hi'}


@pytest.mark.asyncio
async def test_legacy_calls_keep_one_active_call_and_atomic_turn_totals():
    import asyncio
    from app.backend_call_store import BackendCallStore
    from app.backend_learner_store import BackendLearnerStore
    root, token = os.environ['SP00_TEST_URL'], os.environ['SP00_TEST_TOKEN']
    async with httpx.AsyncClient() as http:
        backend = FoundationClient(root, token, http)
        one, two = BackendCallStore(backend), BackendCallStore(backend)
        session = 'tg-' + str(time.time_ns())
        await BackendLearnerStore(backend).set_goal(session, 5)
        first, repeat = await asyncio.gather(one.open(session), two.open(session))
        assert first['callId'] == repeat['callId']
        call_id = first['callId']
        await one.set_character(call_id, 'kora_h')
        await one.save_opening(call_id, 'How was your day?')
        assert await two.mark_opening_delivered(call_id) == (session, 'How was your day?')
        assert await one.mark_opening_delivered(call_id) is None
        assert await one.note_telegram_voice(session, call_id, 100)
        await asyncio.gather(
            one.append_turn(session, 'First', 'Reply', [], 200, [], attempt_id='one'),
            two.append_turn(session, 'Second', 'Reply', [], 150, [], attempt_id='two'),
        )
        await one.append_turn(session, 'First', 'Reply', [], 200, [], attempt_id='one')
        state = await two.get(call_id)
        assert len(state['turns']) == 2
        assert state['seconds'] == 350
        assert (await one.summary(session))['todaySeconds'] == 350
        assert (await one.summary(session))['goalSeconds'] == 300
        assert await two.seal(session) == call_id
        assert await one.summary(session) is None
        assert await two.save_review(call_id, {'summary': 'Practice continues'})
        assert (await one.get(call_id))['review'] == {'summary': 'Practice continues'}
        assert call_id in await two.recent_ids(session)


@pytest.mark.asyncio
async def test_voice_cost_late_ack_cannot_remove_a_new_price_revision():
    import asyncio
    from datetime import timedelta
    from app.backend_voice_cost import BackendVoiceCostLedger
    root, token = os.environ['SP00_TEST_URL'], os.environ['SP00_TEST_TOKEN']
    async with httpx.AsyncClient() as http:
        backend = FoundationClient(root, token, http)
        call_id, generation = uuid4().hex, uuid4().hex
        event = dict(callId=call_id, attemptId='attempt-' + uuid4().hex, component='tts', provider='openrouter',
                     model='test', result='success', cost=None, generationId=generation,
                     occurredAt=(datetime.now(timezone.utc) - timedelta(seconds=30)).isoformat())
        first, repeat = await asyncio.gather(backend.voice_cost('record', event),
            backend.voice_cost('record', {**event, 'callId': uuid4().hex}))
        assert first['callId'] == repeat['callId']
        call_id = first['callId']
        ledger = BackendVoiceCostLedger(backend, 'https://provider.test', 'test-key',
            transport=httpx.MockTransport(lambda request: httpx.Response(200, json={'data': {'total_cost': 0.001}})))
        initial = next(item for item in await ledger.batch() if item['callId'] == call_id)
        await ledger.settle_due()
        await ledger.acknowledge([{'callId': call_id, 'revision': initial['revision']}])
        current = next(item for item in await ledger.batch() if item['callId'] == call_id)
        assert current['revision'] == initial['revision'] + 1
        assert current['priceStatus'] == 'known'
        assert current['costMicroUsd'] == 1000
        await ledger.acknowledge([{'callId': call_id, 'revision': current['revision']}])
        assert all(item['callId'] != call_id for item in await ledger.batch())


@pytest.mark.asyncio
async def test_legacy_dialogue_shared_history_deduplicates_and_generation_uses_a_lease():
    import asyncio
    from app.backend_dialogue import BackendDialogueStore
    root, token = os.environ['SP00_TEST_URL'], os.environ['SP00_TEST_TOKEN']
    async with httpx.AsyncClient() as http:
        backend = FoundationClient(root, token, http)
        first, second = BackendDialogueStore(backend, 40, 2592000), BackendDialogueStore(backend, 40, 2592000)
        session = 'tg-' + str(time.time_ns())
        await first.record_turn(session, 'old speaky', 'reply one')
        await second.record_turn(session + ':kora_h', 'old kora', 'reply two')
        merged = await first.shared_free_history(session)
        assert len(merged) == 4
        await asyncio.gather(first.record_shared_free_turn(session, 'kora_h', 'new', 'answer', 'same-turn'),
                             second.record_shared_free_turn(session, 'kora_h', 'new', 'answer', 'same-turn'))
        assert len(await second.shared_free_history(session)) == 6
        with pytest.raises(BackendFailure, match='operation_conflict'):
            await second.record_shared_free_turn(session, 'kora_h', 'new', 'different answer', 'same-turn')

        async def generate(history, user_text):
            assert history[-1].content == 'reply one'
            with pytest.raises(BackendFailure, match='conversation_busy'):
                await second.complete_turn(session, 'concurrent', generate)
            assert (await backend.voice_preferences('read', session, {}))['speed'] is None
            return 'generated answer'

        assert await first.complete_turn(session, 'fresh', generate) == 'generated answer'
        assert (await second.history(session))[-1].content == 'generated answer'


@pytest.mark.asyncio
async def test_feedback_rating_is_atomic_per_mode_and_answers_cannot_cross_students():
    import asyncio
    from app.backend_feedback import BackendFirstCallFeedback, BackendModeFeedback
    async with httpx.AsyncClient() as http:
        backend = FoundationClient(os.environ['SP00_TEST_URL'], os.environ['SP00_TEST_TOKEN'], http)
        old, modes = BackendFirstCallFeedback(backend), BackendModeFeedback(backend)
        session = 'tg-' + str(time.time_ns())
        one, two = uuid4().hex, uuid4().hex
        await modes.offer(session, one, 'free', character='kora_h')
        await modes.offer(session, two, 'free', character='kora_h')
        ratings = await asyncio.gather(modes.rate(session, one, 'liked'), modes.rate(session, two, 'neutral'))
        assert sorted(row['status'] for row in ratings) == ['ignored', 'rated']
        selected = one if ratings[0]['status'] == 'rated' else two
        assert (await modes.answer(session + '-other', 'secret', selected))['status'] == 'ignored'
        assert (await modes.answer(session, 'useful practice', selected))['status'] == 'saved'
        assert (await modes.answer(session, 'overwrite', selected))['status'] == 'ignored'
        assert (await modes.release(session, selected))['status'] == 'ignored'
        rows = (await modes.list_rated(limit=500))['items']
        own = [row for row in rows if row['sessionId'] == session]
        assert len(own) == 1 and own[0]['message'] == 'useful practice'
        assert len([row for row in (await old.list_rated(limit=500))['items'] if row['sessionId'] == session]) == 1
        assert (await modes.offer(session, uuid4().hex, 'free'))['reason'] == 'already_rated'


@pytest.mark.asyncio
async def test_legacy_memory_queue_preserves_fifo_and_fences_retry_writes():
    from dataclasses import replace
    from app.backend_memory_jobs import BackendMemoryJobStore
    from app.memory_jobs import MemoryJob
    async with httpx.AsyncClient() as http:
        backend = FoundationClient(os.environ['SP00_TEST_URL'], os.environ['SP00_TEST_TOKEN'], http)
        first, second = BackendMemoryJobStore(backend), BackendMemoryJobStore(backend)
        session = 'tg-' + str(time.time_ns())
        job = MemoryJob(uuid4().hex, session, 'retained source', time.time())
        following = MemoryJob(uuid4().hex, session, 'next source', time.time())
        assert await first.enqueue(job)
        assert not await second.enqueue(job)
        assert await second.enqueue(following)
        claim = await first.claim(session, 'old')
        assert claim.id == job.id
        assert await second.claim(session, 'blocked') is None
        await first.release(session, 'old')
        recovered = await second.claim(session, 'new')
        with pytest.raises(BackendFailure):
            await first.update(replace(claim, save_attempts=1))
        assert not await first.ack(claim, 'old')
        await second.update(replace(recovered, save_attempts=1, candidate={'person': {'name': 'Test'}}))
        assert await second.ack(recovered, 'new')
        assert not await first.enqueue(job)
        next_claim = await first.claim(session, 'next')
        assert next_claim.id == following.id
        assert await first.ack(next_claim, 'next')
        assert session not in await first.sessions()
