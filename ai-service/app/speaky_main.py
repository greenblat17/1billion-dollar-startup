"""Speaky composition root. Legacy app.main is retained but never constructed here."""
from contextlib import asynccontextmanager, suppress
import asyncio
from secrets import compare_digest
import ssl

import httpx
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from openai import AsyncOpenAI

from app.diagnostics import diagnostic_context, event, identifier
from uuid import uuid4
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
from app.trial_policy import TrialPlanner, TrialAnalyzer
from app.trial_audio import TrialAudioStore
from app.trial_api import install_trial_api
from app.trial_tools import TrialTutorModel


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    if not settings.ai_internal_token or not settings.foundation_backend_url:
        raise RuntimeError('AI_INTERNAL_TOKEN and FOUNDATION_BACKEND_URL are required')
    if not settings.openai_api_key:
        raise RuntimeError('OPENAI_API_KEY is required')
    configure_process_logging(settings.log_level, settings.log_file)
    configure_user_id_hash_key(settings.ai_internal_token)
    backend_tls = ssl.create_default_context()
    if settings.foundation_backend_ca_file:
        backend_tls.load_verify_locations(cafile=settings.foundation_backend_ca_file)
    http = httpx.AsyncClient(timeout=30, verify=backend_tls)
    model_client = AsyncOpenAI(api_key=settings.openai_api_key, base_url=settings.openai_base_url, max_retries=0)
    backend = FoundationClient(settings.foundation_backend_url, settings.ai_internal_token, http)
    contexts = ContextBuilder(backend)
    voice_model = OpenAiChatModel(model_client, settings.kora_model)
    tutor = FoundationTutor(backend, TrialTutorModel(voice_model), contexts)
    extractor = PersonalCandidateExtractor(LearnerModel(OpenAiChatModel(model_client, settings.llm_model),
                                                      person_memory_model=settings.person_memory_model))
    learning = LearningEngine(backend, contexts, extractor=extractor, planner=TrialPlanner(), analyzer=TrialAnalyzer(voice_model))
    audio_store = TrialAudioStore(settings.trial_audio_bucket, settings.trial_s3_endpoint) if settings.trial_audio_bucket else None

    @asynccontextmanager
    async def lifespan(_app):
        cleanup = asyncio.create_task(audio_store.cleanup(backend)) if audio_store else None
        try:
            yield
        finally:
            if cleanup:
                cleanup.cancel()
                with suppress(asyncio.CancelledError):
                    await cleanup
            await http.aclose()
            await model_client.close()

    app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)
    app.state.settings = settings

    @app.middleware('http')
    async def authenticate(request: Request, call_next):
        if request.url.path != '/health' and not (request.url.path == '/miniapp' or request.url.path.startswith('/miniapp/')):
            provided = request.headers.get('X-Internal-Token', '')
            if not compare_digest(provided.encode(), settings.ai_internal_token.encode()):
                return JSONResponse({'detail': 'unauthorized'}, status_code=401)
        with diagnostic_context(trace_id=identifier(request.headers.get('X-Diagnostic-Trace')) or uuid4().hex,
                                lesson_id=request.headers.get('X-Diagnostic-Lesson'),
                                call_session_id=request.headers.get('X-Diagnostic-Call')):
            try:
                return await call_next(request)
            except Exception as error:
                event('http_failed', error=error, stage='handler')
                raise

    @app.get('/health')
    async def health():
        return {'status': 'ok', 'runtime': 'speaky'}

    install_foundation_api(app, tutor, learning)
    install_trial_api(app, backend, contexts, voice_model, model_client, settings, audio_store)
    return app
