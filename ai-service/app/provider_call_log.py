"""One privacy-safe log record for each external STT, LLM, or TTS request."""
from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
import logging
import re
import time
from collections.abc import Awaitable, Callable
from datetime import datetime, timezone
from typing import Any, TypeVar

from app.load_test import load_test_active
from app.metrics_v2 import client_for, current_session
from app.operational_metrics import PROVIDER_CALL_METRICS, current_voice_usage

logger = logging.getLogger(__name__)
T = TypeVar("T")
_hash_key: bytes | None = None
_TELEGRAM_SESSION = re.compile(r"tg-(?:ChatId\(chatId=)?(-?\d+)\)?\Z")
_APP_SESSION = re.compile(r"app-([0-9a-fA-F-]{36})-[0-9a-fA-F-]{36}\Z")


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
) -> T:
    """Record the actual HTTP attempt, including attempts later retried by the caller."""
    started = time.perf_counter()
    try:
        response = await call()
    except BaseException as error:
        status = _http_status(error)
        result = "cancelled" if isinstance(error, asyncio.CancelledError) else "error"
        _emit(kind, provider, model, purpose, result, status, type(error).__name__, None, started)
        raise
    try:
        cost = cost_of(response) if cost_of else None
    except Exception:
        cost = None
    _emit(kind, provider, model, purpose, "success", _http_status(response), None, cost, started)
    return response


def _http_status(value: Any) -> int | None:
    status = getattr(value, "status_code", None)
    if status is None:
        status = getattr(getattr(value, "response", None), "status_code", None)
    return status if isinstance(status, int) and not isinstance(status, bool) else None


def _emit(
    kind: str, provider: str, model: str, purpose: str | None, result: str,
    http_status: int | None, error_code: str | None, cost_micro: int | None, started: float,
) -> None:
    session = current_session()
    is_load_test = load_test_active()
    event = {
        "timestamp": datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
        "source": "load_test" if is_load_test else client_for(session),
        "user_id": anonymous_user_id(),
        "type": kind,
        "purpose": purpose,
        "provider": provider,
        "model": model,
        "result": result,
        "http_status": http_status,
        "error_code": error_code,
        "duration_ms": int((time.perf_counter() - started) * 1000),
        "cost_micro_usd": cost_micro,
    }
    if not is_load_test:
        PROVIDER_CALL_METRICS.record(kind, result)
        voice_usage = current_voice_usage()
        if voice_usage is not None:
            voice_usage.record(kind, result, cost_micro)
    logger.info("provider_call %s", json.dumps(event, separators=(",", ":"), ensure_ascii=True))
