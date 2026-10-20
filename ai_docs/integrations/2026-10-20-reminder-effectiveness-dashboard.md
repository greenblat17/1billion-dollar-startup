# Reminder effectiveness dashboard

Status: implemented locally on `feature/onboarding-nudge-analytics`; PostgreSQL integration tests passed. Live Telegram acceptance and deployment remain outstanding.

`/admin/metrics/reminders?days=7|30` starts with a short decision view. Existing detailed tables and send controls are retained inside a collapsed diagnostics section. `/admin/metrics/soft-nudges` has the same five-minute soft-nudge definition. Only Telegram-accepted sends enter conversion denominators. Telegram does not report whether a message was read. All dates are Moscow calendar dates; current saved and inferred time distributions are snapshots, not send-period cohorts.

The primary outcomes are:

| Flow | Success | Window |
| --- | --- | --- |
| Soft onboarding interruption | First incoming voice | 5 minutes after send |
| Soft open-call interruption | Incoming voice or End Call on that call, counted once | 5 minutes after send |
| Daily unfinished onboarding | Reached a later observed funnel stage | 24 hours or next accepted daily reminder, whichever comes first |
| Daily practice, user chose time | First incoming voice | 24 hours or next accepted daily reminder |
| Daily practice, bot inferred time | First incoming voice | 24 hours or next accepted daily reminder |

The denominator contains only sends whose full attribution window has closed. Pending sends are shown separately. Button clicks and first voice are intermediate rows for onboarding; button clicks are intermediate rows for practice. A person can enter more than one send-level denominator across days. The dashboard does not claim a causal effect: users who set their own time can already be more motivated, and general/personalized copy alternates by day rather than random assignment.

New `reminder_buttons.source` and `copy_kind` capture the category and actual text kind when the bot accepts a message and registers its button. A successful callback records `clicked_at`; previous buttons remain in storage after deactivation, so historical clicks are preserved. The configured-time practice cohort uses these records. The inferred-time practice and unfinished-onboarding cohorts use `onboarding_nudges` and their stage/copy metadata. The inferred time distribution uses each recipient's latest recorded median time on an accepted practice send in the selected period. Configured time buckets come from the current reminder-setting summary. These distributions have different populations and must not be compared as a controlled experiment. Older sends have no new category or recorded median minute and cannot be backfilled reliably.

Automatic configured-time attempts also write `configured_practice_attempts` after sending. Follow-up replacements do not enter this table. This keeps configured-time blocks and failures separate from inferred-time sends even when one scheduler claims both. Known Telegram errors have a numeric `telegram_*` code; local and transport failures use `transport_or_internal`. The primary conversion still uses Telegram-accepted button records, so a failed database write can appear as an unknown gap between the send runner and this dashboard.

The first voice uses incoming Telegram interaction metadata, retained for 30 days; it does not require successful transcription. Onboarding progression uses durable timestamped funnel milestones from the same attempt. The terminal `reminder_resolved` stage advances when a practice voice arrives. Soft-nudge voice response remains durable in `soft_nudges.returned_at`, and End Call uses the timestamped `end_pressed` event for the interrupted call. If both actions happen, the combined soft-call numerator is one.

The main diagnostic table separates 403 blocks, other failed sends, and uncertain outcomes where the source records them separately. Its cells show counts and percentages of attempts. An expandable day table shows when errors occurred by source; configured-time attempts also have a code breakdown. The detailed Telegram error reason is not consistently stored across soft and inferred reminder flows, so the dashboard does not fabricate one. Existing day and stage breakdowns remain in the collapsed tables. Operational checks should verify that the five-minute soft metric, callback clicks, source split, and first-voice windows match live Telegram behaviour before relying on production percentages.
