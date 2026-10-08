from __future__ import annotations

import asyncio
import json
import logging
import time
from contextlib import asynccontextmanager
from datetime import date, datetime
from typing import Any
from zoneinfo import ZoneInfo

from fastapi import FastAPI, File, Form, HTTPException, Query, Request, UploadFile
from fastapi.responses import JSONResponse, Response
from openai import AsyncOpenAI
from redis.asyncio import Redis

from app.call_review import CallReviews
from app.audit_artifacts import AuditArtifacts, bind_writer, reset_writer, bind_receipt_time, reset_receipt_time
from app.call_start import CallStarter
from app.calls import CallStore, moscow_day
from app.first_call_feedback import FirstCallFeedback, offer_eligibility
from app.config import Settings
from app.dialogue import DialogueStore, build_dialogue_store
from app.jobs import ClipJob, JobStore
from app.legacy_onboarding_campaign import campaign_status, claim_batch, report_delivery
from app.llm import OpenAiChatModel
from app.metrics import MetricsStore, build_metrics_store
from app.metrics_v2 import build_metrics_v2
from app.operational_metrics import STAGE_METRICS
from app.onboarding import OnboardingService, OnboardingSttError, OnboardingStore
from app.onboarding_model import OnboardingModel
from app.pipeline import ClipPipeline, PipelineResult, bind_job_timings, reset_job_timings
from app.retry import bind_provider_metrics, reset_provider_metrics
from app.realtime import OpenAiRealtimeGateway, RealtimeGateway, TOPICS, VOICES
from app.reminders import build_reminder_ledger, parse_report
from app.review import OpenAiSessionReviewer, SessionReviewer
from app.sessions import GREETING_TEXT, GREETING_VOICE_TEXT
from app.speech import SPEED_CHOICES, SessionSpeech, SpeechSpeedStore
from app.stt import GroqSpeechToText
from app.tts import DeepgramTextToSpeech, OpenAiTextToSpeech, TextToSpeech, TtsAudio

logger = logging.getLogger(__name__)

INTERNAL_TOKEN_HEADER = "X-Internal-Token"


def _provided_internal_token(request: Request) -> str | None:
    direct = request.headers.get(INTERNAL_TOKEN_HEADER)
    if direct is not None:
        return direct
    authorization = request.headers.get("authorization") or ""
    scheme, separator, credential = authorization.partition(" ")
    if separator and scheme.lower() == "bearer" and credential and " " not in credential:
        return credential
    return None


