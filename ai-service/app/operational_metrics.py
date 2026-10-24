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


class ReviewVerificationMetrics:
    """One measurement for each attempted final correction verification."""

    BUCKETS = (0.5, 1.0, 2.0, 5.0, 10.0, 15.0, 20.0, 30.0)
    FLOWS = ("onboarding", "call")
    OUTCOMES = ("success", "partial", "timeout", "error", "cancelled")

    def __init__(self) -> None:
        self._lock = Lock()
        self._counts = {(flow, outcome): 0 for flow in self.FLOWS for outcome in self.OUTCOMES}
        self._duration_sum = {(flow, outcome): 0.0 for flow in self.FLOWS for outcome in self.OUTCOMES}
        self._buckets = {
            (flow, outcome): [0] * len(self.BUCKETS)
            for flow in self.FLOWS for outcome in self.OUTCOMES
        }

    def record(self, flow: str, outcome: str, duration_seconds: float) -> None:
        key = (flow, outcome)
        with self._lock:
            self._counts[key] += 1
            self._duration_sum[key] += duration_seconds
            for index, upper_bound in enumerate(self.BUCKETS):
                if duration_seconds <= upper_bound:
                    self._buckets[key][index] += 1

    def prometheus(self) -> str:
        with self._lock:
            lines = [
                "# TYPE speaking_review_verification_total counter",
            ]
            for (flow, outcome), count in self._counts.items():
                lines.append(
                    f'speaking_review_verification_total{{flow="{flow}",outcome="{outcome}"}} {count}'
                )
            lines.append("# TYPE speaking_review_verification_duration_seconds histogram")
            for (flow, outcome), count in self._counts.items():
                labels = f'flow="{flow}",outcome="{outcome}"'
                for upper_bound, bucket_count in zip(self.BUCKETS, self._buckets[(flow, outcome)]):
                    lines.append(
                        f'speaking_review_verification_duration_seconds_bucket{{{labels},le="{upper_bound:g}"}} {bucket_count}'
                    )
                lines.append(
                    f'speaking_review_verification_duration_seconds_bucket{{{labels},le="+Inf"}} {count}'
                )
                lines.append(
                    f'speaking_review_verification_duration_seconds_sum{{{labels}}} {self._duration_sum[(flow, outcome)]}'
                )
                lines.append(f'speaking_review_verification_duration_seconds_count{{{labels}}} {count}')
        return "\n".join(lines) + "\n"


REVIEW_VERIFICATION_METRICS = ReviewVerificationMetrics()


class VocabularyEnrichmentMetrics:
    """Process-local final-search counts without user or transcript labels."""

    def __init__(self) -> None:
        self._lock = Lock()
        self._runs: dict[tuple[str, str], int] = {}
        self._duration: dict[tuple[str, str], float] = {}
        self._counts: dict[tuple[str, str], int] = {}

    def record(self, flow: str, outcome: str, duration_seconds: float) -> None:
        with self._lock:
            key = (flow, outcome)
            self._runs[key] = self._runs.get(key, 0) + 1
            self._duration[key] = self._duration.get(key, 0.0) + duration_seconds

    def candidates(self, flow: str, proposed: int, grounded: int, accepted: int, selected: int) -> None:
        with self._lock:
            for stage, count in (("proposed", proposed), ("grounded", grounded),
                                 ("verified", accepted), ("selected", selected)):
                key = (flow, stage)
                self._counts[key] = self._counts.get(key, 0) + count

    def prometheus(self) -> str:
        with self._lock:
            lines = ["# TYPE speaking_vocabulary_enrichment_total counter"]
            for (flow, outcome), count in self._runs.items():
                lines.append(f'speaking_vocabulary_enrichment_total{{flow="{flow}",outcome="{outcome}"}} {count}')
            lines.append("# TYPE speaking_vocabulary_enrichment_duration_seconds_sum counter")
            for (flow, outcome), seconds in self._duration.items():
                lines.append(f'speaking_vocabulary_enrichment_duration_seconds_sum{{flow="{flow}",outcome="{outcome}"}} {seconds}')
            lines.append("# TYPE speaking_vocabulary_enrichment_candidates_total counter")
            for (flow, stage), count in self._counts.items():
                lines.append(f'speaking_vocabulary_enrichment_candidates_total{{flow="{flow}",stage="{stage}"}} {count}')
        return "\n".join(lines) + "\n"


VOCABULARY_ENRICHMENT_METRICS = VocabularyEnrichmentMetrics()


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
