from __future__ import annotations

import asyncio
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