def create_app(
    settings: Settings | None = None,
    pipeline: ClipPipeline | None = None,
    realtime: RealtimeGateway | None = None,
    reviewer: SessionReviewer | None = None,
    onboarding_model: Any = None,
    onboarding_store: OnboardingStore | None = None,
    call_store: CallStore | None = None,
) -> FastAPI:
    settings = settings or Settings.from_env()
    if not settings.ai_internal_token:
        raise RuntimeError("AI_INTERNAL_TOKEN is required")
    logging.basicConfig(level=settings.log_level)
    jobs = JobStore(ttl_seconds=settings.job_ttl_seconds)
    clip_pipeline = pipeline or _build_pipeline(settings)
    onboarding = OnboardingService(
        onboarding_store or OnboardingStore(Redis.from_url(settings.redis_url, decode_responses=True) if settings.redis_url else None),
        clip_pipeline, onboarding_model or OnboardingModel(
            clip_pipeline.llm, settings.onboarding_review_model or settings.llm_model,
            settings.onboarding_review_fallback_model,
        ),
    )
    owns_calls = call_store is None
    calls = call_store or CallStore(
        redis=Redis.from_url(settings.redis_url, decode_responses=True) if settings.redis_url else None,
        goal_of=onboarding.store.get_goal,
    )
    first_call_feedback = FirstCallFeedback(Redis.from_url(settings.redis_url, decode_responses=True) if settings.redis_url else None)
    clip_pipeline.calls = calls
    reviews = CallReviews(calls, onboarding.store, onboarding.model, clip_pipeline.streaks)
    sessions = clip_pipeline.dialogue
    call_starter = CallStarter(calls, onboarding.model, onboarding.personalization, sessions, clip_pipeline.speech)
    streaks = clip_pipeline.streaks
    reminder_ledger = build_reminder_ledger(clip_pipeline.metrics, streaks)
    realtime_gateway = realtime if realtime is not None else _build_realtime(settings)
    session_reviewer = reviewer if reviewer is not None else _build_reviewer(settings, clip_pipeline.metrics)
    greeting_audio: dict[float, TtsAudio] = {}
    greeting_lock = asyncio.Lock()
    campaign_redis = Redis.from_url(settings.redis_url, decode_responses=True) if settings.redis_url else None
    audit_artifacts = AuditArtifacts(Redis.from_url(settings.redis_url, decode_responses=True) if settings.redis_url else None)
    tasks: set[asyncio.Task[None]] = set()

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        await streaks.backfill()
        intro_warmup = asyncio.create_task(onboarding.warm_intro(settings.pipeline_timeout_seconds))
        async def prune_raw_content() -> None:
            while True:
                try:
                    for store in (sessions, onboarding.store, calls):
                        prune = getattr(store, "prune_all", None)
                        if prune is not None:
                            await prune()
                except asyncio.CancelledError:
                    raise
                except Exception:
                    logger.exception("raw conversation retention sweep failed")
                await asyncio.sleep(60 * 60)
        retention_task = asyncio.create_task(prune_raw_content())
        try:
            yield
        finally:
            intro_warmup.cancel()
            retention_task.cancel()
            await asyncio.gather(intro_warmup, retention_task, return_exceptions=True)
        await onboarding.store.aclose()
        if owns_calls:
            await calls.aclose()
        await sessions.aclose()
        await clip_pipeline.speech.speeds.aclose()
        await clip_pipeline.metrics.aclose()
        if campaign_redis is not None:
            await campaign_redis.aclose()
        await audit_artifacts.aclose()
        await first_call_feedback.aclose()
        close_tts = getattr(clip_pipeline.tts, "aclose", None)
        if close_tts is not None:
            await close_tts()

    app = FastAPI(
        lifespan=lifespan,
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )
    app.state.settings = settings
    app.state.jobs = jobs
    app.state.pipeline = clip_pipeline
    app.state.onboarding = onboarding

    @app.middleware("http")
    async def require_internal_token(request: Request, call_next):
        if request.url.path in {"/health", "/"}:
            return await call_next(request)
        expected = settings.ai_internal_token
        provided = _provided_internal_token(request)
        if not expected or provided != expected:
            return JSONResponse({"detail": "unauthorized"}, status_code=401)
        return await call_next(request)

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/internal/metrics")
    async def metrics_snapshot() -> dict:
        payload = await clip_pipeline.metrics.snapshot()
        v2 = getattr(clip_pipeline, "_v2", None)
        if v2 is not None:
            v2_snapshot = await v2.snapshot()
            v2_snapshot["day"] = payload.get("day")
            payload["v2"] = v2_snapshot
        reminders = await reminder_ledger.snapshot()
        activity = await clip_pipeline.metrics.reminder_activity([row["day"] for row in reminders["analyticsDays"]])
        reminders["analyticsDays"] = [
            {**row, **activity.get(row["day"], {})} for row in reminders["analyticsDays"]
        ]
        days = [row["day"] for row in reminders["analyticsDays"]]
        reminders["settingUsers"] = {
            "7": await clip_pipeline.metrics.reminder_activity_unique(days[:7]),
            "30": await clip_pipeline.metrics.reminder_activity_unique(days[:30]),
        }
        reminders["trackingSince"] = await clip_pipeline.metrics.reminder_tracking_since()
        payload["reminders"] = {**reminders, **await clip_pipeline.metrics.reminder_overview()}
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

    @app.get("/internal/metrics/prometheus")
    async def metrics_prometheus() -> Response:
        payload = await clip_pipeline.metrics.snapshot()
        v2 = getattr(clip_pipeline, "_v2", None)
        body = ""
        if v2 is not None:
            v2_snapshot = await v2.snapshot()
            v2_snapshot["day"] = payload.get("day", "")
            body = v2.prometheus(v2_snapshot)
        return Response(content=body + STAGE_METRICS.prometheus(), media_type="text/plain; version=0.0.4")

    @app.post("/internal/metrics/action")
    async def metrics_action(request: Request) -> dict[str, bool]:
        payload = await _json_object(request)
        session_id = str(payload.get("sessionId") or "").strip()
        action = str(payload.get("action") or "").strip()
        v2 = getattr(clip_pipeline, "_v2", None)
        if not session_id or not action or v2 is None:
            raise HTTPException(status_code=400, detail="sessionId and action required")
        platform = payload.get("platform")
        if isinstance(platform, str) and platform.strip():
            await v2.remember_platform(session_id, platform)
        event_id = payload.get("eventId")
        if isinstance(event_id, str):
            await v2.record_telegram_message(session_id, action, event_id)
        await v2.record_action(session_id, action, platform=platform if isinstance(platform, str) else None)
        return {"ok": True}

    @app.get("/internal/metrics/llm")
    async def llm_metrics_range(
        from_day: date = Query(alias="from"), to_day: date = Query(alias="to"),
    ) -> dict:
        today = datetime.now(ZoneInfo("Europe/Moscow")).date()
        if to_day < from_day or to_day > today or (to_day - from_day).days >= 366:
            raise HTTPException(status_code=400, detail="LLM period must be 1-366 days ending no later than today")
        return await clip_pipeline.metrics.llm_range(from_day, to_day)

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

    @app.get("/internal/profile/{session_id}")
    async def progress_profile(session_id: str) -> dict:
        return await onboarding.progress_profile(session_id)

    @app.get("/internal/speech-speed/{session_id}")
    async def speech_speed(session_id: str) -> dict[str, float]:
        return {"speed": await clip_pipeline.speech.speed(session_id)}

    @app.post("/internal/speech-speed")
    async def set_speech_speed(request: Request) -> dict[str, float]:
        payload = await _json_object(request)
        session_id = _call_session_id(payload)
        speed = payload.get("speed")
        if isinstance(speed, bool) or not isinstance(speed, (int, float)) or speed not in SPEED_CHOICES:
            raise HTTPException(status_code=400, detail="speed must be 0.8, 0.9, or 1.0")
        await clip_pipeline.speech.speeds.set(session_id, float(speed))
        return {"speed": float(speed)}

    @app.get("/internal/streak/{session_id}")
    async def streak_profile(session_id: str) -> dict[str, Any]:
        return await streaks.profile(session_id)

    @app.get("/internal/reminders/summary")
    async def reminders_summary() -> dict:
        return await clip_pipeline.metrics.reminder_summary()

    @app.get("/internal/reminders/{session_id}")
    async def reminder_time(session_id: str) -> dict[str, str | None]:
        return {"time": await clip_pipeline.metrics.reminder_time(session_id)}

    @app.post("/internal/reminders/schedule")
    async def reminders_schedule(request: Request) -> dict[str, str]:
        payload = await _json_object(request)
        session_id = str(payload.get("sessionId") or "").strip()
        if not session_id:
            raise HTTPException(status_code=400, detail="sessionId required")
        text = payload.get("text")
        try:
            return await clip_pipeline.metrics.schedule_reminder(
                session_id,
                str(payload.get("action") or ""),
                text=None if text is None else str(text),
                run_id=str(payload.get("runId") or ""),
            )
        except ValueError as error:
            raise HTTPException(status_code=400, detail=str(error)) from error

    @app.post("/internal/reminders/claim")
    async def reminders_claim(request: Request) -> dict[str, list[dict[str, str | int | None]]]:
        mode = "auto"
        if await request.body():
            payload = await _json_object(request)
            mode = str(payload.get("mode") or "auto")
        if mode not in {"auto", "manual"}:
            raise HTTPException(status_code=400, detail="invalid reminder mode")
        targets = await clip_pipeline.metrics.claim_reminders(mode=mode)
        claimed = []
        for target in targets:
            claimed.append(
                {
                    "sessionId": target.session_id,
                    "name": target.name or None,
                    "streak": await streaks.shown(target.session_id),
                    "hour": target.hour,
                },
            )
        return {"targets": claimed}

    @app.get("/internal/campaign/legacy-onboarding")
    async def legacy_campaign_status() -> dict:
        if campaign_redis is None:
            raise HTTPException(status_code=503, detail="campaign requires Redis")
        return await campaign_status(campaign_redis)

    @app.post("/internal/campaign/legacy-onboarding/claim")
    async def legacy_campaign_claim() -> dict[str, list[int]]:
        if campaign_redis is None:
            raise HTTPException(status_code=503, detail="campaign requires Redis")
        return {"chatIds": await claim_batch(campaign_redis, limit=1)}

    @app.post("/internal/campaign/legacy-onboarding/report")
    async def legacy_campaign_report(request: Request) -> dict[str, bool]:
        if campaign_redis is None:
            raise HTTPException(status_code=503, detail="campaign requires Redis")
        payload = await _json_object(request)
        chat_id = payload.get("chatId")
        if isinstance(chat_id, bool) or not isinstance(chat_id, int) or chat_id <= 0:
            raise HTTPException(status_code=400, detail="positive chatId required")
        status = str(payload.get("status") or "")
        if status not in {"sent", "blocked", "failed", "uncertain"}:
            raise HTTPException(status_code=400, detail="invalid status")
        return {"ok": await report_delivery(campaign_redis, chat_id, status)}

    @app.post("/internal/onboarding/state")
    async def onboarding_state(request: Request) -> dict:
        payload = await _json_object(request)
        session_id, request_id = _onboarding_identity(payload)
        reset = str(payload.get("reset") or "")
        if reset not in {"", "start", "force"}:
            raise HTTPException(status_code=400, detail="invalid reset")
        return await onboarding.resolve(session_id, request_id, reset)

    @app.post("/internal/onboarding/legacy-invitation")
    async def legacy_onboarding_invitation(request: Request) -> dict[str, bool]:
        payload = await _json_object(request)
        session_id = _call_session_id(payload)
        action = str(payload.get("action") or "claim")
        if action not in {"claim", "release"}:
            raise HTTPException(status_code=400, detail="invalid action")
        return {"ok": await onboarding.legacy_invitation(session_id, action)}

    @app.post("/internal/calls/open")
    async def calls_open(request: Request) -> dict[str, Any]:
        payload = await _json_object(request)
        return await calls.open(_call_session_id(payload))

    @app.post("/internal/calls/status")
    async def calls_status(request: Request) -> dict[str, Any]:
        payload = await _json_object(request)
        session_id = _call_session_id(payload)
        summary = await calls.summary(session_id)
        current = await calls.get(summary["callId"]) if summary else None
        if current is not None and current.get("day") != moscow_day():
            summary = None
        return {"active": await calls.is_open_today(session_id),
                "callId": summary.get("callId") if summary else None,
                "startedUnix": summary.get("startedUnix") if summary else None,
                "goalSeconds": summary.get("goalSeconds") if summary else None}

    @app.post("/internal/calls/start")
    async def calls_start(request: Request) -> dict[str, Any]:
        payload = await _json_object(request)
        return await call_starter.start(_call_session_id(payload), payload.get("firstName"))

    @app.post("/internal/calls/starter-delivered")
    async def calls_starter_delivered(request: Request) -> dict[str, bool]:
        payload = await _json_object(request)
        call_id = str(payload.get("callId") or "").strip()
        if not call_id:
            raise HTTPException(status_code=400, detail="callId required")
        try:
            await call_starter.delivered(call_id)
        except KeyError as error:
            raise HTTPException(status_code=404, detail="unknown call") from error
        except ValueError as error:
            raise HTTPException(status_code=409, detail=str(error)) from error
        return {"ok": True}

    @app.post("/internal/calls/end")
    async def calls_end(request: Request) -> dict[str, Any]:
        payload = await _json_object(request)
        reason = str(payload.get("reason") or "end_button")
        if reason not in {"end_button", "onboarding_reset"}:
            raise HTTPException(status_code=400, detail="invalid call close reason")
        call_id = await calls.seal(_call_session_id(payload), reason)
        call = await calls.get(call_id) if call_id else None
        return {"callId": call_id, "lastVoiceMessageId": call.get("lastTelegramVoiceId") if call else None,
                "endedUnix": call.get("endedUnix") if call else None, "reason": reason}

    @app.post("/internal/calls/telegram-voice")
    async def calls_telegram_voice(request: Request) -> dict[str, bool]:
        payload = await _json_object(request)
        session_id = _call_session_id(payload)
        call_id = str(payload.get("callId") or "").strip()
        message_id = payload.get("messageId")
        if not call_id or type(message_id) is not int or message_id <= 0:
            raise HTTPException(status_code=400, detail="valid callId and messageId required")
        try:
            first_reply = await calls.note_telegram_voice(session_id, call_id, message_id)
        except ValueError as error:
            raise HTTPException(status_code=409, detail=str(error)) from error
        return {"firstReplyToStarter": first_reply}

    @app.post("/internal/calls/review")
    async def calls_review(request: Request) -> dict[str, Any]:
        payload = await _json_object(request)
        call_id = str(payload.get("callId") or "").strip()
        if not call_id:
            raise HTTPException(status_code=400, detail="callId required")
        try:
            return await reviews.review(call_id)
        except KeyError as error:
            raise HTTPException(status_code=404, detail="unknown call") from error
        except ValueError as error:
            raise HTTPException(status_code=409, detail="numeric baseline required") from error

    @app.post("/internal/calls/feedback")
    async def calls_feedback(request: Request) -> dict[str, str]:
        payload = await _json_object(request)
        session_id = _call_session_id(payload)
        action = str(payload.get("action") or "")
        call_id = str(payload.get("callId") or "")
        if action == "offer":
            call = await calls.get(call_id)
            onboarding_state = await onboarding.store.get(session_id)
            ineligible = offer_eligibility(call, onboarding_state, session_id)
            if ineligible is not None:
                return {"status": "ignored", "reason": ineligible}
            return await first_call_feedback.offer(session_id, call_id, str(payload.get("username") or ""))
        if action == "rate":
            try:
                return await first_call_feedback.rate(session_id, call_id, str(payload.get("choice") or ""))
            except ValueError as error:
                raise HTTPException(status_code=400, detail=str(error)) from error
        if action == "answer":
            value = payload.get("text")
            if not isinstance(value, str):
                raise HTTPException(status_code=400, detail="text required")
            return await first_call_feedback.answer(session_id, text=value)
        if action == "skip":
            return await first_call_feedback.answer(session_id, call_id=call_id)
        raise HTTPException(status_code=400, detail="invalid feedback action")

    @app.get("/internal/calls/feedback")
    async def calls_feedback_list(offset: int = Query(0, ge=0, le=10_000),
                                  limit: int = Query(25, ge=1, le=50)) -> dict[str, Any]:
        return await first_call_feedback.list_rated(offset, limit)

    @app.post("/internal/onboarding/goal")
    async def onboarding_goal(request: Request) -> dict:
        payload = await _json_object(request)
        session_id, _request_id = _onboarding_identity(payload)
        minutes = payload.get("minutes")
        if type(minutes) is not int or minutes not in {0, 5, 10, 15}:
            raise HTTPException(status_code=400, detail="invalid practice goal")
        return await onboarding.set_goal(session_id, minutes)

    @app.post("/internal/onboarding/actions", status_code=202)
    async def onboarding_action(request: Request) -> dict:
        payload = await _json_object(request)
        session_id, request_id = _onboarding_identity(payload)
        run_id = str(payload.get("runId") or "")
        action = str(payload.get("action") or "")
        if not run_id or action not in {"begin", "retry", "continue", "short"}:
            raise HTTPException(status_code=400, detail="invalid action")
        await sessions.create(session_id)
        job = jobs.create(session_id)
        task = asyncio.create_task(_run_onboarding_action(job, onboarding, run_id, action, settings.pipeline_timeout_seconds, request_id))
        tasks.add(task)
        task.add_done_callback(tasks.discard)
        return {"jobId": job.job_id}

    @app.post("/v1/sessions", status_code=201)
    async def create_session(request: Request) -> dict:
        session_id = await sessions.create(await _requested_session_id(request))
        return {"sessionId": session_id, "greeting": {"text": GREETING_TEXT}}

    @app.get("/v1/sessions/{session_id}/greeting/audio")
    async def greeting_audio_route(session_id: str) -> Response:
        if not await sessions.exists(session_id):
            raise HTTPException(status_code=404, detail="unknown session")
        speed = await clip_pipeline.speech.speed(session_id)
        async with greeting_lock:
            if speed not in greeting_audio:
                greeting_audio[speed] = await clip_pipeline.speech.synthesize(
                    session_id, GREETING_VOICE_TEXT, speed=speed,
                )
            audio = greeting_audio[speed]
            return Response(content=audio.data, media_type=audio.content_type)

    @app.post("/v1/clips", status_code=202)
    async def create_clip(
        sessionId: str = Form(),
        audio: UploadFile = File(),
        onboardingRunId: str | None = Form(default=None),
        requestId: str | None = Form(default=None),
        attemptId: str | None = Form(default=None),
        receivedAtEpoch: float | None = Form(default=None),
        durationSeconds: float = Form(default=0),
    ) -> dict[str, str]:
        if not await sessions.exists(sessionId):
            raise HTTPException(status_code=404, detail="unknown session")
        payload = await audio.read()
        if not payload:
            raise HTTPException(status_code=400, detail="empty audio")
        if onboardingRunId and not requestId:
            raise HTTPException(status_code=400, detail="requestId required for onboarding")
        if attemptId is not None:
            from uuid import UUID
            try:
                attemptId = str(UUID(attemptId))
            except ValueError:
                raise HTTPException(status_code=400, detail="invalid attemptId") from None
        if receivedAtEpoch is not None and not (0 < receivedAtEpoch <= time.time() + 60):
            raise HTTPException(status_code=400, detail="invalid receivedAtEpoch")
        job = jobs.create(sessionId, attemptId)
        content_type = audio.content_type or "audio/ogg"
        filename = audio.filename or "voice.ogg"
        task = asyncio.create_task(
            _run_job(job, payload, content_type, filename, clip_pipeline, settings.pipeline_timeout_seconds,
                     onboarding, onboardingRunId, requestId, durationSeconds, audit_artifacts, receivedAtEpoch),
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

    @app.get("/internal/audit/attempt/{attempt_id}")
    async def audit_attempt(attempt_id: str) -> dict[str, str]:
        from uuid import UUID
        try:
            normalized = str(UUID(attempt_id))
        except ValueError:
            raise HTTPException(status_code=400, detail="invalid attempt ID") from None
        return await audit_artifacts.get(normalized)

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
        session_id = str(payload.get("sessionId") or "").strip()
        platform = str(payload.get("platform") or "").strip()
        v2 = getattr(clip_pipeline, "_v2", None)
        if v2 is not None and session_id and platform:
            await v2.remember_platform(session_id, platform)
        if call_id and session_id and settings.openai_realtime_api_key:
            base = getattr(realtime_gateway, "_base_url", "https://api.openai.com/v1")
            asyncio.create_task(_watch_realtime(base, settings.openai_realtime_api_key, call_id, session_id, v2))
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
    if settings.tts_provider not in {"openrouter", "deepgram"}:
        raise RuntimeError("TTS_PROVIDER must be openrouter or deepgram")
    if settings.tts_provider == "deepgram" and not settings.deepgram_api_key:
        raise RuntimeError("DEEPGRAM_API_KEY is required when TTS_PROVIDER=deepgram")
    if settings.tts_output_format not in {"ogg", "mp3"}:
        raise RuntimeError("TTS_OUTPUT_FORMAT must be ogg or mp3")
    if settings.tts_provider == "deepgram" and (
        not 0.7 <= settings.tts_speed <= 1.5
        or abs(settings.tts_speed * 20 - round(settings.tts_speed * 20)) > 1e-9
    ):
        raise RuntimeError("TTS_SPEED must be between 0.7 and 1.5 in 0.05 increments for Deepgram")
    groq = AsyncOpenAI(api_key=settings.groq_api_key, base_url=settings.groq_base_url, max_retries=0)
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
        max_retries=0,
    )
    tts: TextToSpeech
    if settings.tts_provider == "deepgram":
        tts = DeepgramTextToSpeech(
            settings.deepgram_api_key,
            output_format=settings.tts_output_format,
            speed=settings.tts_speed,
            ffmpeg_bin=settings.ffmpeg_bin,
        )
    else:
        tts = OpenAiTextToSpeech(
            openai_client,
            settings.tts_model,
            settings.tts_voice,
            settings.tts_response_format,
            settings.ffmpeg_bin,
        )
    metrics = build_metrics_store(settings)
    v2 = build_metrics_v2(settings)
    if hasattr(tts, "_v2"):
        tts._v2 = v2
    model = OpenAiChatModel(openai_client, settings.llm_model, metrics=metrics, notes_model=settings.notes_model)
    model._v2 = v2
    speech = SessionSpeech(
        tts,
        SpeechSpeedStore(Redis.from_url(settings.redis_url, decode_responses=True) if settings.redis_url else None),
        default_speed=settings.tts_speed,
    )
    return ClipPipeline(
        stt=GroqSpeechToText(groq, settings.stt_model, settings.ffmpeg_bin),
        llm=model,
        tts=tts,
        speech=speech,
        dialogue=dialogue or build_dialogue_store(settings),
        metrics=metrics,
        v2=v2,
    )


