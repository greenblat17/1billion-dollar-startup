# Vocabulary Builder analytics

Status: implemented locally on 2026-11-04. Not deployed or checked with real DEV Telegram lessons. See the контракт ниже.

## Access and data path

The protected page is `/admin/metrics/vocabulary`, with a metadata-only lesson detail at `/admin/metrics/vocabulary/{lessonId}`. It uses the existing `METRICS_PASSWORD` session and `Cache-Control: no-store`. The AI service appends content-free facts to Redis Stream `vocabulary:analytics:v1` in the same transaction as a lesson state or review-trace change. Ktor polls `/internal/vocabulary/analytics`, writes a batch and its cursor to Postgres in one transaction, then acknowledges the stream. Telegram acceptance is a separate Ktor fact. Event IDs make replay idempotent. V25 creates the event and cursor tables.

Only a Telegram chat ID, opaque lesson/item IDs, timestamps, event kind, format, counts, phase, classification, message IDs, and timing enter this store. No word, translation, transcript, prompt, response text, or audio enters it. Manual versus review-sourced words are separated; example versus suggestion is retained only as a source class. Detailed technical events expire after 30 days. Compact lesson, word, and outcome facts expire after 365 days. Without `DATABASE_URL`, a process-local store is labelled on the page.

## Counting rules

- A start attempt is a Telegram button event. A created lesson is the AI state commit. An opening is delivered when Telegram accepts the bot message. These are separate counts.
- People are distinct Telegram chats. Lessons are distinct lesson IDs. Practised words are pairs of lesson ID and item ID. The period and format filter apply to the creation date of lessons. Late outcomes remain attached to their creation cohort.
- Full completion means the AI recorded a completed lesson with all card results. Partial completion is separate. A lesson without a terminal event remains active for one hour after its last fact, then enters a stop bucket.
- `used` means the product model accepted the learner's own phrase in the Use phase. A natural answer without the target is not a use success or an error. Card correctness and use success remain separate measures.
- D1/D7/D30 require a second delivered lesson on exactly that Moscow calendar day after the first delivered lesson. Only cohorts whose target day has passed are eligible. The weekly table groups people by the Monday of their first observed lesson. The first observed cohort can contain earlier users.
- Word revisit requires a later lesson with the same item after its first recorded due timestamp. Repeat Use success requires an accepted Use in such a later lesson. Due timestamps are persisted after completed format-5 lessons; older formats have no equivalent due fact.
- A failed request, failed Telegram delivery, and an unanswered lesson are distinct. Retry success resolves the matching message where available. The diagnostic page shows safe identifiers and event metadata only.

## Completeness and interpretation

The page shows source health, last successful sync, a queue-backlog signal, local write failures, and whether storage is volatile. Collection begins when this code is deployed; older lessons are not backfilled. A Redis host crash before persistence can lose uncommitted stream facts. The Telegram event queue can lose its buffered facts on process crash. The dashboard cannot detect either loss after restart, so these counters do not prove complete history. The 365-day report currently reads the bounded event range into memory; measure this query with production-like volume before PROD release. No before/after latency or real Telegram acceptance has been measured locally.

Local checks: Python test suite, Kotlin server test and Detekt, and V25/JDBC tests on a temporary Postgres. DEV acceptance and PROD deployment require separate decisions.
