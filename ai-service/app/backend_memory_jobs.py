"""Legacy FIFO queue: fixed source expiry and backend-checked lease tokens."""
from dataclasses import asdict, dataclass, field
from app.memory_jobs import MemoryJob
from app.foundation_client import FoundationClient


@dataclass
class ClaimedMemoryJob(MemoryJob):
    _backend_token: str = field(default='', repr=False, compare=False)


def _payload(job):
    return {key: value for key, value in asdict(job).items() if key != '_backend_token'}


class BackendMemoryJobStore:
    def __init__(self, backend: FoundationClient):
        self.backend = backend

    async def _op(self, action, **args):
        return await self.backend.legacy_memory_queue(action, args)

    async def enqueue(self, job: MemoryJob):
        return (await self._op('enqueue', job=_payload(job)))['added']

    async def sessions(self):
        return (await self._op('sessions'))['sessions']

    async def claim(self, session, token):
        row = (await self._op('claim', session=session, token=token))['job']
        if row is None:
            return None
        return ClaimedMemoryJob(**row, _backend_token=token)

    async def update(self, job):
        await self._op('update', session=job.session, token=job._backend_token, job=_payload(job))

    async def ack(self, job, token):
        result = await self._op('ack', session=job.session, token=token, job=_payload(job))
        return result['saved']

    async def release(self, session, token):
        await self._op('release', session=session, token=token)

    async def pending(self):
        row = await self._op('pending')
        return row['count'], row['age']