def _build_realtime(settings: Settings) -> RealtimeGateway | None:
    if not settings.openai_realtime_api_key:
        return None
    return OpenAiRealtimeGateway(settings.openai_realtime_api_key)


def _build_reviewer(settings: Settings, metrics: MetricsStore) -> SessionReviewer | None:
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
        max_retries=0,
    )
    return OpenAiSessionReviewer(client, settings.llm_model, metrics=metrics)


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
    onboarding: OnboardingService | None = None,
    run_id: str | None = None,
    request_id: str | None = None,
    duration: float = 0.0,
    audit_artifacts: AuditArtifacts | None = None,
    received_at_epoch: float | None = None,
) -> None:
    started = time.perf_counter()
    provider_metrics_token = bind_provider_metrics(pipeline.metrics)
    job_timings: dict[str, int] = {}
    timings_token = bind_job_timings(job_timings)
    async def audit_stage(field: str, value: str) -> None:
        if field == "transcript":
            job.transcript = value
        elif field == "reply":
            job.reply_text = value
        if job.attempt_id and audit_artifacts is not None:
            await audit_artifacts.record(job.attempt_id, received_at_epoch or time.time(), field, value)
    audit_token = bind_writer(audit_stage if job.attempt_id else None)
    receipt_token = bind_receipt_time(received_at_epoch)
    stage = "onboarding" if onboarding is not None and run_id else "stt"

    def set_stage(value: str) -> None:
        nonlocal stage
        stage = value

    try:
        result = await asyncio.wait_for(
            onboarding.turn(job.session_id, run_id, request_id or "", audio, content_type, filename, duration)
            if onboarding is not None and run_id else pipeline.run(job.session_id, audio, content_type, filename, on_stage=set_stage),
            timeout=timeout_seconds,
        )
        if result.transcript and job.transcript is None:
            await audit_stage("transcript", result.transcript)
        if result.reply_text:
            await audit_stage("reply", result.reply_text)
        _complete_job(job, result)
        await _record_clip_result(pipeline.metrics, None, job_id=job.job_id, attempt_id=job.attempt_id)
        logger.info(
            "clip job complete job_id=%s attempt_id=%s session=%s job_ms=%d timings_ms=%s",
            job.job_id, job.attempt_id, job.session_id, int((time.perf_counter() - started) * 1000), result.timings_ms,
        )
    except Exception as error:
        logger.exception("clip job failed job_id=%s attempt_id=%s session=%s", job.job_id, job.attempt_id, job.session_id)
        job.status = "error"
        job.timings_ms = job_timings.copy()
        code = _error_code(error)
        reason = _error_reason(error)
        job.error = {"code": code, "message": _public_error(error)}
        failed_stage = "stt" if code == "onboarding_stt_failed" else getattr(error, "_speaky_stage", stage)
        job.error.update(stage=failed_stage, reason=reason)
        await _record_clip_result(pipeline.metrics, code, failed_stage, error, job.session_id,
                                  job_id=job.job_id, attempt_id=job.attempt_id, reason=reason)
    finally:
        reset_writer(audit_token)
        reset_receipt_time(receipt_token)
        reset_job_timings(timings_token)
        reset_provider_metrics(provider_metrics_token)


