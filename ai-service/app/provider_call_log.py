"""One privacy-safe log record for each external STT, LLM, or TTS request."""
from __future__ import annotations

import asyncio
import hashlib
import hmac
import logging
import re
import time
from collections.abc import Awaitable, Callable
from typing import Any, TypeVar

from app.diagnostics import EventSpec, event
from app.load_test import load_test_active
from app.metrics_v2 import client_for, current_session
from app.operational_metrics import PROVIDER_CALL_METRICS, current_voice_usage
from app.voice_cost import record_voice_cost

logger = logging.getLogger(__name__)
T = TypeVar("T")
_hash_key: bytes | None = None
_TELEGRAM_SESSION = re.compile(r"tg-(?:ChatId\(chatId=)?(-?\d+)\)?\Z")
_APP_SESSION = re.compile(r"app-([0-9a-fA-F-]{36})-[0-9a-fA-F-]{36}\Z")
_SHAPE_FIELDS = frozenset({"schema_name", "schema_hash", "max_completion_tokens"})
_PROVIDER_CALL_FIELDS = _SHAPE_FIELDS | {
    "source", "user_id", "type", "purpose", "provider", "model", "result",
    "http_status", "error_code", "cost_micro_usd", "duration_ms",
}


def configure_user_id_hash_key(secret: str) -> None:
    """Use the required internal secret; never write it or the original ID to logs."""
    global _hash_key
    _hash_key = secret.encode("utf-8") if secret else None


def anonymous_user_id() -> str | None:
    session = current_session()
    if not session or not _hash_key:
        return None
    telegram = _TELEGRAM_SESSION.fullmatch(session)
    app = _APP_SESSION.fullmatch(session)
    identity = f"telegram:{telegram.group(1)}" if telegram else (
        f"app:{app.group(1).lower()}" if app else session
    )
    return hmac.new(_hash_key, identity.encode("utf-8"), hashlib.sha256).hexdigest()[:20]


def provider_from_url(base_url: Any) -> str:
    url = str(base_url or "").lower()
    if "openrouter.ai" in url:
        return "openrouter"
    if "groq.com" in url:
        return "groq"
    if "openai.com" in url:
        return "openai"
    return "unknown"


async def logged_provider_call(
    call: Callable[[], Awaitable[T]], *, kind: str, provider: str, model: str,
    purpose: str | None = None, cost_of: Callable[[T], int | None] | None = None,
    generation_id_of: Callable[[T], str | None] | None = None,
    operation: str | None = None, shape: dict | None = None,
) -> T:
    """Record the actual HTTP attempt, including attempts later retried by the caller."""
    started = time.perf_counter()
    try:
        response = await call()
    except BaseException as error:
        status = _http_status(error)
        result = "cancelled" if isinstance(error, asyncio.CancelledError) else "error"
        _emit(kind, provider, model, purpose, result, status, None, started, operation, shape, error)
        if result == "cancelled":
            await asyncio.shield(record_voice_cost(
                component=kind, provider=provider, model=model, result=result, cost=None))
        else:
            await record_voice_cost(component=kind, provider=provider, model=model, result=result, cost=None)
        raise
    try:
        cost = cost_of(response) if cost_of else None
    except Exception:
        cost = None
    _emit(kind, provider, model, purpose, "success", _http_status(response), cost, started, operation, shape)
    generation_id = None
    if generation_id_of is not None:
        try:
            generation_id = generation_id_of(response)
        except Exception:
            pass
    await record_voice_cost(component=kind, provider=provider, model=model, result="success",
                            cost=cost, generation_id=generation_id)
    return response


def _http_status(value: Any) -> int | None:
    status = getattr(value, "status_code", None)
    if status is None:
        status = getattr(getattr(value, "response", None), "status_code", None)
    return status if isinstance(status, int) and not isinstance(status, bool) else None


def _emit(
    kind: str, provider: str, model: str, purpose: str | None, result: str,
    http_status: int | None, cost_micro: int | None, started: float,
    operation: str | None, shape: dict | None, error: BaseException | None = None,
) -> None:
    is_load_test = load_test_active()
    if not is_load_test:
        PROVIDER_CALL_METRICS.record(kind, result)
        voice_usage = current_voice_usage()
        if voice_usage is not None:
            voice_usage.record(kind, result, cost_micro)
    stage = "response" if error is None or http_status is not None else "transport"
    reason = None
    if result == "cancelled":
        outcome, message = "cancelled", "The provider request was cancelled."
    elif result == "success":
        outcome, message = "completed", "The provider request returned a response."
    elif http_status is None:
        outcome, message = "unknown", "The provider request failed before a response could be confirmed."
        reason = "provider_transport_error"
    elif 400 <= http_status < 500:
        outcome, message = "rejected", f"The provider rejected the request with HTTP {http_status}."
        reason = "provider_http_error"
    else:
        outcome, message = "failed", f"The provider request failed with HTTP {http_status}."
        reason = "provider_http_error"
    spec = EventSpec("provider_failed" if result == "error" else "provider_call",
                     "provider_client", operation or purpose or kind, stage, outcome, message, reason,
                     logging.WARNING if result == "error" else logging.INFO, _PROVIDER_CALL_FIELDS)
    event(spec, marker="provider_call", logger=logger,
          error=error if result == "error" else None,
          source="load_test" if is_load_test else client_for(current_session()), user_id=anonymous_user_id(),
          type=kind, purpose=purpose, provider=provider, model=model, result=result, http_status=http_status,
          error_code=type(error).__name__ if error else None, cost_micro_usd=cost_micro,
          duration_ms=int((time.perf_counter() - started) * 1000),
          **{key: value for key, value in (shape or {}).items() if key in _SHAPE_FIELDS})
