# Telegram free conversation and situation analytics

Status: implemented locally and tested on 2026-10-09; not deployed. Live Telegram use and report performance under production traffic remain to be checked after deployment.

## Product questions

Use the existing protected `/admin/metrics/calls` report to answer:

1. How many distinct people and calls use free conversation versus situation practice?
2. Which situation is selected, and where do people leave the selection path?
3. How much do people actually speak in each mode, and do they return to it?
4. Are starts, voice replies, corrections, and review delivery less reliable in a mode?

Show today, 7 days, and 30 days in the Europe/Moscow time zone. A call belongs to its opening day. A mode-selection event belongs to its event day; therefore a selection and its later call can straddle a reporting boundary. Show counts and sample sizes, not only percentages. Do not infer a user's preference from raw call counts alone: show unique users and repeat users alongside calls.

## Facts to collect

Extend the existing idempotent `telegram_call_events` with a bounded `scenario_kind` (`free`, `job`, `manager`, `custom`). Set it on `call_open` for button starts and direct-voice starts. A later direct voice must not replace a situation mode on an existing call. Historical calls with no recorded kind remain `unknown`; there is no Redis backfill. Never store custom scenario descriptions, transcripts, audio, or prompt text in analytics.

Record these additional bounded events with stable Telegram message or callback IDs:

- `scenario_menu_opened` only after Telegram accepts the menu message;
- `scenario_selected` for job, manager, or custom, whether or not the call later opens;
- `scenario_back` when the menu is closed;
- `custom_description_submitted` for a valid description and `custom_description_invalid` for an invalid one.

Existing `start_pressed` states describe eligibility, `call_open` and `starter_delivered` describe an opened/delivered call, and `voice_received` describes actual dialogue. These events form a funnel without making model calls or adding synchronous database writes to Telegram actions. The existing bounded `CallEventRecorder` queue, write-failure counter, 30-day retention, and 50,000-event report cap apply.

## Report

Add a mode filter to the call list. The comparison section uses the selected period and existing source/status/failure filters, but ignores the mode filter so modes remain comparable. For free, job, manager, custom, and unknown, show distinct users, calls, call share, users with at least two calls in that mode, calls with at least one user voice, zero-voice calls, voice turns, recognized speech p50/p95 per call, audio replies, failures, delivered correction cards, Subtitles clicks, and delivered reviews. Show raw recognized speech separately from elapsed open-call time; an open call can sit idle for hours. A closed-call interval is not a measure of speaking time.

The situation path uses all menu events in the selected period independently of call-list filters: menus accepted by Telegram, selections by kind, custom valid/invalid descriptions, calls opened by that selection, starter delivery, calls with a user voice, and menu Back. Include explicit denominators and note that a selection may lead to a call on another day. Add mode to each call row and detail page. Do not claim Telegram acceptance proves a person read or listened to a message.

## Validation and rollout

Test idempotent event storage and mode preservation, selection versus opened-call counts, distinct/repeat users, speech percentiles, historical unknown calls, and filter behavior. `:server:test` and `:server:detekt` pass from `cmp/`. The Postgres-backed JVM test is conditional on `TEST_POSTGRES_URL`, which is not set locally; the migration and upsert SQL were checked inside a rolled-back transaction on a temporary DEV table. No behavior change to spoken replies, onboarding gates, or situation prompts. Deploy separately after review; the report starts collecting mode data only when the new Telegram server is deployed.
