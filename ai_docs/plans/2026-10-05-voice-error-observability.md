# Сквозная диагностика ошибок голосовых сообщений

Status: implemented locally; live Telegram and DEV/PROD validation remain. Scope: extend the error dashboard in PR #32, based on PR #31. No deployment is part of this plan.

## Goal

For each Telegram voice message, answer: did the user receive the main voice reply, where did a failure occur, how long did each stage take, and how many users were affected? Preserve the existing completed AI-job counters as a separate series so their historical meaning does not change. Include partial feature failures without counting a delivered main reply as a failed turn.

## Existing facts and boundaries

- The current `/admin/metrics/errors` and `/admin/monitoring/errors` page reads `/internal/metrics` from ai-service. It counts completed clip jobs only, and shows 14 daily rows and 15 recent failures. See [clip error dashboard](../architecture/2026-10-04-clip-error-dashboard.md).
- `TelegramChatActions` admits and orders chat actions; `SessionClipQueue` can separately return `QueueFull`. `TelegramHandlers.kt` already measures setup, queue, download, processing, delivery and total durations in logs, but does not store them as dashboard data. Onboarding analytics records some outcomes only for active onboarding.
- `HttpClipClient` submits to `/v1/clips`, polls by `jobId`, then downloads audio. AI jobs record final success/failure; `PipelineResult.timings_ms` contains several successful-stage timings. Live correction outcomes already have daily counters; some optional failures are caught so the main job succeeds.
- `DATABASE_URL` is required in webhook mode and Postgres/Flyway already support onboarding analytics. The error page must continue to work in local configurations without Postgres, using a bounded in-memory store. Existing Redis metrics remain best effort.

## Definitions

One **voice attempt** is a unique `(Telegram chat ID, Telegram message ID)` pair. Store its first receive time and an opaque `attempt_id`; retries of the same Telegram update update the same row. A technical onboarding Retry based on stored audio is a separate operation linked to the original attempt, not another received voice. Other client platforms and realtime calls stay outside the end-to-end Telegram denominator and retain their own AI-job counters.

The **eligible** denominator is voice messages that reach the normal voice-processing path. Record earlier voice prompts (`waiting`, `pending`, goal/onboarding gates) as `not_eligible` with a reason; show their count separately and exclude them from the failure rate. Of eligible attempts, exactly one terminal outcome is recorded:

| Outcome | Meaning |
| --- | --- |
| `delivered` | Telegram accepted the main voice reply. It does not prove that the user listened to it. |
| `queue_full` | Either chat-action or clip queue rejected the voice; the queue-full notice is not a successful voice reply. |
| `download_failed` / `upload_rejected` | Telegram file download or AI upload failed before a clip job completed. |
| `ai_timeout` / `ai_failed` | Processing or client polling failed before a usable main reply. |
| `delivery_failed` | A reply was generated but Telegram did not accept the main voice delivery. |
| `other_failed` | A classified failure outside the above cases. |
| `unknown` | An admitted attempt remained nonterminal past a bounded stale threshold; show separately, never quietly treat it as success. |

User-visible failure rate = terminal eligible outcomes other than `delivered` divided by terminal eligible attempts. Show the number of still-pending attempts separately; do not mix them into the rate until reconciliation marks them `unknown`. One failed attempt can have multiple underlying provider errors, but only one terminal user-visible outcome. Use Moscow day of the original voice receive time for cohort counts; label this explicitly. Preserve the existing AI-job daily series under its original completion-day definition.

## Data and correlation contract

1. Add a Flyway migration for `telegram_voice_attempts` with unique `(chat_id, message_id)`, opaque `attempt_id`, `received_at`, eligibility, terminal outcome/time, bounded reason/stage/provider-category enums, nullable `job_id`, stage durations in milliseconds, and optional safe username snapshot. No audio, transcript, generated text, raw provider response, or credentials. Index `(received_at, outcome)` and `chat_id` over a bounded date range. Apply retention to attempt-level rows after 30 days in small batches; the dashboard covers at most the retained period. Document the cleanup owner and cadence. A matching bounded in-memory implementation supports local tests.
2. Give both the Ktor handler and AI job the same `attempt_id` through an optional internal clip form field. Keep onboarding's existing `requestId` unchanged. Include `attempt_id` and `job_id` in structured logs and the recent-error record; add a compact identifier column and a copyable value in the admin table. Do not place chat ID, username or message text in metric labels. Keep the current username and Telegram ID columns for admin investigation.
3. Start or upsert the attempt when an eligible voice enters handling; set terminal outcome after `deliver` returns or a caught failure is classified. Record both queue-full paths and early download/upload errors. Use one idempotent terminal transition per attempt; duplicate webhook deliveries and repeated metric calls must not increase counts. If a failure notice also fails to send, keep the original voice outcome and record notice-delivery trouble separately. Treat cancellation and shutdown explicitly so a dropped in-flight attempt becomes `unknown` after the threshold.
4. Make metrics writes best effort and off the user-critical path where possible. Use a bounded asynchronous write buffer/dispatcher with explicit overflow and shutdown behavior, or the existing analytics buffer if it safely covers this path. Never wait for dashboard aggregation on a voice reply. Expose a write-failure counter/log so a gap in observability is visible. Postgres writes use parameterized statements and a small bounded connection pool; dashboard reads use indexed, date-limited aggregate queries.

## Failure diagnosis

