# Onboarding analytics

Status: local implementation of the [decision analytics plan](../plans/2026-09-30-onboarding-analytics-decision-plan.md). Live DEV acceptance and before/after latency measurements remain release gates.

## Storage and identity

`/admin/metrics/onboarding` uses the metrics password and reads Postgres (`DATABASE_URL`). Without a database, the bot still works and the page reports that history is unavailable. Redis holds only the current onboarding attempt; Postgres keeps historical attempts and events. The analytics version is explicitly `v2` for new attempts. Existing `v1` rows remain `v1`; missing historical events are not reconstructed. A `run_id` identifies one attempt, `session_id` connects attempts for a Telegram user, and the first `/start` attempt is primary. Later `/start`, `/onboarding`, and automatic restarts have distinct triggers. `/start` deep-link source is stored when supplied; `direct` in the filter represents a missing source.

The schema stores timestamps, categorical statuses, numeric durations and counts. It does not store audio, transcripts, correction text, profile text or button-message content. There is no automatic retention/cleanup policy yet. The product must choose retention and acceptable data loss before adding a durable queue or cleanup.

## Event contract

| Fact | Occurs when | Idempotency |
| --- | --- | --- |
| Attempt start | Ktor has accepted a new onboarding run | `run_id` |
| `lets_chat_at`, page/card/goal/reminder/profile/bye marks | The corresponding Telegram reply or edit succeeded | First timestamp per `run_id` and mark |
| Onboarding voice | Processing or delivery finished, including classified failure | `(run_id, Telegram request_id)` |
| `result_delivered_at` | The closing result reply was delivered, including a successful cached retry | First timestamp per `run_id` |
| `retry_requested`, `retry_recovered`, `result_build_failed`, `text_hint`, `pre_begin_voice_hint` | Handler reached the named action | `(run_id, event_key)` |
| First practice, D1, D7 | A later ordinary voice was recognized after primary onboarding completed | First timestamp per primary attempt and milestone |

`received_at` is when the bot received a voice; `created_at` is after processing and Telegram reply. A voice's `processing_ms` includes clip processing and reply delivery. Its `speech_before_sec` and `speech_after_sec` are cumulative recognized speech, even when a recording contributes no speech. `telegram_duration_sec`, `recognized_duration_sec`, and `voice_index` are separate facts. The 30/60/90/120-second marks are first crossings, not four extra events per recording. The reply-gap metric measures time from a delivered voice reply to the next received voice, so it represents user wait and is separate from processing time. A button mark means that action completed; Telegram supplies no reliable read/open impression.

Voice outcomes are `recognized`, `no_speech`, `stt_failure`, `processing_failure`, `delivery_failure`, `queue_full`, and `unknown`. STT failure is classified at the STT stage by ai-service. A failed Telegram delivery is distinct from a processed voice. `unknown` covers old or unclassified failures. Text hints and voices before the start button have separate event counts and do not consume the speech budget. Result facts capture whether it was built, whether a CEFR/score exists, the number of displayed Grammar/Vocabulary examples, and whether numeric Fluency measurements exist. Retry is counted separately from a successfully restored result.

## Report definitions

The date filter selects attempts by their Moscow start date (7, 30, or 90 days); version, source, and trigger are further filters. Every selected attempt is a cohort member. A cohort day closes at the beginning of the second following Moscow day. A funnel step counts only if its first timestamp is between attempt start and 24 hours later. “From start” uses cohort size; “from previous step” counts users with both adjacent marks and divides by users with the previous mark. The report flags marks reached without the previous step. Open cohorts are shown separately because their conversion is incomplete. Old `v1` records may lack new fields.

The outcome table includes all selected onboarding voices; stage uses cumulative speech before that voice. Recognition error rate covers `no_speech` and `stt_failure` among selected voices from the last seven days, excluding `queue_full`; processing and delivery failures remain separate. Result-build error rate covers selected attempts that reached 120 seconds in the last seven days. The error blocks show event count, denominator, and distinct affected users. Percentiles use Postgres discrete percentiles. The voice-count distribution counts recognized voices on attempts with a built result.

Result/card/reminder counts cover selected attempts, including events later than the funnel's 24-hour window. The first ordinary practice voice belongs to the first completed primary attempt. D1 is Moscow calendar day after start and is shown only for a closed cohort; D7 is the seventh calendar day and is shown after that day has ended. Return counts use all starters as denominator and also show completed attempts by the end of the respective day. A new onboarding attempt alone is never practice return. These are observational associations; they do not establish why a user stopped or returned.

## Performance and data quality

Analytics writes are buffered during the per-chat action, then executed in order after Telegram delivery and after releasing the chat action lock and voice admission slot. An analytics write cannot delay that delivered reply or the next chat action. This is direct Postgres writing, not a process-memory background queue: an unflushed write can be lost on process crash. Individual write failures are logged and do not alter a delivered reply. Postgres connection wait is limited to one second, socket read to two seconds, and aggregate queries to five seconds. The analytics pool has two connections; the report aggregates in SQL over at most 90 days instead of loading every voice into Ktor memory. The page shows attempted writes, failed writes, and detected missing-attempt writes since this Ktor process started; they reset on restart and are not durable completeness metrics.

The local Flyway/Postgres integration test covers new-schema writing, deduplication, aggregate reading, and migration of synthetic `v1` rows. Deployment still needs a DEV walkthrough on new and existing users, migration verification on real historical data, query plans at expected volume, and matched before/after p50/p95/p99 bot latency and report-time measurements. A small cohort's percentages should not be treated as proof of improvement.
