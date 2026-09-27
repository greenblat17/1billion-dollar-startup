from __future__ import annotations

import asyncio
import logging
from contextlib import asynccontextmanager
from typing import Any

from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import JSONResponse, Response
from openai import AsyncOpenAI

from app.config import Settings
from app.dialogue import DialogueStore, build_dialogue_store
from app.jobs import ClipJob, JobStore
from app.llm import OpenAiChatModel
from app.metrics import MetricsStore, build_metrics_store
from app.pipeline import ClipPipeline
from app.realtime import OpenAiRealtimeGateway, RealtimeGateway, TOPICS, VOICES
from app.reminders import build_reminder_ledger, parse_report
from app.review import OpenAiSessionReviewer, SessionReviewer
from app.sessions import GREETING_TEXT, GREETING_VOICE_TEXT
from app.stt import GroqSpeechToText
from app.tts import OpenAiTextToSpeech

logger = logging.getLogger(__name__)

CONTENT_TYPE_OGG = "audio/ogg"
INTERNAL_TOKEN_HEADER = "X-Internal-Token"


def create_app(
    settings: Settings | None = None,
    pipeline: ClipPipeline | None = None,
    realtime: RealtimeGateway | None = None,
    reviewer: SessionReviewer | None = None,
) -> FastAPI:
    settings = settings or Settings.from_env()
    if not settings.ai_internal_token:
        raise RuntimeError("AI_INTERNAL_TOKEN is required")
    logging.basicConfig(level=settings.log_level)
    jobs = JobStore(ttl_seconds=settings.job_ttl_seconds)
    clip_pipeline = pipeline or _build_pipeline(settings)
    sessions = clip_pipeline.dialogue
    streaks = clip_pipeline.streaks
    reminder_ledger = build_reminder_ledger(clip_pipeline.metrics, streaks)
    realtime_gateway = realtime if realtime is not None else _build_realtime(settings)
    session_reviewer = reviewer if reviewer is not None else _build_reviewer(settings)
    greeting_audio: bytes | None = None
    greeting_lock = asyncio.Lock()
    tasks: set[asyncio.Task[None]] = set()

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        await streaks.backfill()
        yield
        await sessions.aclose()
        await clip_pipeline.metrics.aclose()

    app = FastAPI(
        lifespan=lifespan,
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )
    app.state.settings = settings
    app.state.jobs = jobs
    app.state.pipeline = clip_pipeline

    @app.middleware("http")
    async def require_internal_token(request: Request, call_next):
        if request.url.path in {"/health", "/"}:
            return await call_next(request)
        expected = settings.ai_internal_token
        provided = request.headers.get(INTERNAL_TOKEN_HEADER)
        if not expected or provided != expected:
            return JSONResponse({"detail": "unauthorized"}, status_code=401)
        return await call_next(request)

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/internal/metrics")
    async def metrics_snapshot() -> dict:
        payload = await clip_pipeline.metrics.snapshot()
        payload["reminders"] = {
            **await reminder_ledger.snapshot(),
            "forecast": await clip_pipeline.metrics.reminder_forecast(),
        }
        payload["streaks"] = {
            **await streaks.snapshot(),
            "reminderBuckets": await reminder_ledger.week_streak_buckets(),
        }
        marks = await reminder_ledger.chat_marks([chat["sessionId"] for chat in payload["chats"]])
        for chat in payload["chats"]:
            mark = marks.get(chat["sessionId"], {})
            chat["lastReminderAt"] = mark.get("lastReminderAt")
            chat["reminderIgnored"] = mark.get("ignored", 0)
        return payload

    @app.post("/internal/funnel/start")
    async def funnel_start(request: Request) -> dict[str, bool]:
        payload = await _json_object(request)
        session_id = str(payload.get("sessionId") or "").strip()
        if not session_id:
            raise HTTPException(status_code=400, detail="sessionId required")
        source = payload.get("source")
        await clip_pipeline.metrics.record_start(session_id, None if source is None else str(source))
        await _record_profile(clip_pipeline.metrics, session_id, payload)
        return {"ok": True}

    @app.post("/internal/funnel/voice")
    async def funnel_voice(request: Request) -> dict[str, bool]:
        payload = await _json_object(request)
        session_id = str(payload.get("sessionId") or "").strip()
        if not session_id:
            raise HTTPException(status_code=400, detail="sessionId required")
        await clip_pipeline.metrics.record_voice(session_id)
        await reminder_ledger.record_reply(session_id)
        await _record_profile(clip_pipeline.metrics, session_id, payload)
        return {"ok": True}

    @app.post("/internal/reminders/report")
    async def reminders_report(request: Request) -> dict[str, bool]:
        await reminder_ledger.record_report(parse_report(await _json_object(request)))
        return {"ok": True}

    @app.get("/internal/streak/{session_id}")
    async def streak_profile(session_id: str) -> dict[str, Any]:
        return await streaks.profile(session_id)

    @app.post("/internal/reminders/claim")
    async def reminders_claim() -> dict[str, list[dict[str, str | int | None]]]:
        targets = await clip_pipeline.metrics.claim_reminders()
        claimed = []
        for target in targets:
            claimed.append(
                {
                    "sessionId": target.session_id,
                    "name": target.name or None,
                    "streak": await streaks.shown(target.session_id),
                },
            )
        return {"targets": claimed}

    @app.post("/v1/sessions", status_code=201)
    async def create_session(request: Request) -> dict:
        session_id = await sessions.create(await _requested_session_id(request))
        return {"sessionId": session_id, "greeting": {"text": GREETING_TEXT}}

    @app.get("/v1/sessions/{session_id}/greeting/audio")
    async def greeting_audio_route(session_id: str) -> Response:
        if not await sessions.exists(session_id):
            raise HTTPException(status_code=404, detail="unknown session")
        nonlocal greeting_audio
        async with greeting_lock:
            if greeting_audio is None:
                greeting_audio = await clip_pipeline.tts.synthesize(GREETING_VOICE_TEXT)
            return Response(content=greeting_audio, media_type=CONTENT_TYPE_OGG)

    @app.post("/v1/clips", status_code=202)
    async def create_clip(
        sessionId: str = Form(),
        audio: UploadFile = File(),
    ) -> dict[str, str]:
        if not await sessions.exists(sessionId):
            raise HTTPException(status_code=404, detail="unknown session")
        payload = await audio.read()
        if not payload:
            raise HTTPException(status_code=400, detail="empty audio")
        job = jobs.create(sessionId)
        content_type = audio.content_type or "audio/ogg"
        filename = audio.filename or "voice.ogg"
        task = asyncio.create_task(
            _run_job(job, payload, content_type, filename, clip_pipeline, settings.pipeline_timeout_seconds),
        )
        tasks.add(task)
        task.add_done_callback(tasks.discard)
        return {"jobId": job.job_id}

    @app.get("/v1/clips/{job_id}")
    def get_clip(job_id: str) -> JSONResponse:
        job = jobs.get(job_id)
        if job is None:
            raise HTTPException(status_code=404, detail="unknown job")
        return JSONResponse(job.to_status())

    @app.get("/v1/clips/{job_id}/audio")
    def get_audio(job_id: str) -> Response:
        job = jobs.get(job_id)
        if job is None or job.status != "ok" or job.reply_audio is None:
            return Response(status_code=404)
        return Response(content=job.reply_audio, media_type=job.reply_content_type)

    @app.post("/internal/realtime/call")
    async def realtime_call(request: Request) -> dict:
        if realtime_gateway is None:
            raise HTTPException(status_code=503, detail="realtime unavailable")
        payload: Any = await request.json()
        if not isinstance(payload, dict):
            raise HTTPException(status_code=400, detail="invalid body")
        sdp = str(payload.get("sdp") or "")
        topic = str(payload.get("topic") or "").strip()
        voice = str(payload.get("tutorVoice") or "").strip()
        if not sdp.strip() or topic not in TOPICS or voice not in VOICES:
            raise HTTPException(status_code=400, detail="invalid realtime request")
        answer, call_id = await realtime_gateway.start_call(sdp, topic, voice)
        return {"sdp": answer, "openaiCallId": call_id}

    @app.post("/internal/review")
    async def review_call(request: Request) -> dict:
        if session_reviewer is None:
            raise HTTPException(status_code=503, detail="review unavailable")
        payload: Any = await request.json()
        if not isinstance(payload, dict):
            raise HTTPException(status_code=400, detail="invalid body")
        turns_raw = payload.get("turns") or []
        if not isinstance(turns_raw, list):
            raise HTTPException(status_code=400, detail="invalid turns")
        turns = [
            {"role": str(item.get("role") or ""), "text": str(item.get("text") or "")}
            for item in turns_raw
            if isinstance(item, dict)
        ]
        return await session_reviewer.review(turns)

    return app