async def _record_clip_result(
    metrics: MetricsStore,
    code: str | None,
    stage: str | None = None,
    error: Exception | None = None,
    session_id: str | None = None,
    job_id: str | None = None,
    attempt_id: str | None = None,
    reason: str | None = None,
) -> None:
    try:
        message = None if error is None else f"{type(error).__name__}: {error}"
        await metrics.record_clip_result(code, session_id=session_id, stage=stage, message=message,
                                         job_id=job_id, attempt_id=attempt_id, reason=reason)
    except Exception:
        logger.exception("clip result metric failed")


async def _watch_realtime(base_url: str, api_key: str, call_id: str, session_id: str, v2) -> None:
    if v2 is None:
        return
    try:
        import websockets
    except ImportError:
        logger.warning("websockets is not installed; realtime token usage is not recorded")
        return
    url = base_url.replace("https://", "wss://").replace("http://", "ws://").rstrip("/")
    url = f"{url}/realtime?call_id={call_id}"
    try:
        async with websockets.connect(url, additional_headers={"Authorization": f"Bearer {api_key}"}) as socket:
            async for raw in socket:
                event = json.loads(raw)
                kind = event.get("type")
                if kind == "response.done":
                    usage = (event.get("response") or {}).get("usage") or {}
                    details_in = usage.get("input_token_details") or {}
                    details_out = usage.get("output_token_details") or {}
                    cached = details_in.get("cached_tokens_details") or {}
                    model = (event.get("response") or {}).get("model") or "gpt-realtime"
                    await v2.record_realtime(session_id, str(model), {
                        "in_text": details_in.get("text_tokens") or 0,
                        "in_audio": details_in.get("audio_tokens") or 0,
                        "out_text": details_out.get("text_tokens") or 0,
                        "out_audio": details_out.get("audio_tokens") or 0,
                        "cached_text": cached.get("text_tokens") or 0,
                        "cached_audio": cached.get("audio_tokens") or 0,
                    })
                elif kind == "conversation.item.input_audio_transcription.completed":
                    usage = event.get("usage") or {}
                    if usage:
                        amount = usage.get("seconds") or usage.get("total_tokens") or 0
                        await v2.record_transcript(session_id, str(usage.get("model") or "transcript"), int(amount or 0))
    except websockets.ConnectionClosedOK:
        return
    except Exception:
        logger.warning("realtime usage socket closed call_id=%s", call_id)
        await v2.record_error(session_id, "realtime", "socket")


