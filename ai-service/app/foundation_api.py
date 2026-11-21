"""Foundation AI endpoints installed with explicitly injected dependencies."""
import asyncio
from asyncio import CancelledError
import logging

from fastapi import FastAPI, Header, HTTPException, Request
from time import perf_counter

from app.diagnostics import EventSpec, event
from app.foundation_client import BackendFailure
from app.foundation_tutor import FoundationTutor
from app.learning_engine import LearningEngine
from app.tutor import TutorRequest


def _record_backend_failure(error: BackendFailure, operation: str):
    if error.code == 'transport_outcome_unknown':
        outcome, reason = 'unknown', 'backend_transport_unknown'
        message = 'Foundation did not confirm the request needed by this operation.'
    elif 400 <= error.status < 500:
        outcome, reason = 'rejected', 'backend_rejected'
        message = 'Foundation rejected the request needed by this operation.'
    else:
        outcome, reason = 'failed', 'backend_failure'
        message = 'Foundation failed to return the result needed by this operation.'
    return event(EventSpec('foundation_failed', 'foundation_api', operation, 'backend', outcome,
                           message, reason, logging.WARNING), error=error)


def install_foundation_api(app: FastAPI, tutor: FoundationTutor, learning: LearningEngine):
    # The host application's internal-token middleware authenticates the backend service.
    @app.post('/internal/foundation/tutor/turn')
    async def turn(payload: TutorRequest, x_execution_scope: str = Header(), start: bool = False):
        try:
            return await tutor.respond(payload, x_execution_scope, start)
        except BackendFailure as error:
            error_id = _record_backend_failure(error, 'foundation.turn')
            raise HTTPException(error.status, detail=error.code, headers={'X-Error-ID': error_id}) from None

    @app.post('/internal/foundation/compute')
    async def compute(request: Request, x_execution_scope: str = Header()):
        try:
            job = await request.json()
        except ValueError:
            raise HTTPException(400, detail='invalid_job') from None
        if not isinstance(job, dict):
            raise HTTPException(400, detail='invalid_job')
        kind = job.get('kind')
        operation = kind if kind in ('trial.observe', 'trial.assess', 'roadmap.generate', 'roadmap.prioritize',
            'roadmap.prepare', 'lesson.evaluate', 'lesson.review', 'lesson.prepare', 'lesson.content.prepare',
            'memory.extract') else 'foundation_compute'
        started = perf_counter()

        def record(stage, outcome, message, reason=None, *, error=None, http_status=None, level=logging.INFO):
            spec = EventSpec('foundation_failed' if error else 'foundation_compute',
                             'foundation_api', operation, stage, outcome, message, reason, level,
                             frozenset({'duration_ms', 'http_status'}))
            return event(spec, error=error, http_status=http_status, duration_ms=int((perf_counter() - started) * 1000))

        record('compute', 'started', f'AI computation started for {operation}.')
        try:
            async with asyncio.timeout(120):
                result = await learning.compute(x_execution_scope, job)
            record('validate', 'completed', f'AI computation returned a result for {operation}.')
            return result
        except CancelledError:
            record('compute', 'cancelled', f'AI computation was cancelled for {operation}.')
            raise
        except BackendFailure as error:
            error_id = _record_backend_failure(error, operation)
            raise HTTPException(error.status, detail=error.code, headers={'X-Error-ID': error_id}) from None
        except TimeoutError as error:
            error_id = record('compute', 'failed', f'AI computation did not finish before its timeout for {operation}.',
                              'provider_timeout', error=error, http_status=503, level=logging.WARNING)
            raise HTTPException(503, detail='provider_timeout', headers={'X-Error-ID': error_id}) from None
        except ValueError as error:
            code, status, reason = {
                'handler_unavailable': ('handler_unavailable', 503, 'its handler was unavailable'),
                'source_expired': ('source_expired', 410, 'its source expired'),
                'missing_action_template': ('missing_action_template', 422, 'the action template was missing'),
            }.get(str(error), ('invalid_job', 400, 'the job data was invalid'))
            error_id = record('validate', 'failed' if status >= 500 else 'rejected',
                              f'AI computation stopped for {operation} because {reason}.',
                              code, error=error, http_status=status, level=logging.WARNING)
            raise HTTPException(status, detail=code, headers={'X-Error-ID': error_id}) from None
        except Exception as error:
            status, code = (402, 'budget_exhausted') if getattr(error, 'status_code', None) == 402 else (
                503, 'provider_unavailable')
            if status == 402:
                error_id = record('compute', 'rejected', f'The provider budget blocked AI computation for {operation}.',
                                  code, error=error, http_status=status, level=logging.WARNING)
            else:
                error_id = record('compute', 'failed', f'AI computation failed while processing {operation}.',
                                  'compute_failed', error=error, http_status=status, level=logging.ERROR)
            raise HTTPException(status, detail=code, headers={'X-Error-ID': error_id}) from None
