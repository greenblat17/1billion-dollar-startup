"""Scoped access to authoritative backend operations; never opens a database connection."""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import httpx
from app.diagnostics import context_fields, diagnostic_context, event
from app.learning_observations import LearningObservation
from app.learning_content import LearningContentDocument


@dataclass
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
                'X-Diagnostic-Trace': context_fields().get('trace_id', ''),
                'X-Diagnostic-Lesson': context_fields().get('lesson_id', ''),
                'X-Diagnostic-Call': context_fields().get('call_session_id', ''),
            }, timeout=15, **kwargs)
        except httpx.TransportError as error:
            event("backend_failed", error=error, stage="transport", method=method, route=path.split("/operations/")[0])
            # An uncertain write is recovered with its original operation ID, never a new ID.
            raise BackendFailure('transport_outcome_unknown', 503) from None
        try:
            payload = response.json()
        except ValueError as error:
            event("backend_failed", error=error, stage="decode", http_status=response.status_code)
            raise BackendFailure('invalid_backend_response', 502) from None
        if not response.is_success or payload.get('ok') is not True:
            failure = BackendFailure(payload.get('error', 'backend_unavailable'), response.status_code)
            event('backend_failed', error=failure, stage='response')
            raise failure
        result = payload.get('result')
        if not isinstance(result, dict):
            raise BackendFailure('invalid_backend_response', 502)
        return result

    async def snapshot(self, scope: str, purpose: str, lesson_id: str | None = None) -> dict:
        params = {'purpose': purpose}
        if lesson_id is not None:
            params['lessonId'] = lesson_id
        return await self._request('GET', '/snapshot', scope, params=params)

    async def catalog(self, *, version: str | None = None, skill: str | None = None,
                      cefr: str | None = None, topic_id: str | None = None) -> dict:
        """Read shared published curriculum. Pin returned version for subsequent reads in one computation.

        No student scope, inference, local mutable catalogue, or fallback to an unreviewed file.
        """
        params = {key: value for key, value in {
            'version': version, 'skill': skill, 'cefr': cefr, 'topicId': topic_id,
        }.items() if value is not None}
        result = await self._request('GET', '/catalog', '', params=params)
        if result.get('schemaVersion') != 2:
            raise BackendFailure('unsupported_catalog_schema', 502)
        return result

    async def learning_observations(self, scope: str, *, lesson_id: str | None = None,
                                    task_id: str | None = None, skill_id: str | None = None,
                                    topic_id: str | None = None, problem_id: str | None = None,
                                    offset: int = 0) -> dict:
        params = {k: v for k, v in {'lessonId': lesson_id, 'taskId': task_id, 'skillId': skill_id,
                  'topicId': topic_id, 'problemId': problem_id, 'offset': str(offset)}.items() if v is not None}
        return await self._observation_request('/learning-observations', scope, params=params)

    async def learning_observation(self, scope: str, observation_id: str, *, history: bool = False,
                                   offset: int = 0) -> dict:
        from urllib.parse import quote
        path = '/learning-observations/' + quote(observation_id, safe='') + ('/history' if history else '')
        return await self._observation_request(path, scope, params={'offset': str(offset)} if history else {})

    async def _observation_request(self, path: str, scope: str, **kwargs) -> dict:
        result = await self._request('GET', path, scope, **kwargs)
        if result.get('schemaVersion') != 1:
            raise BackendFailure('unsupported_observation_schema', 502)
        return result

    async def record_learning_observation(self, scope: str, operation_id: str, observation: 'LearningObservation') -> dict:
        # Trusted integrations only. No native tool or implicit grading.
        return await self.command(scope, operation_id, 'learning.observation.record', {'observation': observation})

    async def revise_learning_observation(self, scope: str, operation_id: str, *, observation_id: str,
                                         expected_revision: int, observation: 'LearningObservation', reason: str) -> dict:
        return await self.command(scope, operation_id, 'learning.observation.revise', {
            'observationId': observation_id, 'expectedRevision': expected_revision,
            'observation': observation, 'reason': reason})

    async def invalidate_learning_observation(self, scope: str, operation_id: str, *, observation_id: str,
                                             expected_revision: int, component: str, reason: str) -> dict:
        return await self.command(scope, operation_id, 'learning.observation.invalidate', {
            'observationId': observation_id, 'expectedRevision': expected_revision,
            'component': component, 'reason': reason})

    async def learning_tasks(self, scope: str, *, topic_id: str | None = None,
                             problem_id: str | None = None, lifecycle: str | None = None, offset: int = 0) -> dict:
        params = {key: value for key, value in {
            'topicId': topic_id, 'problemId': problem_id, 'lifecycle': lifecycle, 'offset': str(offset),
        }.items() if value is not None}
        return await self._task_request('/learning-tasks', scope, params=params)

    async def learning_task(self, scope: str, task_id: str, *, history: bool = False, offset: int = 0) -> dict:
        from urllib.parse import quote
        path = '/learning-tasks/' + quote(task_id, safe='') + ('/history' if history else '')
        return await self._task_request(path, scope, params={'offset': str(offset)} if history else {})

    async def learning_score(self, scope: str, task_id: str, *, history: bool = False, offset: int = 0) -> dict:
        """Read SP-19A Stage state; real Observation writes are owned by SP-19B."""
        from urllib.parse import quote
        path = '/learning-tasks/' + quote(task_id, safe='') + '/score'
        if history:
            path += '/history'
        result = await self._request('GET', path, scope, params={'offset': str(offset)} if history else {})
        if result.get('schemaVersion') != 1:
            raise BackendFailure('unsupported_score_schema', 502)
        return result

    async def _task_request(self, path: str, scope: str, **kwargs) -> dict:
        result = await self._request('GET', path, scope, **kwargs)
        if result.get('schemaVersion') != 1:
            raise BackendFailure('unsupported_task_schema', 502)
        return result

    async def create_learning_task(self, scope: str, operation_id: str, *, goal_revision: int,
                                   catalog_version: str, skill_id: str, topic_id: str, problem_id: str,
                                   component: str, reason: str | None = None, sources: list[dict] | None = None) -> dict:
        # Trusted integrations only; not exposed as an AI tool.
        args = {'goalRevision': goal_revision, 'catalogVersion': catalog_version, 'skillId': skill_id,
                'topicId': topic_id, 'problemId': problem_id, 'component': component, 'sources': sources or []}
        if reason is not None:
            args['reason'] = reason
        return await self.command(scope, operation_id, 'learning.task.create', args)

    async def change_learning_task(self, scope: str, operation_id: str, *, task_id: str,
                                   expected_revision: int, action: str, component: str, **changes) -> dict:
        if action not in {'update', 'transition', 'progress'}:
            raise ValueError('invalid_task_action')
        return await self.command(scope, operation_id, 'learning.task.' + action, {
            'taskId': task_id, 'expectedRevision': expected_revision, 'component': component, **changes,
        })

    async def trial_events(self, scope: str, lesson_id: str, *, offset: int = 0) -> dict:
        return await self._request('GET', '/trial/events', scope, params={'lessonId': lesson_id, 'offset': offset})

    async def trial_audio(self, scope: str, lesson_id: str, *, cursor: str = '') -> dict:
        return await self._request('GET', '/trial/audio', scope, params={'lessonId': lesson_id, 'cursor': cursor})
    async def learning_plan(self, scope: str, plan_id: str, *, revision: int | None = None,
                            history: bool = False, offset: int = 0) -> dict:
        from urllib.parse import quote
        path = '/learning-plans/' + quote(plan_id, safe='') + ('/history' if history else '')
        params = {'offset': str(offset)} if history else ({'revision': str(revision)} if revision is not None else {})
        return await self._plan_request(path, scope, params=params)

    async def learning_stage(self, scope: str, *, task_id: str, plan_id: str,
                             plan_revision: int, stage_id: str) -> dict:
        from urllib.parse import quote
        path = '/learning-plans/' + quote(plan_id, safe='') + '/stages/' + quote(stage_id, safe='')
        return await self._plan_request(path, scope, params={'taskId': task_id, 'revision': str(plan_revision)})

    async def _plan_request(self, path: str, scope: str, **kwargs) -> dict:
        result = await self._request('GET', path, scope, **kwargs)
        if result.get('schemaVersion') != 1:
            raise BackendFailure('unsupported_plan_schema', 502)
        return result

    async def create_learning_plan(self, scope: str, operation_id: str, *, task_id: str,
                                   expected_task_revision: int, definition: dict, component: str,
                                   current_stage_id: str | None = None) -> dict:
        # Trusted backend integrations only; no AI/native tool and no pedagogical method selection.
        return await self.command(scope, operation_id, 'learning.plan.create', {
            'taskId': task_id, 'expectedTaskRevision': expected_task_revision, 'definition': definition,
            'component': component, 'currentStageId': current_stage_id,
        })

    async def revise_learning_plan(self, scope: str, operation_id: str, *, task_id: str, plan_id: str,
                                   expected_task_revision: int, expected_plan_revision: int,
                                   expected_state_revision: int, definition: dict, component: str,
                                   **changes) -> dict:
        # Omit currentStageId to retain it; explicit None clears it.
        if set(changes) - {'currentStageId'}:
            raise ValueError('invalid_plan_change')
        return await self.command(scope, operation_id, 'learning.plan.revise', {
            'taskId': task_id, 'planId': plan_id, 'expectedTaskRevision': expected_task_revision,
            'expectedPlanRevision': expected_plan_revision, 'expectedStateRevision': expected_state_revision,
            'definition': definition, 'component': component, **changes,
        })

    async def select_learning_stage(self, scope: str, operation_id: str, *, plan_id: str,
                                    expected_plan_revision: int, expected_state_revision: int,
                                    current_stage_id: str | None, reason: str, component: str) -> dict:
        return await self.command(scope, operation_id, 'learning.plan.select-stage', {
            'planId': plan_id, 'expectedPlanRevision': expected_plan_revision,
            'expectedStateRevision': expected_state_revision, 'currentStageId': current_stage_id,
            'reason': reason, 'component': component,
        })

    async def learning_content(self, scope: str, content_id: str, *, revision: int | None = None,
                               history: bool = False, offset: int = 0) -> dict:
        from urllib.parse import quote
        path = '/learning-content/' + quote(content_id, safe='') + ('/history' if history else '')
        params = {'offset': str(offset)} if history else ({'revision': str(revision)} if revision is not None else {})
        result = await self._request('GET', path, scope, params=params)
        if result.get('schemaVersion') != 1:
            raise BackendFailure('unsupported_content_schema', 502)
        return result

    async def create_learning_content(self, scope: str, operation_id: str, *,
                                      document: LearningContentDocument, component: str) -> dict:
        return await self.command(scope, operation_id, 'learning.content.create', {
            'document': document, 'component': component,
        })

    async def revise_learning_content(self, scope: str, operation_id: str, *, content_id: str,
                                      expected_revision: int, document: LearningContentDocument,
                                      component: str, reason: str) -> dict:
        return await self.command(scope, operation_id, 'learning.content.revise', {
            'contentId': content_id, 'expectedRevision': expected_revision, 'document': document,
            'component': component, 'reason': reason,
        })

    async def issue_learning_content(self, scope: str, operation_id: str, *, content_id: str,
                                     expected_revision: int, lesson_id: str) -> dict:
        return await self.command(scope, operation_id, 'learning.content.issue', {
            'contentId': content_id, 'expectedRevision': expected_revision, 'lessonId': lesson_id,
        })

    async def mark_learning_content_defective(self, scope: str, operation_id: str, *, content_id: str,
                                              revision: int, reason: str, component: str) -> dict:
        return await self.command(scope, operation_id, 'learning.content.mark-defective', {
            'contentId': content_id, 'revision': revision, 'reason': reason, 'component': component,
        })

    async def learning_state(self, scope: str) -> dict:
        result = await self._request('GET', '/learning-state', scope)
        if result.get('schemaVersion') != 1:
            raise BackendFailure('unsupported_learning_schema', 502)
        return result

    async def topic_knowledge(self, scope: str, catalog_version: str, *, topic_id: str | None = None,
                              offset: int = 0, history: bool = False) -> dict:
        params = {'catalogVersion': catalog_version, 'offset': str(offset), 'history': str(history).lower()}
        if topic_id is not None:
            params['topicId'] = topic_id
        result = await self._request('GET', '/learning-state/topics', scope, params=params)
        if result.get('schemaVersion') != 1:
            raise BackendFailure('unsupported_learning_schema', 502)
        return result

    async def update_learning_state(self, scope: str, operation_id: str, *, expected_revision: int,
                                    patch: dict, component: str, sources: list[dict], **changes) -> dict:
        # Trusted backend integrations only. No native AI tool exposes this method.
        if set(changes) - {'goal'}:
            raise ValueError('unknown_learning_field')
        return await self.command(scope, operation_id, 'learning.update', {
            'expectedRevision': expected_revision, 'patch': patch, 'component': component,
            'sources': sources, **changes,
        })

    async def record_topic_knowledge(self, scope: str, operation_id: str, *, catalog_version: str,
                                     topic_id: str, expected_revision: int, status: str,
                                     explanation: str, component: str, sources: list[dict]) -> dict:
        return await self.command(scope, operation_id, 'learning.topic.record', {
            'catalogVersion': catalog_version, 'topicId': topic_id, 'expectedRevision': expected_revision,
            'status': status, 'explanation': explanation, 'component': component, 'sources': sources,
        })

    async def command(self, scope: str, operation_id: str, action: str, arguments: dict) -> dict:
        with diagnostic_context(operation_id=operation_id):
            event('backend_command', stage=action, outcome='started')
            result = await self._request('POST', '/commands', scope, json={
                'version': 1, 'operationId': operation_id, 'action': action, 'args': arguments,
            })
            event('backend_command', stage=action, outcome='completed')
            return result

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