def _error_code(error: BaseException) -> str:
    if isinstance(error, OnboardingSttError):
        return "onboarding_stt_failed"
    if isinstance(error, TimeoutError) or isinstance(error, asyncio.TimeoutError):
        return "timeout"
    return "pipeline_failed"


def _error_reason(error: BaseException) -> str:
    if isinstance(error, OnboardingSttError) and error.__cause__ is not None:
        return _error_reason(error.__cause__)
    if isinstance(error, (TimeoutError, asyncio.TimeoutError)) or "timeout" in type(error).__name__.lower():
        return "timeout"
    status = getattr(error, "status_code", None)
    if status is None:
        status = getattr(getattr(error, "response", None), "status_code", None)
    if status == 429:
        return "rate_limit"
    if isinstance(status, int) and 500 <= status < 600:
        return "provider_5xx"
    if isinstance(status, int) and 400 <= status < 500:
        return "provider_4xx"
    name = type(error).__name__.lower()
    if any(word in name for word in ("connect", "network", "socket")):
        return "network"
    if isinstance(error, (ValueError, TypeError)):
        return "invalid_input"
    return "internal"


def _public_error(error: BaseException) -> str:
    text = str(error).strip() or error.__class__.__name__
    if len(text) > 240:
        return text[:240]
    return text


