"""Composition root for the shipping Speaky AI service."""
from contextlib import asynccontextmanager, suppress
import asyncio
import logging
from secrets import compare_digest
import ssl
from time import perf_counter

import httpx
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from openai import AsyncOpenAI

from app.diagnostics import EventSpec, correlation_headers, diagnostic_context, event
from uuid import uuid4
from app.config import Settings
from app.context_builder import ContextBuilder
from app.memory_context import MemorySelector
from app.foundation_api import install_foundation_api
from app.foundation_client import FoundationClient
from app.foundation_tutor import FoundationTutor
from app.learner_model import LearnerModel
from app.learning_engine import LearningEngine, PersonalCandidateExtractor
from app.llm import OpenAiChatModel
from app.logging_setup import configure_process_logging
from app.trial_policy import TrialAnalyzer
from app.regular_lesson import LessonPlanner
from app.regular_teaching import RegularTeachingPreparation
from app.roadmap import RoadmapPlanner
from app.personalized_roadmap import PersonalizedRoadmap
from app.roadmap_priority import RoadmapPriorityService
from app.trial_audio import TrialAudioStore
from app.trial_api import install_trial_api
from app.trial_tools import TrialTutorModel
from app.trial_observations import TrialObservationExtractor
from app.trial_assessment import TrialAssessment


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    configure_process_logging(settings.log_level, settings.log_file, payload_free=True)
    if not settings.ai_internal_token or not settings.foundation_backend_url:
        event(EventSpec('ai_runtime', 'speaky_runtime', 'ai.runtime', 'configuration', 'failed',
                        'The AI service could not start because Foundation access is unconfigured.',
                        'foundation_configuration_missing', logging.ERROR))
        raise RuntimeError('AI_INTERNAL_TOKEN and FOUNDATION_BACKEND_URL are required')
    if not settings.openai_api_key:
        event(EventSpec('ai_runtime', 'speaky_runtime', 'ai.runtime', 'configuration', 'failed',
                        'The AI service could not start because OpenAI access is unconfigured.',
                        'openai_configuration_missing', logging.ERROR))
        raise RuntimeError('OPENAI_API_KEY is required')
    backend_tls = ssl.create_default_context()
    if settings.foundation_backend_ca_file:
        try:
            backend_tls.load_verify_locations(cafile=settings.foundation_backend_ca_file)
        except (OSError, ValueError) as error:
            event(EventSpec('ai_runtime', 'speaky_runtime', 'ai.runtime', 'configuration', 'failed',
                            'The AI service could not load the Foundation TLS trust configuration.',
                            'foundation_tls_configuration_failed', logging.ERROR), error=error)
            raise
    http = httpx.AsyncClient(timeout=30, verify=backend_tls)
    model_client = AsyncOpenAI(api_key=settings.openai_api_key, base_url=settings.openai_base_url, max_retries=0)
    backend = FoundationClient(settings.foundation_backend_url, settings.ai_internal_token, http)
    voice_model = OpenAiChatModel(model_client, settings.kora_model)
    contexts = ContextBuilder(backend, selector=MemorySelector(voice_model))
    introduction_model = (TrialTutorModel(OpenAiChatModel(model_client, settings.stage1_model))
                          if settings.stage1_model else None)
    tutor = FoundationTutor(backend, TrialTutorModel(voice_model), contexts, introduction_model=introduction_model)
    extractor = PersonalCandidateExtractor(LearnerModel(OpenAiChatModel(model_client, settings.llm_model),
                                                      person_memory_model=settings.person_memory_model))
    learning = LearningEngine(backend, contexts, extractor=extractor, planner=LessonPlanner(voice_model), analyzer=TrialAnalyzer(voice_model), roadmap=RoadmapPlanner(voice_model),
                              trial_observations=TrialObservationExtractor(backend, voice_model),
                              trial_assessment=TrialAssessment(backend, voice_model),
                              personalized_roadmap=PersonalizedRoadmap(backend),
                              roadmap_priority=RoadmapPriorityService(backend),
                              regular_teaching=RegularTeachingPreparation(backend, voice_model))
    audio_store = TrialAudioStore(settings.trial_audio_bucket, settings.trial_s3_endpoint) if settings.trial_audio_bucket else None

    @asynccontextmanager
    async def lifespan(_app):
        cleanup = asyncio.create_task(audio_store.cleanup(backend)) if audio_store else None
        event(EventSpec('ai_runtime', 'speaky_runtime', 'ai.runtime', 'startup', 'completed',
                        'The Speaky AI service is ready to accept requests.'))
        try:
            yield
        finally:
            if cleanup:
                cleanup.cancel()
                with suppress(asyncio.CancelledError):
                    await cleanup
            await http.aclose()
            await model_client.close()
            event(EventSpec('ai_runtime', 'speaky_runtime', 'ai.runtime', 'shutdown', 'completed',
                            'The Speaky AI service closed its runtime clients.'))

    app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)
    app.state.settings = settings

    @app.middleware('http')
    async def authenticate(request: Request, call_next):
        path = request.url.path
        if path == '/health':
            return await call_next(request)
        public = path in ('/miniapp', '/miniapp/api', '/miniapp/diagnostics') or path.startswith('/miniapp/')
        authorized = not public and compare_digest(
            request.headers.get('X-Internal-Token', '').encode(), settings.ai_internal_token.encode())
        correlation = {'trace_id': uuid4().hex, **(correlation_headers(request.headers) if authorized else {})}
        request_id = uuid4().hex
        started = perf_counter()
        with diagnostic_context(**correlation, request_id=request_id):
            try:
                response = (await call_next(request) if authorized or public else
                            JSONResponse({'detail': 'unauthorized'}, status_code=401))
                status = response.status_code
                operation = getattr(request.scope.get('route'), 'name', 'http')
                stage = 'authorize' if status in (401, 403) else 'validate' if status in (400, 413, 422) else 'response'
                error_id = response.headers.get('X-Error-ID')
                fields = frozenset({'http_status', 'duration_ms', 'route_name'})
                if status >= 400:
                    reason = 'unauthorized' if not authorized and not public else f'http_{status}'
                    if reason == 'unauthorized':
                        message = 'The AI service rejected the request because its service token was missing or invalid.'
                    else:
                        message = f'The AI service returned HTTP {status} for the {operation.replace("_", " ")} request.'
                    spec = EventSpec('http_request', 'http', operation, stage,
                                     'rejected' if status < 500 else 'failed', message, reason, logging.WARNING, fields)
                else:
                    spec = EventSpec('http_request', 'http', operation, stage, 'completed',
                                     f'The AI service returned HTTP {status} for the {operation.replace("_", " ")} request.',
                                     fields=fields)
                error_id = event(spec, error_id=error_id, http_status=status,
                                 route_name=getattr(request.state, 'diagnostic_route_name', None),
                                 duration_ms=int((perf_counter() - started) * 1000))
            except asyncio.CancelledError:
                event(EventSpec('http_request', 'http', 'http', 'handler', 'cancelled',
                                'The AI HTTP request was cancelled before a response was sent.',
                                fields=frozenset({'duration_ms', 'route_name'})),
                      route_name=getattr(request.state, 'diagnostic_route_name', None),
                      duration_ms=int((perf_counter() - started) * 1000))
                raise
            except Exception as error:
                error_id = event(EventSpec('http_failed', 'http', 'http', 'handler', 'failed',
                                           'The AI HTTP handler failed unexpectedly.', 'internal_error', logging.ERROR,
                                           frozenset({'duration_ms', 'route_name'})),
                                 error=error, route_name=getattr(request.state, 'diagnostic_route_name', None),
                                 duration_ms=int((perf_counter() - started) * 1000))
                response = JSONResponse({'detail': 'internal_error'}, status_code=500)
            response.headers['X-Diagnostic-Trace'] = correlation['trace_id']
            response.headers['X-Request-ID'] = request_id
            if error_id:
                response.headers['X-Error-ID'] = error_id
            return response

    @app.get('/health')
    async def health():
        return {'status': 'ok', 'runtime': 'speaky'}

    install_foundation_api(app, tutor, learning)
    install_trial_api(app, backend, contexts, voice_model, model_client, settings, audio_store)
    return app
