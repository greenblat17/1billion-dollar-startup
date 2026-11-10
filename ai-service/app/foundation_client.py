"""Scoped access to authoritative backend operations; never opens a database connection."""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import httpx


@dataclass(frozen=True)
class BackendFailure(Exception):
    code: str
    status: int

    def __str__(self) -> str:
        return self.code


class FoundationClient:
    def __init__(self, root: str, service_token: str, http: httpx.AsyncClient):
        self.root = root.rstrip('/') + '/internal/foundation/v1'
        self.service_token = service_token
        self.http = http

    async def _request(self, method: str, path: str, scope: str, **kwargs) -> dict[str, Any]:
        try:
            response = await self.http.request(method, self.root + path, headers={
                'X-Internal-Token': self.service_token, 'X-Execution-Scope': scope,
            }, timeout=15, **kwargs)
        except httpx.TransportError:
            # An uncertain write is recovered with its original operation ID, never a new ID.
            raise BackendFailure('transport_outcome_unknown', 503) from None
        try:
            payload = response.json()
        except ValueError:
            raise BackendFailure('invalid_backend_response', 502) from None
        if not response.is_success or payload.get('ok') is not True:
            raise BackendFailure(payload.get('error', 'backend_unavailable'), response.status_code)
        result = payload.get('result')
        if not isinstance(result, dict):
            raise BackendFailure('invalid_backend_response', 502)
        return result

    async def snapshot(self, scope: str, purpose: str, lesson_id: str | None = None) -> dict:
        params = {'purpose': purpose}
        if lesson_id is not None:
            params['lessonId'] = lesson_id
        return await self._request('GET', '/snapshot', scope, params=params)

    async def command(self, scope: str, operation_id: str, action: str, arguments: dict) -> dict:
        return await self._request('POST', '/commands', scope, json={
            'version': 1, 'operationId': operation_id, 'action': action, 'args': arguments,
        })

    async def receipt(self, scope: str, operation_id: str) -> dict | None:
        return (await self._request('GET', '/operations/' + operation_id, scope)).get('result')

    async def jobs(self, scope: str) -> dict:
        return await self._request('GET', '/jobs', scope)

    async def legacy_learner(self, action: str, session_id: str | None, args: dict) -> dict:
        return await self._request('POST', '/legacy/learners', '', json={
            'action': action, 'sessionId': session_id, 'args': args,
        })

    async def voice_preferences(self, action: str, session_id: str | None, args: dict) -> dict:
        return await self._request('POST', '/legacy/preferences', '', json={
            'action': action, 'sessionId': session_id, 'args': args,
        })

    async def audit_artifact(self, action: str, **args) -> dict:
        return await self._request('POST', '/legacy/artifacts', '', json={'action': action, **args})

    async def legacy_call(self, action: str, session_id: str | None, call_id: str | None, args: dict) -> dict:
        return await self._request('POST', '/legacy/calls', '', json={
            'action': action, 'sessionId': session_id, 'callId': call_id, 'args': args,
        })

    async def voice_cost(self, action: str, args: dict) -> dict:
        return await self._request('POST', '/legacy/voice-cost', '', json={'action': action, 'args': args})

    async def legacy_memory_queue(self, action: str, args: dict) -> dict:
        return await self._request('POST', '/legacy/memory-queue', '', json={'action': action, 'args': args})

    async def legacy_feedback(self, action: str, family: str, session_id: str | None, args: dict) -> dict:
        return await self._request('POST', '/legacy/feedback', '', json={
            'action': action, 'family': family, 'sessionId': session_id, 'args': args})

    async def legacy_dialogue(self, action: str, session_id: str | None, args: dict) -> dict:
        return await self._request('POST', '/legacy/dialogue', '', json={'action': action, 'sessionId': session_id, 'args': args})
