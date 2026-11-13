"""Versioned tutor context; atomic state + operation receipts, independent of voice calls."""
import asyncio
import json
import time
from contextlib import asynccontextmanager
from copy import deepcopy
from uuid import uuid4

from app.dialogue import CONTENT_RETENTION_SECONDS


class TutorStore:
    def __init__(self, redis=None):
        self.redis = redis
        self.memory = {}
        self.locks = {}

    @asynccontextmanager
    async def locked(self, learner):
        # Redis lease outlives the bounded request. No model call runs outside this lease.
        async with self.locks.setdefault(learner, asyncio.Lock()):
            if self.redis is None:
                yield
            else:
                async with self.redis.lock(f'tutor:v1:lock:{learner}', timeout=120, blocking_timeout=65):
                    yield

    async def load(self, learner):
        raw = await self.redis.get(f'tutor:v1:{learner}') if self.redis else None
        state = json.loads(raw) if raw else deepcopy(self.memory.get(learner))
        if state is None:
            state = dict(conversationId=uuid4().hex, learnerId=learner, startedAt=None, revision=0,
                         introduction='getting_acquainted', trialPreference='unknown',
                         personal={}, learning={}, factHistory=[], bookings=[], operations={},
                         history=[], summary='', receipts={}, events=[], cards={}, requests={})
        cutoff = time.time() - CONTENT_RETENTION_SECONDS
        state['history'] = [m for m in state['history'] if m['at'] > cutoff][-40:]
        state['receipts'] = {k: v for k, v in state['receipts'].items() if v['at'] > cutoff}
        state['requests'] = {k: v for k, v in state.get('requests', {}).items() if v['at'] > cutoff}
        # Summary is derived conversational content, subject to the same retention.
        if state.get('summaryAt', 0) <= cutoff:
            state['summary'] = ''
        return state

    async def save(self, learner, state):
        # CAS protects against a worker resuming after its Redis lease expired.
        revision = state['revision']
        updated = dict(state, revision=revision + 1)
        if self.redis:
            from redis.exceptions import WatchError
            key = f'tutor:v1:{learner}'
            async with self.redis.pipeline(transaction=True) as pipe:
                try:
                    await pipe.watch(key)
                    raw = await pipe.get(key)
                    current_revision = json.loads(raw)['revision'] if raw else 0
                    if current_revision != revision:
                        raise RuntimeError('stale_tutor_state')
                    pipe.multi()
                    pipe.set(key, json.dumps(updated, ensure_ascii=False))
                    await pipe.execute()
                except WatchError:
                    raise RuntimeError('concurrent_tutor_write') from None
        else:
            if self.memory.get(learner, {}).get('revision', 0) != revision:
                raise RuntimeError('stale_tutor_state')
            self.memory[learner] = deepcopy(updated)
        state['revision'] = revision + 1

    async def context(self, learner):
        async with self.locked(learner):
            state = await self.load(learner)
            await self.save(learner, state)
            return {k: state[k] for k in ('learnerId', 'personal', 'learning', 'introduction', 'trialPreference', 'summary')} | {'booking': active_booking(state)}

    async def record_delivery(self, learner, request_id, reply_message_id=None, booking_id=None,
                              card_message_id=None, version=None):
        async with self.locked(learner):
            state = await self.load(learner)
            receipt = state['receipts'].get(request_id)
            if receipt is None:
                raise ValueError('unknown_request')
            if reply_message_id is not None:
                receipt['replyMessageId'] = reply_message_id
            if card_message_id is not None:
                expected = receipt['response'].get('booking')
                current = next((b for b in state['bookings'] if b['id'] == booking_id), None)
                if not expected or expected['id'] != booking_id or not current or current['version'] != version:
                    raise ValueError('booking_delivery_mismatch')
                previous = state['cards'].get(booking_id)
                if not previous or previous['version'] <= version:
                    state['cards'][booking_id] = dict(messageId=card_message_id, version=version)
            state['events'].append(dict(type='reply_delivered', requestId=request_id, at=time.time()))
            await self.save(learner, state)

    async def analytics(self, since, until):
        learners = list(self.memory)
        if self.redis:
            learners = [key.removeprefix('tutor:v1:') async for key in self.redis.scan_iter(match='tutor:v1:*')
                        if not key.startswith('tutor:v1:lock:')]
        counts = dict(introductions=0, converted=0, activeBookings=0, cancellations=0, reschedules=0,
                      rebookings=0, offers=0, actionErrors=0, deliveredRequests=0)
        for learner in learners:
            async with self.locked(learner):
                state = await self.load(learner)
                started = state['startedAt']
                if started is None or not since <= started < until:
                    continue
                counts['introductions'] += 1
                counts['converted'] += bool(state['bookings'])
                counts['activeBookings'] += active_booking(state) is not None
                for kind, field in [('cancel_trial_booking', 'cancellations'),
                                    ('reschedule_trial_booking', 'reschedules'), ('trial_rebooked', 'rebookings'),
                                    ('trial_offered', 'offers'), ('action_error', 'actionErrors')]:
                    counts[field] += sum(event['type'] == kind for event in state['events'])
                counts['deliveredRequests'] += len({e['requestId'] for e in state['events'] if e['type'] == 'reply_delivered'})
        return counts | {'conversion': counts['converted'] / counts['introductions'] if counts['introductions'] else None,
                         'cohortFrom': since, 'cohortUntil': until}

    async def prune_all(self):
        learners = list(self.memory)
        if self.redis:
            learners = [key.removeprefix('tutor:v1:') async for key in self.redis.scan_iter(match='tutor:v1:*')
                        if not key.startswith('tutor:v1:lock:')]
        for learner in learners:
            async with self.locked(learner):
                await self.save(learner, await self.load(learner))


def active_booking(state):
    return next((b for b in reversed(state['bookings']) if b['status'] == 'scheduled'), None)