def _build_pipeline(settings: Settings, dialogue: DialogueStore | None = None) -> ClipPipeline:
    if not settings.groq_api_key:
        raise RuntimeError("GROQ_API_KEY is required")
    if not settings.openai_api_key:
        raise RuntimeError("OPENAI_API_KEY is required")
    groq = AsyncOpenAI(api_key=settings.groq_api_key, base_url=settings.groq_base_url)
    openai_headers = {}
    if "openrouter.ai" in settings.openai_base_url:
        openai_headers = {
            "HTTP-Referer": "https://github.com/greenblat17/sber500xdisrupt-speaking-coach-application",
            "X-Title": "Speaky",
        }
    openai_client = AsyncOpenAI(
        api_key=settings.openai_api_key,
        base_url=settings.openai_base_url,
        default_headers=openai_headers or None,
    )
    metrics = build_metrics_store(settings)
    return ClipPipeline(
        stt=GroqSpeechToText(groq, settings.stt_model, settings.ffmpeg_bin),
        llm=OpenAiChatModel(openai_client, settings.llm_model, metrics=metrics),
        tts=OpenAiTextToSpeech(
            openai_client,
            settings.tts_model,
            settings.tts_voice,
            settings.tts_response_format,
            settings.ffmpeg_bin,
        ),
        dialogue=dialogue or build_dialogue_store(settings),
        metrics=metrics,
    )


