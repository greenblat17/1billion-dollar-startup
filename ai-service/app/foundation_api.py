"""Foundation AI endpoints installed with explicitly injected dependencies."""
from fastapi import FastAPI, Header, HTTPException, Request

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
            raise HTTPException(error.status, detail=error.code) from None

    @app.post('/internal/foundation/compute')
    async def compute(request: Request, x_execution_scope: str = Header()):
        job = await request.json()
        if not isinstance(job, dict):
            raise HTTPException(400, detail='invalid_job')
        try:
            return await learning.compute(x_execution_scope, job)
        except BackendFailure as error:
            raise HTTPException(error.status, detail=error.code) from None
        except ValueError as error:
            if str(error) == 'handler_unavailable':
                raise HTTPException(503, detail='handler_unavailable') from None
            if str(error) == 'source_expired':
                raise HTTPException(410, detail='source_expired') from None
            raise HTTPException(400, detail='invalid_job') from None
        except Exception as error:
            if getattr(error, 'status_code', None) == 402:
                raise HTTPException(402, detail='budget_exhausted') from None
            raise HTTPException(503, detail='provider_unavailable') from None
