from __future__ import annotations

import asyncio
from contextvars import ContextVar, Token
from functools import wraps
from threading import Lock

from app.metrics_v2 import client_for, current_session


class StageMetrics:
    """Process-local counters for short Prometheus windows, independent of daily Redis totals."""

    def __init__(self) -> None:
        self._lock = Lock()
        self._counts = {
            (stage, outcome): 0
            for stage in ("stt", "llm", "tts")
            for outcome in ("success", "failure")
        }

    def record(self, stage: str, outcome: str) -> None:
        with self._lock:
            self._counts[(stage, outcome)] += 1

    def prometheus(self) -> str:
        with self._lock:
            lines = ["# TYPE speaking_stage_attempts_total counter"]
            for (stage, outcome), count in self._counts.items():
                lines.append(
                    f'speaking_stage_attempts_total{{client="telegram",stage="{stage}",outcome="{outcome}"}} {count}'
                )
        return "\n".join(lines) + "\n"


STAGE_METRICS = StageMetrics()


class ProviderCallMetrics:
    """Count real provider attempts for Grafana, including failed retries."""

    def __init__(self) -> None:
        self._lock = Lock()
        self._counts = {
            (kind, result): 0
            for kind in ("stt", "llm", "tts")
            for result in ("success", "error", "cancelled")
        }

    def record(self, kind: str, result: str) -> None:
        with self._lock:
            self._counts[(kind, result)] += 1

    def prometheus(self) -> str:
        with self._lock:
            lines = ["# TYPE speaking_provider_calls_total counter"]
            for (kind, result), count in self._counts.items():
                lines.append(
                    f'speaking_provider_calls_total{{type="{kind}",result="{result}"}} {count}'
                )
        return "\n".join(lines) + "\n"


PROVIDER_CALL_METRICS = ProviderCallMetrics()


class LlmGenerationMetrics:
    """Output tokens and provider-reported generation time for LLM responses."""

    def __init__(self) -> None:
        self._lock = Lock()
        self._tokens = 0
        self._seconds = 0.0

    def record(self, completion_tokens: int, generation_ms: float) -> None:
        if completion_tokens <= 0 or generation_ms <= 0:
            return
        with self._lock:
            self._tokens += completion_tokens
            self._seconds += generation_ms / 1000

    def prometheus(self) -> str:
        with self._lock:
            return (
                "# TYPE speaking_llm_generation_tokens_total counter\n"
                f'speaking_llm_generation_tokens_total{{client="telegram"}} {self._tokens}\n'
                "# TYPE speaking_llm_generation_seconds_total counter\n"
                f'speaking_llm_generation_seconds_total{{client="telegram"}} {self._seconds}\n'
            )


LLM_GENERATION_METRICS = LlmGenerationMetrics()


class VoiceModelUsage:
    """Provider attempts made while processing one incoming Telegram voice clip."""

    def __init__(self) -> None:
        self.attempts = {kind: 0 for kind in ("stt", "llm", "tts")}
        self.priced = {kind: 0 for kind in ("stt", "llm", "tts")}
        self.cost_micro = {kind: 0 for kind in ("stt", "llm", "tts")}
        self.pending_tts = 0

    def record(self, kind: str, result: str, cost_micro: int | None) -> None:
        self.attempts[kind] += 1
        if cost_micro is not None:
            self.priced[kind] += 1
            self.cost_micro[kind] += cost_micro
        elif kind == "tts" and result == "success":
            self.pending_tts += 1

    def settle_tts(self, cost_micro: int | None) -> None:
        if not self.pending_tts:
            return
        self.pending_tts -= 1
        if cost_micro is not None:
            self.priced["tts"] += 1
            self.cost_micro["tts"] += cost_micro


_voice_usage: ContextVar[VoiceModelUsage | None] = ContextVar("voice_model_usage", default=None)


def bind_voice_usage(usage: VoiceModelUsage | None) -> Token:
    return _voice_usage.set(usage)


def reset_voice_usage(token: Token) -> None:
    _voice_usage.reset(token)


def current_voice_usage() -> VoiceModelUsage | None:
    return _voice_usage.get()


class VoiceModelMetrics:
    """Known provider charges and price coverage for incoming Telegram voice jobs."""

    def __init__(self) -> None:
        self._lock = Lock()
        self._attempts = {kind: 0 for kind in ("stt", "llm", "tts")}
        self._priced = {kind: 0 for kind in ("stt", "llm", "tts")}
        self._cost_micro = {kind: 0 for kind in ("stt", "llm", "tts")}

    def record(self, usage: VoiceModelUsage) -> None:
        with self._lock:
            for kind in self._attempts:
                self._attempts[kind] += usage.attempts[kind]
                self._priced[kind] += usage.priced[kind]
                self._cost_micro[kind] += usage.cost_micro[kind]

    def prometheus(self) -> str:
        with self._lock:
            lines = ["# TYPE speaking_voice_model_cost_micro_total counter"]
            for kind, value in self._cost_micro.items():
                lines.append(f'speaking_voice_model_cost_micro_total{{component="{kind}"}} {value}')
            lines.append("# TYPE speaking_voice_model_calls_total counter")
            for kind in self._attempts:
                lines.append(
                    f'speaking_voice_model_calls_total{{component="{kind}",price="known"}} {self._priced[kind]}'
                )
                lines.append(
                    f'speaking_voice_model_calls_total{{component="{kind}",price="missing"}} '
                    f'{self._attempts[kind] - self._priced[kind]}'
                )
        return "\n".join(lines) + "\n"


VOICE_MODEL_METRICS = VoiceModelMetrics()


def telegram_stage(stage: str, *, session_arg: bool = False):
    """Count one logical operation after its built-in retries; cancellation is not a failure."""

    def decorate(operation):
        @wraps(operation)
        async def measured(*args, **kwargs):
            session = args[1] if session_arg else current_session()
            telegram = client_for(session) == "telegram"
            try:
                result = await operation(*args, **kwargs)
            except asyncio.CancelledError:
                raise
            except Exception:
                if telegram:
                    STAGE_METRICS.record(stage, "failure")
                raise
            if telegram:
                STAGE_METRICS.record(stage, "success")
            return result

        return measured

    return decorate
