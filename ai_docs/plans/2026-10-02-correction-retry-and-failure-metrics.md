# Bounded correction retries and observable failures

Status: implemented locally in `feature/telegram-start-call`; DEV validation and deployment remain. Scope: live corrections in ordinary Telegram and onboarding voice turns. The final onboarding assessment review is separate.

## Goal and invariant

Never delay a ready spoken reply indefinitely for correction cards. A missing or unsafe card is preferable to a false correction. Every correction request should have one bounded execution path and one recorded final outcome, without transcript or provider response content in logs or metrics.

The agreed correction deadline is **8 seconds from the start of its request**, fixed in code. It includes all attempts and backoff. At most **two provider attempts in total** are allowed. The SDK's own automatic retries remain disabled for OpenAI-compatible clients; other STT, TTS, and review operations keep the service's existing single explicit retry unless separately changed.

## Starting point

- `pipeline.py` starts notes alongside the spoken reply, applies the 8-second deadline, cancels overdue notes, and returns the voice without cards.
- `main.py` sets `max_retries=0` on Groq/OpenRouter clients; `llm.py` uses one explicit retry for retryable HTTP failures or a response without `choices`.
- `metrics.py` stores daily correction outcome counters in Redis or memory and exposes them through `/internal/metrics`.

The implementation below replaces the draft's separate retry, parsing, timeout, and metrics decisions with one result path for each live correction request.

## Implementation steps

1. **Make one correction attempt controller.** Keep the 8-second deadline outside the provider call so it covers attempt 1, any backoff, attempt 2, parsing, and cancellation. Make the decision to retry in one place. On 429/5xx, connection timeout, an empty `choices` envelope, empty text, or malformed JSON, allow one more attempt only while time remains; otherwise finish with the actual terminal reason. Do not retry a valid `notes: []` or a card rejected by the safety gate. Preserve the spoken reply and the existing correction filter.
2. **Record a single terminal result.** Define a typed outcome for `shown`, `empty`, `filtered`, `deadline`, `provider_timeout`, `rate_limit`, `provider_5xx`, `provider_4xx`, `network`, `no_choices`, `empty_text`, `invalid_json`, `invalid_schema`, and `other_error`. Distinguish JSON syntax errors from structurally wrong JSON. Carry `attempts` and elapsed milliseconds. Cancellation by the 8-second deadline must yield `deadline` only; cancellation because the whole clip failed should not be counted as a correction failure. Avoid losing the spoken reply if metrics storage fails.
3. **Make metrics usable for diagnosis.** Increment daily counts by final outcome and attempts in Redis and memory with fixed field names. Expose count and summed elapsed milliseconds for each outcome in `/internal/metrics` so average latency can be derived; keep counters separate from the LLM token usage counters. Log outcome, attempts, elapsed time, and provider HTTP status category only. Never log the transcript, generated text, credentials, or full provider error body. Document that counters start at deployment and are not backfilled.
4. **Show correction reliability on the existing dashboard.** Extend the Kotlin `MetricsSnapshot` DTO in `cmp/server/.../ai/ClipDtos.kt` with an optional correction metrics field that defaults to empty, so an older AI-service response still renders. In `MetricsDashboard.kt`, add an **«Исправления»** section to the existing `/admin/metrics` summary. Show today's total correction requests, failures and failure percentage, then a table with each outcome's count and average duration (`elapsedMs / count`), plus a visible count of requests that needed a second attempt. Use fixed Russian labels for the outcome enum. Treat `shown`, valid `empty`, and safety `filtered` as nonfailures; the other terminal outcomes are failures. When there are no requests, show «Пока нет данных» and no failure percentage. Do not expose transcript text or provider response bodies. Extend `MetricsDashboardTest.kt` for populated, empty, and backward-compatible snapshots; verify labels, counts, percentages, and HTML escaping.
5. **Verify failure combinations.** Use deterministic fake clients for 429→success, 503→empty envelope, empty text→valid JSON, malformed JSON→valid JSON, repeated malformed JSON, and slow first attempt with no time for a retry. Assert no more than two calls, no call after the deadline, exactly one matching outcome, and no card on terminal failure. Test that a ready audio reply survives a hung notes request, for ordinary and onboarding paths. Test memory and Redis snapshots against the same expected counters, and confirm the dashboard displays those counters.
6. **Validate before rollout.** Run the full AI-service suite, `./gradlew :server:test` from `cmp/`, and `git diff --check`. Compare the changed behavior against the 100-case synthetic correction set without treating it as proof of real-audio quality. On DEV, send several real voice turns, including a forced provider failure if possible; inspect request attempts, outcome counters, the dashboard section, `notes` timing, total pipeline timing, and Telegram delivery. Only then decide whether 8 seconds should change in code.

## Acceptance criteria

- At most two provider requests per correction generation; all retries and backoff end within the single 8-second deadline.
- A failed or timed-out correction never suppresses a successfully generated voice reply.
- Each completed correction generation produces exactly one terminal outcome with attempt count and elapsed time; timeout and ordinary cancellation cannot double count.
- `/internal/metrics` distinguishes a valid empty result, a safety-filtered candidate, malformed JSON/schema, empty provider output, provider failures, and deadline expiry.
- The existing `/admin/metrics` page shows correction outcomes and a failure rate derived from the same AI counters; it renders correctly when the AI-service has no correction data or is temporarily on an older version.
- Ordinary dialogue and onboarding use the same behavior and correction policy. No deployment or change to the final assessment reviewer is included in this plan.