def _build_realtime(settings: Settings) -> RealtimeGateway | None:
    if not settings.openai_realtime_api_key:
        return None
    return OpenAiRealtimeGateway(settings.openai_realtime_api_key)


def _build_reviewer(settings: Settings) -> SessionReviewer | None:
    if not settings.openai_api_key:
        return None
    openai_headers = {}
    if "openrouter.ai" in settings.openai_base_url:
        openai_headers = {
            "HTTP-Referer": "https://github.com/greenblat17/sber500xdisrupt-speaking-coach-application",
            "X-Title": "Speaky",
        }
    client = AsyncOpenAI(
        api_key=settings.openai_api_key,
        base_url=settings.openai_base_url,
        default_headers=openai_headers or None,
    )
    return OpenAiSessionReviewer(client, settings.llm_model)


async def _json_object(request: Request) -> dict[str, Any]:
    try:
        payload: Any = await request.json()
    except Exception as error:
        raise HTTPException(status_code=400, detail="invalid body") from error
    if not isinstance(payload, dict):
        raise HTTPException(status_code=400, detail="invalid body")
    return payload


async def _record_profile(metrics: MetricsStore, session_id: str, payload: dict[str, Any]) -> None:
    if "username" not in payload and "name" not in payload:
        return
    username = payload.get("username")
    name = payload.get("name")
    await metrics.record_profile(
        session_id,
        None if username is None else str(username),
        None if name is None else str(name),
    )


async def _requested_session_id(request: Request) -> str | None:
    content_type = request.headers.get("content-type", "")
    if "application/json" not in content_type:
        return None
    try:
        payload: Any = await request.json()
    except Exception:
        return None
    if not isinstance(payload, dict):
        return None
    value = payload.get("sessionId")
    if value is None:
        return None
    text = str(value).strip()
    return text or None


async def _run_job(
    job: ClipJob,
    audio: bytes,
    content_type: str,
    filename: str,
    pipeline: ClipPipeline,
    timeout_seconds: float,
) -> None:
    try:
        result = await asyncio.wait_for(
            pipeline.run(job.session_id, audio, content_type, filename),
            timeout=timeout_seconds,
        )
        job.transcript = result.transcript
        job.reply_text = result.reply_text
        job.notes = list(result.notes)
        job.corrections = [item.to_json() for item in result.corrections]
        job.timings_ms = result.timings_ms
        job.streak = result.streak.to_json() if result.streak is not None else None
        job.reply_audio = result.audio
        job.reply_content_type = CONTENT_TYPE_OGG
        job.status = "ok"
    except Exception as error:
        logger.exception("clip job failed job_id=%s session=%s", job.job_id, job.session_id)
        job.status = "error"
        job.error = {"code": _error_code(error), "message": _public_error(error)}


def _error_code(error: BaseException) -> str:
    if isinstance(error, TimeoutError) or isinstance(error, asyncio.TimeoutError):
        return "timeout"
    return "pipeline_failed"


def _public_error(error: BaseException) -> str:
    text = str(error).strip() or error.__class__.__name__
    if len(text) > 240:
        return text[:240]
    return text
