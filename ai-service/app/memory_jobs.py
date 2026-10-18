"""Recoverable, per-session FIFO work for durable personal facts."""
from __future__ import annotations

import asyncio
import json
import logging
import time
from collections import Counter, defaultdict, deque
from dataclasses import dataclass, replace
from threading import Lock
from uuid import uuid4

from redis.asyncio import Redis
from redis.exceptions import WatchError

from app.metrics_v2 import bind_metrics, reset_metrics

logger = logging.getLogger(__name__)
RETENTION_SECONDS = 7 * 24 * 60 * 60
LEASE_SECONDS = 45
_SESSIONS = "memory:queue:sessions"
_OUTCOMES = frozenset({"queued", "started", "retried", "retry_exhausted", "succeeded", "failed", "enqueue_failed", "contact_failed"})
_REASONS = frozenset({
    "none", "expired", "invalid_json", "person_missing", "field_missing", "field_type",
    "field_too_long", "facts_missing", "facts_type", "facts_too_many", "facts_empty",
    "facts_too_long", "provider_timeout", "provider_error", "save_failed", "enqueue_failed",
})


@dataclass
class MemoryJob:
    id: str
    session: str
    transcript: str
    created_at: float
    next_at: float = 0.0
    save_attempts: int = 0
    candidate: dict | None = None
    previous_person: dict | None = None


def _job_key(job_id):
    return f"memory:job:{job_id}"


def _queue_key(session):
    return f"memory:queue:{session}"


def _lease_key(session):
    return f"memory:lease:{session}"


class MemoryJobStore:
    def __init__(self, redis: Redis | None = None):
        self.redis = redis
        self._jobs = {}
        self._queues = defaultdict(deque)
        self._done = set()
        self._leases = {}
        self._lock = asyncio.Lock()

    async def enqueue(self, job: MemoryJob) -> bool:
        if self.redis is not None:
            key, done = _job_key(job.id), f"memory:done:{job.id}"
            for _ in range(3):
                async with self.redis.pipeline(transaction=True) as pipe:
                    try:
                        await pipe.watch(key, done)
                        if await pipe.exists(key, done):
                            return False
                        pipe.multi()
                        pipe.set(key, json.dumps(job.__dict__), ex=RETENTION_SECONDS)
                        pipe.rpush(_queue_key(job.session), job.id)
                        pipe.sadd(_SESSIONS, job.session)
                        await pipe.execute()
                        return True
                    except WatchError:
                        continue
            raise RuntimeError("memory enqueue contention")
        async with self._lock:
            if job.id in self._jobs or job.id in self._done:
                return False
            self._jobs[job.id] = job
            self._queues[job.session].append(job.id)
            return True

    async def sessions(self) -> list[str]:
        if self.redis is not None:
            return list(await self.redis.smembers(_SESSIONS))
        async with self._lock:
            return [key for key, queue in self._queues.items() if queue]

    async def claim(self, session: str, token: str) -> MemoryJob | None:
        if self.redis is not None:
            if not await self.redis.set(_lease_key(session), token, nx=True, ex=LEASE_SECONDS):
                return None
            job_id = await self.redis.lindex(_queue_key(session), 0)
            if job_id is None:
                async with self.redis.pipeline(transaction=True) as pipe:
                    try:
                        await pipe.watch(_queue_key(session))
                        if await pipe.llen(_queue_key(session)) == 0:
                            pipe.multi()
                            pipe.srem(_SESSIONS, session)
                            await pipe.execute()
                    except WatchError:
                        pass
                await self.release(session, token)
                return None
            raw = await self.redis.get(_job_key(job_id))
            if raw is None:
                return MemoryJob(job_id, session, "", 0)
            return MemoryJob(**json.loads(raw))
        async with self._lock:
            if session in self._leases or not self._queues[session]:
                return None
            self._leases[session] = token
            job = self._jobs.get(self._queues[session][0])
            return job or MemoryJob(self._queues[session][0], session, "", 0)

    async def release(self, session: str, token: str) -> None:
        if self.redis is not None:
            key = _lease_key(session)
            async with self.redis.pipeline(transaction=True) as pipe:
                try:
                    await pipe.watch(key)
                    if await pipe.get(key) == token:
                        pipe.multi()
                        pipe.delete(key)
                        await pipe.execute()
                except WatchError:
                    pass
            return
        async with self._lock:
            if self._leases.get(session) == token:
                del self._leases[session]

    async def update(self, job: MemoryJob) -> None:
        if self.redis is not None:
            await self.redis.set(_job_key(job.id), json.dumps(job.__dict__), ex=RETENTION_SECONDS)
        else:
            async with self._lock:
                self._jobs[job.id] = job

    async def ack(self, job: MemoryJob, token: str) -> bool:
        if self.redis is not None:
            queue, lease = _queue_key(job.session), _lease_key(job.session)
            for _ in range(3):
                async with self.redis.pipeline(transaction=True) as pipe:
                    try:
                        await pipe.watch(queue, lease)
                        if await pipe.get(lease) != token or await pipe.lindex(queue, 0) != job.id:
                            return False
                        last = await pipe.llen(queue) == 1
                        pipe.multi()
                        pipe.lpop(queue)
                        pipe.delete(_job_key(job.id))
                        pipe.set(f"memory:done:{job.id}", "1", ex=RETENTION_SECONDS)
                        if last:
                            pipe.srem(_SESSIONS, job.session)
                        pipe.delete(lease)
                        await pipe.execute()
                        return True
                    except WatchError:
                        continue
            raise RuntimeError("memory acknowledgement contention")
        async with self._lock:
            if self._leases.get(job.session) != token or self._queues[job.session][0] != job.id:
                return False
            self._queues[job.session].popleft()
            self._jobs.pop(job.id, None)
            self._done.add(job.id)
            del self._leases[job.session]
            return True

    async def pending(self) -> tuple[int, float]:
        if self.redis is not None:
            sessions = await self.sessions()
            heads = await asyncio.gather(*(self.redis.lindex(_queue_key(s), 0) for s in sessions))
            ages = []
            for job_id in heads:
                if job_id:
                    raw = await self.redis.get(_job_key(job_id))
                    if raw:
                        ages.append(max(0, time.time() - json.loads(raw)["created_at"]))
            lengths = await asyncio.gather(*(self.redis.llen(_queue_key(s)) for s in sessions))
            return sum(lengths), max(ages, default=0.0)
        async with self._lock:
            return sum(map(len, self._queues.values())), max(
                (max(0, time.time() - self._jobs[q[0]].created_at) for q in self._queues.values() if q and q[0] in self._jobs),
                default=0.0)