5. Define fixed low-cardinality values for `stage` (`telegram_download`, `queue`, `upload`, `stt`, `reply_llm`, `correction_llm`, `tts`, `state`, `telegram_delivery`, `other`) and `reason` (`timeout`, `rate_limit`, `provider_5xx`, `provider_4xx`, `network`, `invalid_input`, `internal`, `unknown`). Classify errors at the boundary that has the HTTP status or exception, before public error text is shortened. Keep provider identity as an allowlisted service name, not raw URL/model/error message. Preserve exception text only in the sanitized, bounded recent-error detail. Show counts by `(stage, reason)` for the selected 14-day window and today; percentages use eligible attempts as denominator, while counts of underlying provider attempts are labelled separately.
6. Record provider attempt outcome and retry exhaustion for STT, reply LLM, correction LLM and TTS in the existing AI metrics store. Count attempts and final operations separately so a successful retry does not become a failed user turn. Avoid unbounded `metrics:v2:events` growth: use fixed daily aggregate fields or bounded keys, with 30-day TTL. Keep memory/Redis implementations equivalent. Preserve `/internal/metrics` backward compatibility with optional DTO fields and empty defaults.

## Latency and user impact

7. Store nonnegative setup, chat-queue, clip-queue, download, upload/poll/AI-processing, Telegram-delivery and end-to-end durations from monotonic clocks, alongside the terminal outcome. Make the timing boundary for each field explicit; overlapping AI `notes` and TTS durations must not be added to get total time. Extend AI job telemetry with STT, reply LLM, correction LLM, TTS and finalization timings for both success and failure/timeout, using stage-local `finally` blocks. Onboarding follows the same timing contract where applicable; show `—` for stages that were not attempted. Bound/sanitize all values accepted from the internal API.
8. On the dashboard show p50/p95 for end-to-end and each meaningful stage over terminal eligible attempts in the selected 14-day window, plus today's p95 and sample count. Show successful and failed attempts separately where a combined percentile would hide a timeout. In Postgres, compute percentiles only from the indexed bounded period; if volume grows, add daily histograms rather than scanning all history on each page load. Do not derive p95 from per-day p95 values.
9. Show affected distinct Telegram chats per day/window, failed attempts per chat, and chats with at least three consecutive failed eligible attempts. A delivered main reply resets the streak; `not_eligible` and technical retries do not change it. Add a bounded recent repeated-failure list with Telegram ID/username and latest `attempt_id`; no unbounded per-user metric labels. Group chats are labelled as chats, since a group chat ID does not identify an individual user.

## Partial failures

10. Add a separate **Ответ доставлен, часть функций не сработала** section. Reuse existing correction terminal outcomes: `shown`, valid `empty`, and safety `filtered` are nonfailures; deadline, provider and parsing outcomes are partial failures only when the main reply was delivered. Also record applicable optional streak, call-summary, assessment/review and follow-up-card failures at their catch sites. Define each feature's attempted/succeeded/failed/skipped denominator. Show a reason breakdown and recent sanitized examples; do not add these to the user-visible voice failure rate. If a feature outcome cannot reliably be linked to a delivered attempt, label it as an AI-operation counter rather than claiming a delivered partial failure.

## Dashboard and compatibility

11. Keep both existing error URLs and access rules. Put the end-to-end result first, then stage/reason breakdown, latency, affected chats/repeats, partial failures, and the existing AI-job table/recent records. Show source, date basis, retention and any unavailable source near each section. If Postgres or AI metrics is unavailable, render the other source and an explicit unavailable state, never zero. Keep all dynamic text HTML-escaped and never turn Telegram IDs into public links.
12. Keep the existing `errors` payload fields intact; add optional versioned sections. Reads remain bounded to 14 days and recent 15/30 rows. Add no background polling from the admin page. Older records have no backfill; show the first observed date and avoid interpreting pre-instrumentation zeroes as healthy traffic.

## Implementation sequence and validation

1. Add contract/schema/store with migration and deterministic memory/Postgres tests for unique attempt, terminal transition, stale recovery, retention and aggregate queries.
2. Instrument Ktor reception, both queues, upload/poll and delivery; pass `attempt_id` to AI; verify onboarding and ordinary call flows, duplicate Telegram updates and technical Retry.
3. Add AI classifications, retry/final-operation counters and success/failure timing telemetry; verify provider 429/5xx/timeout/network, retries, concurrent notes/TTS, and whole-job timeout.
4. Add partial-failure signals at existing fail-soft sites; prove a delivered reply can have a partial failure without increasing the voice failure count.
5. Extend DTOs and both admin routes, then test populated/empty/older snapshots, HTML escaping, missing Postgres/AI, time-zone boundary and percentile math. Use representative 0, 1 and many-attempt fixtures.
6. Run the full AI-service suite and `./gradlew :server:test` from `cmp/`; run migration tests against temporary Postgres and `git diff --check`. Check that added instrumentation does not materially raise voice latency or queue wait under a small concurrent load, and that dashboard query time is bounded. Live Telegram delivery, DEV/PROD counters and provider behavior remain release checks after deployment; this plan does not authorize deployment.

Acceptance: every eligible Telegram voice has at most one terminal user-visible result; delivery is counted only after Telegram accepts the main voice reply; each failure has a stable stage/reason or explicit unknown; the dashboard shows p50/p95 with sample size, affected/repeated chats, a usable `attempt_id`, and separate partial failures; old AI-job metrics retain their meaning; metric-store failures never suppress the user's reply and are themselves observable.
