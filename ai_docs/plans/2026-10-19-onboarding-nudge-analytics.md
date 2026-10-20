# Incomplete onboarding reminder analytics

The initial `onboarding-nudges.v1` contract below remains the historical baseline. The locally implemented [stage-specific follow-up plan](2026-10-20-onboarding-stage-followups.md) extends its JSON and dashboard to `onboarding-nudges.v2` with stage, copy, timing, practice and stop aggregates. No live deployment has been verified.

Scope: delivery accounting and dashboard only. No holdout experiment, new send policy, opt-out changes, or deployment.

## Decisions

- Keep incomplete-onboarding reminders separate from opted-in daily practice reminders. Add their own block on `/admin/metrics/reminders` and the private monitoring equivalent, sharing the 7/30 Moscow calendar-day selector.
- Extend the existing daily PostgreSQL claim ledger. New claims snapshot action (`begin`, `voice`, `retry`), template version `v1`, ordinal across all claims for this user (including legacy/failed claims), and inactivity seconds. Preserve the existing per-user/day uniqueness and eligibility checks.
- After Telegram responds, write `sent`, message ID, and acknowledgement time; API 403 is `blocked`, other explicit API errors are `failed`. Transport exceptions/cancellation/process death or a missing database acknowledgement remain `unknown`. Never retry an uncertain send for analytics. Outcome writes are idempotent and never downgrade confirmed outcomes.
- Legacy claims stay `unknown`; do not reconstruct delivery from existence of a claim. Tracking starts at the first versioned claim. `sent` means accepted by Telegram, not read.
- Retain compact inbound timestamps/event IDs for previously nudged users through a PostgreSQL trigger on the existing inbound audit insert. No content, audio or names are copied. Duplicate updates and audit pruning do not change the counts. Capture from claim onwards to handle an inbound write racing the outcome write. Only callback/voice/text/photo events with incoming/received status qualify; system events and outgoing messages do not.
- Store successful ordinary practice timestamps through the existing `recordReturn` path, in the same transaction. This covers non-primary attempts too. An eligible ordinary practice voice is recognized and has a delivered bot reply.

## Denominators and attribution

Operational counts select claims in the last 7/30 Moscow days, including today. Show attempts, successful sends, distinct successful recipients, 403, explicit failures and unknown outcomes. Unknown includes legacy records and in-progress/uncertain claims.

For each confirmed send, the outcome interval is strictly after acknowledgement and before the earlier of the next confirmed send or 24 hours. Each inbound event, onboarding voice, or delivered result belongs to at most one send; flags count once per send. A return means any qualifying incoming action. A voice means an onboarding voice recorded by the processing flow, including unrecognized/failed voices; it does not mean successful recognition. Results mean `result_delivered_at`, not `completed_at` or opening the result card. Voice/result matching follows the user across restarted attempts.

Rates use only confirmed sends whose full 24 hours have elapsed. Even an early success stays out of both numerator and denominator until maturity. Immature sends are shown separately. These are send-level rates: a person can return after multiple sends. Inbound collection failures are not reconstructed; audit availability remains a prerequisite for observing returns.

Seven-day cohorts are unique users whose **first confirmed send in the new ledger** belongs to the selected date period. Earlier unknown sends cannot establish first-ever exposure. Count a delivered result within 7 days of that first send and an ordinary successful practice exchange after that result and within the same 7 days. Later sends are part of the observed sequence; they do not restart or duplicate this cohort. Include only fully matured 7-day windows in rates and show pending users separately. No causal effectiveness claim without randomized holdout.

Break down send counts and mature 24-hour outcomes by day, action, claim ordinal (1/2/3/4+), inactivity (1–2/3–6/7+ days), and action/template version. Ordinal comparisons have survivor bias: later sends reach people who did not return earlier.

## Implementation and validation

Flyway V16 adds fields, indexed compact activity facts and the audit insert trigger. PostgreSQL report uses indexed per-session probes over the selected period. No external service, model request, raw content or user identity is added to HTML/JSON.

Aggregate export: `/admin/metrics/reminders/onboarding.json?days=7|30`, with the same admin cookie as the page; monitoring equivalent uses the monitoring-port restriction. Schema `onboarding-nudges.v1`, definitions, generation time and dates included. Both pages and exports use no-store. Missing storage/query failure displays unavailable / HTTP 503, never a fabricated zero.

Tests cover duplicate delivery reports and inbound events, uncertain sends, post-send database failure without resend, next-send attribution, exact 24-hour boundaries, immature windows, unique first-send cohorts, source audit pruning, export privacy and admin authorization. Run with `TEST_POSTGRES_URL` pointing at a disposable database to execute actual migrations/JDBC queries.

## Local verification (2026-10-19)

Implemented locally. `:server:test` passed: 219 tests, zero failures/errors/skips, with `TEST_POSTGRES_URL` set to a fresh disposable PostgreSQL 14 database. `:server:detekt` and `git diff --check` passed. The migration and JDBC queries ran against PostgreSQL, including delayed acknowledgement near the seven-day boundary and practice following a non-primary attempt. DEV/PROD deployment, live Telegram delivery and browser visual acceptance have not been performed.

The 7-day date filter includes today: its first-send cohorts have not yet completed seven full days, so that block shows pending users. Use the 30-day filter to analyze matured seven-day results. Percentages intentionally do not use incomplete cohorts.
