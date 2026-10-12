# Incomplete onboarding reminder

Status: implemented locally on `feature/telegram-onboarding`; not deployed.

## Decision

- Send at 20:00 Europe/Moscow each day to Telegram users whose latest onboarding attempt has no delivered result and has had no recorded activity for at least 24 hours. Include users who only received the `/start` invitation.
- Run the webhook scheduler once a minute between 20:00 and 21:59 to recover short outages. A missed day is not replayed after this window. Once a user completes onboarding, stop sending. Previously completed users are excluded even if they start a replay.
- Send at most one incomplete-onboarding reminder per user per Moscow day. The durable PostgreSQL claim happens immediately before the Telegram call and is not retried that day after an uncertain delivery result. A new day permits another reminder.
- Confirm the current AI-service attempt and state before claiming. `waiting` gets the existing Let’s chat button; `active` asks for a voice message; `pending` gets the existing Retry button. A replaced, completed, or unavailable state is skipped.
- Use a separate scheduler and database ledger from the opted-in daily practice reminder. This reminder does not require a saved practice-reminder time.

## Implementation

`V12__onboarding_nudges.sql` creates the daily claim ledger. `PostgresOnboardingAnalytics` selects the latest attempt per Telegram user and checks its invitation, result, 24-hour inactivity, and prior completion. `OnboardingNudgeRunner` performs the live state check, rechecks database eligibility when claiming, and sends the state-specific prompt through the existing bot.

The inactivity clock uses attempt start, begin/first-question milestones, received voice messages, and onboarding events. As with other Telegram sends, a completion that races the final state check can arrive too late to prevent a message. The database claim avoids repeat sends after process restarts.

## Verification

`./gradlew :server:test`, `:server:detekt`, and the PostgreSQL integration test with a temporary local database passed. Live Telegram acceptance has not been run.
