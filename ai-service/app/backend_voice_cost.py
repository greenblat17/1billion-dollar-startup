"""Provider lookup stays outside the database; backend owns leases, revisions and cost outbox."""
import asyncio
import logging
from datetime import datetime, timezone
from uuid import uuid4
import httpx

from app.foundation_client import BackendFailure, FoundationClient
from app.metrics_v2 import cost_micro

logger = logging.getLogger(__name__)


class BackendVoiceCostLedger:
    def __init__(self, backend: FoundationClient, base_url: str, api_key: str, *, transport=None):
        self.backend, self.base_url, self.api_key = backend, base_url.rstrip('/'), api_key
        self.transport = transport

    async def record(self, attempt_id, component, provider, model, result, cost, generation_id=None):
        event = dict(callId=str(uuid4()), attemptId=attempt_id, component=component, provider=provider,
                     model=model, result=result, cost=cost, generationId=generation_id,
                     occurredAt=datetime.now(timezone.utc).isoformat())
        return (await self.backend.voice_cost('record', event))['callId']

    async def batch(self, limit=100):
        return (await self.backend.voice_cost('batch', {'limit': limit}))['events']

    async def acknowledge(self, receipts):
        valid = [{'callId': str(item['callId']), 'revision': item['revision']} for item in receipts[:100]
                 if item.get('callId') and type(item.get('revision')) is int]
        await self.backend.voice_cost('acknowledge', {'receipts': valid})

    async def settle_due(self, now=None):
        # Due time is authoritative on the backend, shared by all workers.
        events = (await self.backend.voice_cost('claim', {}))['events']
        async with httpx.AsyncClient(timeout=5, transport=self.transport) as http:
            for event in events:
                cost, retryable = None, True
                try:
                    response = await http.get(self.base_url + '/generation', params={'id': event['generationId']},
                                              headers={'Authorization': 'Bearer ' + self.api_key})
                    if response.status_code == 200:
                        cost = cost_micro((response.json().get('data') or {}).get('total_cost'))
                    elif response.status_code not in (404, 429) and response.status_code < 500:
                        retryable = False
                except (httpx.HTTPError, ValueError):
                    pass
                try:
                    await self.backend.voice_cost('settle', {'callId': event['callId'], 'revision': event['revision'],
                        'fence': event['fence'], 'cost': cost, 'retryable': retryable})
                except BackendFailure as error:
                    if error.code != 'stale_job':
                        raise
        return len(events)

    async def run(self):
        while True:
            try:
                await self.settle_due()
            except asyncio.CancelledError:
                raise
            except Exception:
                logger.warning('voice_cost_settlement_unavailable')
            await asyncio.sleep(5)

    async def aclose(self):
        pass