class MemoryJobMetrics:
    def __init__(self):
        self._lock = Lock()
        self._counts = Counter()

    def record(self, outcome: str, reason: str = "none") -> None:
        outcome = outcome if outcome in _OUTCOMES else "failed"
        reason = reason if reason in _REASONS else "provider_error"
        with self._lock:
            self._counts[outcome, reason] += 1

    def prometheus(self, pending: int, oldest_seconds: float) -> str:
        with self._lock:
            rows = ["# TYPE speaking_memory_jobs_total counter"] + [
                f'speaking_memory_jobs_total{{outcome="{outcome}",reason="{reason}"}} {count}'
                for (outcome, reason), count in sorted(self._counts.items())
            ]
        rows += ["# TYPE speaking_memory_jobs_pending gauge", f"speaking_memory_jobs_pending {pending}",
                 "# TYPE speaking_memory_oldest_pending_seconds gauge",
                 f"speaking_memory_oldest_pending_seconds {oldest_seconds:.3f}"]
        return "\n".join(rows) + "\n"


class MemoryJobRunner:
    def __init__(self, store: MemoryJobStore, personalization, metrics: MemoryJobMetrics | None = None,
                 concurrency: int = 4):
        self.store, self.personalization = store, personalization
        self.metrics = metrics or MemoryJobMetrics()
        self.concurrency = concurrency
        self._running: set[str] = set()
        self._tasks: set[asyncio.Task] = set()
        self._stopping = False

    async def enqueue(self, job_id: str, session: str, transcript: str) -> None:
        try:
            added = await asyncio.wait_for(self.store.enqueue(MemoryJob(job_id, session, transcript, time.time())), 0.25)
        except Exception:
            self.metrics.record("enqueue_failed", "enqueue_failed")
            logger.warning("personal memory enqueue failed job_id=%s", job_id)
            return
        if added:
            self.metrics.record("queued")

    async def run(self) -> None:
        while not self._stopping:
            try:
                for session in await self.store.sessions():
                    if len(self._tasks) >= self.concurrency:
                        break
                    if session in self._running:
                        continue
                    self._running.add(session)
                    task = asyncio.create_task(self._process(session))
                    self._tasks.add(task)
                    task.add_done_callback(lambda done, s=session: (self._tasks.discard(done), self._running.discard(s)))
            except asyncio.CancelledError:
                raise
            except Exception:
                logger.warning("personal memory queue scan failed")
            await asyncio.sleep(0.2)

    async def stop(self) -> None:
        self._stopping = True
        if self._tasks:
            await asyncio.wait(self._tasks, timeout=3)
        for task in self._tasks:
            task.cancel()
        if self._tasks:
            await asyncio.gather(*self._tasks, return_exceptions=True)

    async def _process(self, session: str) -> None:
        token = str(uuid4())
        job = None
        try:
            job = await self.store.claim(session, token)
            if job is None:
                return
            if job.next_at > time.time():
                return
            if not job.transcript or time.time() - job.created_at > RETENTION_SECONDS:
                self.metrics.record("failed", "expired")
                await self.store.ack(job, token)
                return
            self.metrics.record("started")
            metric_context = bind_metrics(session)
            try:
                await asyncio.wait_for(self.personalization.apply_background(
                    session, job.id, job.transcript, job.candidate, job.previous_person,
                    on_retry=lambda reason: self.metrics.record("retried", reason),
                    created_at=job.created_at), 21)
            except Exception as error:
                reason = getattr(error, "reason", "provider_timeout" if isinstance(error, TimeoutError) else "save_failed")
                if reason == "save_failed" and job.save_attempts < 2:
                    await self.store.update(replace(job, save_attempts=job.save_attempts + 1,
                                                    next_at=time.time() + 2 ** job.save_attempts,
                                                    candidate=getattr(error, "candidate", job.candidate),
                                                    previous_person=getattr(error, "previous", job.previous_person)))
                    self.metrics.record("retried", reason)
                else:
                    if reason == "save_failed" and job.save_attempts >= 2:
                        self.metrics.record("retry_exhausted", reason)
                    self.metrics.record("failed", reason)
                    await self.store.ack(job, token)
                return
            finally:
                reset_metrics(metric_context)
            if await self.store.ack(job, token):
                self.metrics.record("succeeded")
        except asyncio.CancelledError:
            raise
        except Exception:
            logger.warning("personal memory worker failed job_id=%s", job.id if job else "unknown")
        finally:
            try:
                await self.store.release(session, token)
            except Exception:
                logger.warning("personal memory lease release failed job_id=%s", job.id if job else "unknown")
