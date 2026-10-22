"""Measure the final correction check shared by onboarding and practice calls."""
from __future__ import annotations

import asyncio
import logging
import time
from typing import Any

from app.operational_metrics import REVIEW_VERIFICATION_METRICS

REVIEW_VERIFICATION_TIMEOUT_SECONDS = 10
REVIEW_VERIFICATION_BATCH_SIZE = 5
REVIEW_VERIFICATION_CONCURRENCY = 3
logger = logging.getLogger(__name__)


async def verify_corrections_with_metrics(model: Any, candidates: list, *, flow: str) -> set[str]:
    """Keep verified chunks when another chunk fails within the same deadline."""
    started = time.perf_counter()
    outcome = "success"
    semaphore = asyncio.Semaphore(REVIEW_VERIFICATION_CONCURRENCY)

    async def verify_chunk(chunk: list) -> set[str]:
        async with semaphore:
            return await model.verify_corrections(chunk)

    grouped = [
        [item for item in candidates if isinstance(item, dict) and item.get("kind") == kind]
        for kind in ("grammar", "natural")
    ]
    tasks = [
        asyncio.create_task(verify_chunk(group[start:start + REVIEW_VERIFICATION_BATCH_SIZE]))
        for group in grouped for start in range(0, len(group), REVIEW_VERIFICATION_BATCH_SIZE)
    ]
    try:
        done, pending = await asyncio.wait(tasks, timeout=REVIEW_VERIFICATION_TIMEOUT_SECONDS)
        for task in pending:
            task.cancel()
        if pending:
            await asyncio.gather(*pending, return_exceptions=True)
        accepted: set[str] = set()
        successful_chunks = 0
        errors: list[Exception] = []
        for task in done:
            try:
                accepted.update(task.result())
                successful_chunks += 1
            except asyncio.CancelledError:
                errors.append(TimeoutError("correction verification chunk cancelled"))
            except Exception as error:
                errors.append(error)
        timed_out = bool(pending) or any(isinstance(error, TimeoutError) for error in errors)
        if successful_chunks:
            if timed_out:
                outcome = "timeout"
            elif errors:
                outcome = "partial"
            if pending or errors:
                logger.warning(
                    "correction verification partly completed flow=%s verified_chunks=%s failed_chunks=%s",
                    flow, successful_chunks, len(pending) + len(errors),
                )
            return accepted
        if timed_out:
            raise TimeoutError("correction verification deadline exceeded")
        raise errors[0]
    except asyncio.CancelledError:
        outcome = "cancelled"
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        raise
    except TimeoutError:
        outcome = "timeout"
        raise
    except Exception:
        outcome = "error"
        raise
    finally:
        REVIEW_VERIFICATION_METRICS.record(flow, outcome, time.perf_counter() - started)
