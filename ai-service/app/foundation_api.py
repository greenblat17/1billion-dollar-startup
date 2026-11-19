"""Foundation AI endpoints installed with explicitly injected dependencies."""
import asyncio

from fastapi import FastAPI, Header, HTTPException, Request
from time import perf_counter

from app.diagnostics import event
from app.foundation_client import BackendFailure
from app.foundation_tutor import FoundationTutor
from app.learning_engine import LearningEngine
from app.tutor import TutorRequest


def install_foundation_api(app: FastAPI, tutor: FoundationTutor, learning: LearningEngine):
    # The host application's internal-token middleware authenticates the backend service.
    @app.post('/internal/foundation/tutor/turn')
    async def turn(payload: TutorRequest, x_execution_scope: str = Header(), start: bool = False):
        try:
            return await tutor.respond(payload, x_execution_scope, start)
        except BackendFailure as error:
            error_id = event('foundation_failed', error=error, stage='backend', reason_code='backend_failure')
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
        event('foundation_compute', operation=operation, stage='compute', outcome='started')
        try:
            async with asyncio.timeout(120):
                result = await learning.compute(x_execution_scope, job)
            event('foundation_compute', operation=operation, stage='validate',
                  duration_ms=int((perf_counter() - started) * 1000))
            return result
        except BackendFailure as error:
            error_id = event('foundation_failed', error=error, stage='backend', reason_code='backend_failure')
            raise HTTPException(error.status, detail=error.code, headers={'X-Error-ID': error_id}) from None
        except TimeoutError as error:
            error_id = event('foundation_failed', error=error, operation=operation, stage='compute',
                             reason_code='provider_timeout', http_status=503)
            raise HTTPException(503, detail='provider_timeout', headers={'X-Error-ID': error_id}) from None
        except ValueError as error:
            code, status = {
                'handler_unavailable': ('handler_unavailable', 503),
                'source_expired': ('source_expired', 410),
                'missing_action_template': ('missing_action_template', 422),
            }.get(str(error), ('invalid_job', 400))
            error_id = event('foundation_failed', error=error, operation=operation, stage='validate',
                             reason_code=code, http_status=status, outcome='rejected')
            raise HTTPException(status, detail=code, headers={'X-Error-ID': error_id}) from None
        except Exception as error:
            status, code = (402, 'budget_exhausted') if getattr(error, 'status_code', None) == 402 else (
                503, 'provider_unavailable')
            error_id = event('foundation_failed', error=error, operation=operation, stage='compute',
                             reason_code=code, http_status=status)
            raise HTTPException(status, detail=code, headers={'X-Error-ID': error_id}) from None
