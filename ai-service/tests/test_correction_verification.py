import asyncio

import pytest

from app import correction_verification, operational_metrics


class Verifier:
    def __init__(self, result=None, error=None):
        self.result = result
        self.error = error

    async def verify_corrections(self, candidates):
        if self.error is not None:
            raise self.error
        return self.result


@pytest.mark.asyncio
async def test_records_success_and_error_by_flow(monkeypatch):
    metrics = operational_metrics.ReviewVerificationMetrics()
    monkeypatch.setattr(correction_verification, "REVIEW_VERIFICATION_METRICS", metrics)

    assert await correction_verification.verify_corrections_with_metrics(
        Verifier({"a"}), [{"id": "candidate", "kind": "grammar"}], flow="onboarding",
    ) == {"a"}
    with pytest.raises(ValueError):
        await correction_verification.verify_corrections_with_metrics(
            Verifier(error=ValueError("bad response")), [{"id": "candidate", "kind": "grammar"}], flow="call",
        )

    result = metrics.prometheus()
    assert 'speaking_review_verification_total{flow="onboarding",outcome="success"} 1' in result
    assert 'speaking_review_verification_total{flow="call",outcome="error"} 1' in result


@pytest.mark.asyncio
async def test_records_timeout_and_cancellation(monkeypatch):
    metrics = operational_metrics.ReviewVerificationMetrics()
    monkeypatch.setattr(correction_verification, "REVIEW_VERIFICATION_METRICS", metrics)
    monkeypatch.setattr(correction_verification, "REVIEW_VERIFICATION_TIMEOUT_SECONDS", 0.001)

    class SlowVerifier:
        async def verify_corrections(self, candidates):
            await asyncio.sleep(1)

    with pytest.raises(TimeoutError):
        await correction_verification.verify_corrections_with_metrics(
            SlowVerifier(), [{"id": "candidate", "kind": "grammar"}], flow="call",
        )

    class CancelledVerifier:
        async def verify_corrections(self, candidates):
            await asyncio.sleep(1)

    running = asyncio.create_task(correction_verification.verify_corrections_with_metrics(
        CancelledVerifier(), [{"id": "candidate", "kind": "grammar"}], flow="onboarding",
    ))
    await asyncio.sleep(0)
    running.cancel()
    with pytest.raises(asyncio.CancelledError):
        await running

    result = metrics.prometheus()
    assert 'speaking_review_verification_total{flow="call",outcome="timeout"} 1' in result
    assert 'speaking_review_verification_total{flow="onboarding",outcome="cancelled"} 1' in result


@pytest.mark.asyncio
async def test_timeout_in_one_chunk_keeps_other_verified_examples(monkeypatch):
    metrics = operational_metrics.ReviewVerificationMetrics()
    monkeypatch.setattr(correction_verification, "REVIEW_VERIFICATION_METRICS", metrics)
    monkeypatch.setattr(correction_verification, "REVIEW_VERIFICATION_BATCH_SIZE", 1)
    monkeypatch.setattr(correction_verification, "REVIEW_VERIFICATION_TIMEOUT_SECONDS", 0.01)

    class MixedVerifier:
        async def verify_corrections(self, candidates):
            if candidates[0]["id"] == "slow":
                await asyncio.sleep(1)
            return {candidates[0]["id"]}

    accepted = await correction_verification.verify_corrections_with_metrics(
        MixedVerifier(), [{"id": "fast", "kind": "grammar"}, {"id": "slow", "kind": "grammar"}], flow="call",
    )
    assert accepted == {"fast"}
    result = metrics.prometheus()
    assert 'speaking_review_verification_total{flow="call",outcome="timeout"} 1' in result
    assert 'speaking_review_verification_total{flow="call",outcome="success"} 0' in result


@pytest.mark.asyncio
async def test_error_in_one_chunk_keeps_other_verified_examples(monkeypatch):
    metrics = operational_metrics.ReviewVerificationMetrics()
    monkeypatch.setattr(correction_verification, "REVIEW_VERIFICATION_METRICS", metrics)
    monkeypatch.setattr(correction_verification, "REVIEW_VERIFICATION_BATCH_SIZE", 1)

    class MixedVerifier:
        async def verify_corrections(self, candidates):
            if candidates[0]["id"] == "bad":
                raise ValueError("provider failed")
            return {candidates[0]["id"]}

    accepted = await correction_verification.verify_corrections_with_metrics(
        MixedVerifier(), [{"id": "good", "kind": "grammar"}, {"id": "bad", "kind": "grammar"}], flow="onboarding",
    )
    assert accepted == {"good"}
    assert 'speaking_review_verification_total{flow="onboarding",outcome="partial"} 1' in metrics.prometheus()


@pytest.mark.asyncio
async def test_syntax_and_lexical_errors_share_grammar_verification():
    seen = []

    class KindVerifier:
        async def verify_corrections(self, candidates):
            seen.append({item["kind"] for item in candidates})
            return {item["id"] for item in candidates}

    candidates = [
        {"id": "0:0", "kind": "grammar"},
        {"id": "0:1", "kind": "grammar"},
        {"id": "1:0", "kind": "grammar"},
    ]
    accepted = await correction_verification.verify_corrections_with_metrics(
        KindVerifier(), candidates, flow="call",
    )
    assert accepted == {"0:0", "0:1", "1:0"}
    assert seen == [{"grammar"}]
