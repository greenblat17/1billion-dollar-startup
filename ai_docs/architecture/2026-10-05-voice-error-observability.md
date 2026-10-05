# Сквозная диагностика голосовых ошибок

Implementation of [the voice error observability plan](../plans/2026-10-05-voice-error-observability.md). This extends the existing [AI clip error dashboard](2026-10-04-clip-error-dashboard.md), without changing the meaning of its historical AI-job counters.

## User-visible result

The Telegram section of `/admin/metrics/errors` and `/admin/monitoring/errors` counts unique `(chat ID, message ID)` voice attempts by their Moscow receive day. `delivered` means Telegram accepted the main audio message. A successfully sent text fallback is `text_fallback`, a failed voice outcome. Prompts issued before the voice becomes eligible are `not_eligible` and excluded from the failure denominator. A still pending attempt appears as `unknown` after three minutes; this is derived at read time so it stays visible after process loss. Technical onboarding Retry does not create a second received voice attempt. Group chat IDs identify chats rather than individual users.

User-visible failure rate is `failed eligible terminal / all eligible terminal`. The page also shows pending and not-eligible counts, stage/reason counts, p50/p95 by outcome and stage with sample sizes for today and the 14-day period, unique affected chats, and chats with at least three consecutive failures. A delivered answer resets that streak. The last 15 failures retain username, Telegram chat ID, outcome, stage, reason, and a stable attempt ID. The AI section retains its separate completion-day counters, recent exception text and job ID.

## Collection and storage

`VoiceAttemptRecorder` queues bounded, best-effort writes outside the reply path. It reports failed/overflowed writes. The webhook process owns the recorder and drains it briefly on shutdown. Flyway V6 stores a row per message in Postgres, with unique chat/message keys, an opaque deterministic UUID, safe username snapshot, terminal outcome, job ID, and bounded stage durations. Null intermediate fields are retained by later writes; the first terminal outcome wins. A local in-memory implementation uses the same report calculation. A background task prunes rows older than 30 days in small batches. The report reads only the last 14 days and at most 50,000 rows; if it hits the cap, the page states that aggregates are incomplete. No old event history is backfilled.

The attempt ID is an optional internal clip form field. The AI job records it and its job ID in logs and recent failures. The Ktor client reads AI stage timings from the clip status; failed jobs also expose timings observed before failure. The stage durations overlap where work runs concurrently, so they must not be summed to derive end-to-end time. Telegram setup, chat queue, clip queue, download, processing, delivery, and total use monotonic clocks.

The Telegram Prometheus operational metrics from the combined monitoring branch run alongside these Postgres attempt records. Both use the same chat-action wait duration and Telegram delivery result. Prometheus continues to count a successfully sent text fallback as a delivered Telegram message; the error dashboard classifies that same attempt as a failed voice outcome (`text_fallback`). Neither series is substituted for the other.

AI-service stores daily bounded reason, provider attempt/final-operation, and partial feature counters in Redis with 30-day TTL, plus bounded recent failure lists. Provider labels are limited to Groq, Deepgram, OpenRouter, OpenAI and unknown; no provider URL or model text becomes a metric key. The existing `metrics:v2:events` list is capped at 10,000 and expires after 30 days; v2 daily hashes/sets also expire after 30 days. A retry failure followed by success increments an attempt failure and a final operation success, without counting a failed voice turn. Corrections keep their existing terminal outcomes and attempt counts.

Partial feature counters are **AI operation counters**, not proof that the corresponding main voice reached Telegram. They include corrections, streak, call summary/turn, onboarding assessment/review/verification/closing, and optional Telegram follow-up cards. Optional card delivery failure is logged and counted while the main audio send continues. For onboarding, a text fallback is a failed voice outcome even if text delivery succeeds. Recent partial examples contain only time, fixed feature name and bounded reason; no transcript, audio, generated text or raw provider response enters these new stores.

## Availability and validation

The two error routes retain their existing access rules. They load Telegram/Postgres and AI/Redis sections independently, showing unavailable rather than zero when a source fails. The counters begin at deployment and cannot reconstruct earlier failures. Local verification covers Python, Ktor, Flyway and JDBC on temporary Postgres; real Telegram delivery, provider classifications and runtime overhead still need DEV validation before rollout.
