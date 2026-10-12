from __future__ import annotations

import asyncio
from contextvars import ContextVar, Token
from collections.abc import Awaitable, Callable
from typing import TypeVar

import openai
import httpx

T = TypeVar("T")
_provider_metrics: ContextVar[object | None] = ContextVar("provider_metrics", default=None)


def bind_provider_metrics(metrics: object) -> Token:
    return _provider_metrics.set(metrics)


def reset_provider_metrics(token: Token) -> None:
    _provider_metrics.reset(token)


def _reason(error: BaseException) -> str:
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
    if any(part in type(error).__name__.lower() for part in ("connect", "network", "socket")):
        return "network"
    if isinstance(error, (ValueError, TypeError)):
        return "invalid_input"
    return "internal"


async def _record(service: str | None, provider: str, kind: str, result: str) -> None:
    metrics = _provider_metrics.get()
    if service is None or metrics is None:
        return
    try:
        await asyncio.wait_for(metrics.record_provider(service, kind, result, provider=provider), 0.05)
    except Exception:
        pass


def is_retryable(error: BaseException) -> bool:
    if isinstance(error, openai.RateLimitError):
        return True
    if isinstance(error, openai.APIStatusError):
        return error.status_code is not None and (error.status_code == 429 or error.status_code >= 500)
    if isinstance(error, httpx.HTTPStatusError):
        return error.response.status_code == 429 or error.response.status_code >= 500
    return False


async def once_on_retryable(
    factory: Callable[[], Awaitable[T]], delay_seconds: float = 0.5,
    retry_if: Callable[[BaseException], bool] = is_retryable,
    metric_service: str | None = None,
    metric_provider: str = "unknown",
) -> T:
    async def attempt() -> T:
        try:
            result = await factory()
        except Exception as error:
            await _record(metric_service, metric_provider, "attempt", _reason(error))
            raise
        await _record(metric_service, metric_provider, "attempt", "ok")
        return result

    try:
        result = await attempt()
    except Exception as error:
        if not retry_if(error):
            await _record(metric_service, metric_provider, "operation", _reason(error))
            raise
        await asyncio.sleep(delay_seconds)
        try:
            result = await attempt()
        except Exception as retry_error:
            await _record(metric_service, metric_provider, "operation", _reason(retry_error))
            raise
    await _record(metric_service, metric_provider, "operation", "ok")
    return result