def _call_session_id(payload: dict[str, Any]) -> str:
    session_id = str(payload.get("sessionId") or "").strip()
    if not session_id:
        raise HTTPException(status_code=400, detail="sessionId required")
    return session_id


def _onboarding_identity(payload: dict) -> tuple[str, str]:
    session_id = str(payload.get("sessionId") or "").strip()
    request_id = str(payload.get("requestId") or "").strip()
    if not session_id or not request_id:
        raise HTTPException(status_code=400, detail="sessionId and requestId required")
    return session_id, request_id


async def _run_onboarding_action(job: ClipJob, service: OnboardingService, run_id: str, action: str, timeout: float, request_id: str) -> None:
    try:
        result = await asyncio.wait_for(service.action(job.session_id, run_id, action, request_id), timeout=timeout)
        _complete_job(job, result)
    except Exception as error:
        logger.exception("onboarding action failed job=%s", job.job_id)
        job.status = "error"
        job.error = {"code": _error_code(error), "message": _public_error(error)}


def _complete_job(job: ClipJob, result: PipelineResult) -> None:
    job.transcript = result.transcript
    job.reply_text = result.reply_text
    job.notes = list(result.notes)
    job.corrections = [item.to_json() for item in result.corrections]
    job.timings_ms = result.timings_ms
    job.streak = result.streak.to_json() if result.streak is not None else None
    job.reply_audio = result.audio.data if result.audio is not None else None
    job.reply_content_type = result.audio.content_type if result.audio is not None else "audio/ogg"
    job.status = "ok"
    job.onboarding = result.onboarding
    job.call = result.call
