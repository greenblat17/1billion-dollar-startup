# История взаимодействий и логи сервисов

Status: implementation in the repository; deployment and live validation remain separate. This records the discussion on 2026-10-05 and the implementation started on the same date. It extends the existing [monitoring](../integrations/2026-10-02-monitoring.md) and [voice error observability](2026-10-05-voice-error-observability.md).

## Operator experience

Provide two linked views under the existing Grafana login:

1. **Interaction history:** a chronological view per Telegram chat and voice attempt. Show incoming text/commands/buttons, incoming voice, STT transcript, generated reply text, onboarding/call progression, outbound Telegram text/voice events, delivery result, failures, and retries. Preserve the distinction between generated text and what Telegram accepted. Telegram acceptance does not prove the user read or listened to a message. Show the existing `attempt_id` and link to the corresponding service-log time range.
2. **Service logs:** Grafana queries for Ktor, AI service, and relevant container logs. Add Loki as a Grafana data source and collect Docker stdout/stderr from both VPS hosts with Grafana Alloy. Retain the current Prometheus metrics separately.

Both views are available to **every authenticated Grafana user**. There is no additional per-user authorization decision for this operator UI. The interaction endpoints, audio playback, and file downloads must verify that same login on every request. Storage must not expose public audio URLs or become reachable through an unprotected local port. The current Nginx `auth_request` proxy for `/admin/monitoring` is a starting integration pattern; its suitability for the new routes and file responses must be validated before implementation. A shared Grafana admin account provides access control but cannot attribute a view to an individual operator.

## Data and retention

| Data | Proposed store | Retention |
| --- | --- | --- |
| Original incoming voice bytes | Private file/object storage; reference and timestamps in Postgres | 7 × 24 hours from Telegram receipt |
| STT transcript and generated reply text | Restricted interaction-content records | 7 × 24 hours from Telegram receipt |
| Event metadata (type, timestamps, state, outcome, message IDs, `attempt_id`, job ID, durations) | Postgres, linked to existing voice-attempt data | 30 days |
| Container logs | Loki, with bounded retention and disk monitoring | 30 days |

After seven days, the operator UI displays expired content while retaining the technical event timeline until its 30-day limit. A scheduled deletion job removes expired content and voice files; read endpoints enforce expiry immediately even if cleanup is late. Deletion must also cover backups/object versions according to the chosen storage setup. Keep audio and full conversation text out of Loki and metric labels. Use low-cardinality labels such as environment and service; `attempt_id` is a log field for correlation, not a Loki label.

The seven-day rule must apply to **existing copies**, not just the new UI. Today `session:{id}` retains user transcripts and replies for up to 30 days (`DIALOGUE_TTL_SECONDS`); `onboarding:{sessionId}` and `call:{id}` include transcripts and are written without TTL. Implementation must remove or age out raw turns in those stores without deleting needed onboarding/call status, assessments, or derived learner facts. It must account for old persisted records and any stored excerpts in reviews. Derived profile facts and proficiency are a separate product data category and may continue to support personalization. Check that resuming onboarding, reviewing a call, and returning after an absence still work after raw-turn expiry.

## Recording boundaries

Use stable event IDs to deduplicate repeated Telegram webhook updates and retries. Record receipt at the webhook/handler boundary; save the original voice when Ktor downloads it; record transcription and generated reply at the AI-service boundary; record outbound messages and the Telegram API result at the Ktor delivery boundary. A rejected or failed delivery must stay visible as an attempt, not as a sent message. Include commands, callbacks, optional correction cards, call openings, reminders, and relevant onboarding transitions rather than limiting the history to successful voice turns.

Do not block a user reply on dashboard aggregation. If an asynchronous audit write or file save fails, expose the gap through a counter and operational log. Define an explicit guarantee for which events must be durable before acknowledging a webhook; the current webhook acknowledges before downstream processing is complete, so a simple best-effort collector cannot guarantee a complete history through crashes. Compare a transactional outbox or durable queue with bounded best-effort recording before implementation.

## Implementation boundary

The user authorized local implementation after the [implementation plan](../plans/2026-10-05-interaction-history-and-service-logs.md). Deployment remains a separate action. The first implementation covers the Telegram path; other clients can use the event model when their live flows are instrumented.

## Current implementation and release checks

The Ktor webhook writes an idempotent inbound event to Postgres before HTTP 200. A separate content table and private OGG directory hold seven-day data; metadata remains for 30 days. The shared Telegram API wrapper records outgoing attempts and Telegram acceptance/failure. The operator page is `/admin/monitoring/history`, with chat, attempt, job, UTC time filters, pagination, audio range playback and links to Loki Explore. The private monitoring connector and existing Grafana Nginx session check guard the page and audio route. AI stores STT/reply stage artifacts in Redis with the original receipt deadline; Ktor imports them on success/failure and periodically reconciles recent attempts. Dialogue, onboarding and call Redis data remove old raw turns, questions and review excerpts on read/write and in an hourly sweep. Legacy untimestamped turns are discarded. Derived profile facts and assessment scores remain.
Outbound Telegram audit events use a bounded 1,024-event queue with three database write attempts. The history page shows pending writes and observed write failures. This avoids blocking delivery on each outbound database write, but a host crash can lose queued events.
`DATABASE_URL` is required when `TELEGRAM_BOT_TOKEN` is configured; startup fails if the durable audit store cannot be enabled.

Loki retains 720 hours on the CMP host. Alloy reads Docker stdout on each host; the AI host sends over HTTPS with basic authentication using `AI_INTERNAL_TOKEN`. The AI `.env` must set `LOKI_PUSH_URL=https://<CMP monitoring host>:8443/loki/api/v1/push`; add `SPEAKY_ENV` on both hosts for environment labels. If the monitoring certificate uses a private CA, install `/opt/ai-service/monitoring/loki-ca.crt` on the AI host. Grafana provisions the `speaky-loki` data source. The CMP deploy script clears legacy `app.log` files once because older versions included message text.

Before a DEV or PROD rollout, measure OGG/log volume and disk headroom, verify backups do not restore expired raw content, and test the Nginx login gate for the HTML and audio routes including Range requests. Check a controlled set of Telegram messages against history, duplicated webhook delivery, and both hosts in Grafana Explore. Compare reply latency and queue waits with the pre-deploy baseline. These live checks have not run. The shared Grafana account cannot attribute reads to individual operators. A crash between asynchronous delivery and its audit write can still leave a gap; AI Redis RDB snapshots can also lose very recent artifacts. The UI flags observed audit write failures, but it cannot prove that every downstream event survived a host crash.
