"""Speaky composition root. Legacy app.main is retained but never constructed here."""
from contextlib import asynccontextmanager
from secrets import compare_digest

import httpx
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from openai import AsyncOpenAI

from app.config import Settings
from app.context_builder import ContextBuilder
from app.foundation_api import install_foundation_api
from app.foundation_client import FoundationClient
from app.foundation_tutor import FoundationTutor
from app.learner_model import LearnerModel
from app.learning_engine import LearningEngine, PersonalCandidateExtractor
from app.llm import OpenAiChatModel
from app.logging_setup import configure_process_logging
from app.provider_call_log import configure_user_id_hash_key
from app.tutor import TutorModel


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    if not settings.ai_internal_token or not settings.foundation_backend_url:
        raise RuntimeError('AI_INTERNAL_TOKEN and FOUNDATION_BACKEND_URL are required')
    if not settings.openai_api_key:
        raise RuntimeError('OPENAI_API_KEY is required')
    configure_process_logging(settings.log_level, settings.log_file)
    configure_user_id_hash_key(settings.ai_internal_token)
    http = httpx.AsyncClient(timeout=30)
    model_client = AsyncOpenAI(api_key=settings.openai_api_key, base_url=settings.openai_base_url, max_retries=0)
    backend = FoundationClient(settings.foundation_backend_url, settings.ai_internal_token, http)
    contexts = ContextBuilder(backend)
    tutor = FoundationTutor(backend, TutorModel(OpenAiChatModel(model_client, settings.kora_model)), contexts)
    extractor = PersonalCandidateExtractor(LearnerModel(OpenAiChatModel(model_client, settings.llm_model),
                                                      person_memory_model=settings.person_memory_model))
    learning = LearningEngine(backend, contexts, extractor=extractor)

    @asynccontextmanager
    async def lifespan(_app):
        try:
            yield
        finally:
            await http.aclose()
            await model_client.close()

    app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)
    app.state.settings = settings

    @app.middleware('http')
    async def authenticate(request: Request, call_next):
        if request.url.path != '/health':
            provided = request.headers.get('X-Internal-Token', '')
            if not compare_digest(provided.encode(), settings.ai_internal_token.encode()):
                return JSONResponse({'detail': 'unauthorized'}, status_code=401)
        return await call_next(request)

    @app.get('/health')
    async def health():
        return {'status': 'ok', 'runtime': 'speaky'}

    install_foundation_api(app, tutor, learning)
    return app
