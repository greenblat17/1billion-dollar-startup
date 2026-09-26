# Daily Telegram reminder

Status: implemented (2026-09-26), including the admin controls and stats on `/admin/metrics`.

## Decisions

- Every Telegram user gets one text a day. The only skip is a user who already finished a turn today (Moscow day, `metrics:dau:{day}`).
- No `/stop`. A user who does not want it blocks the bot; the send returns 403, is counted as `blocked`, and is not retried.
- Time: 19:00 `Europe/Moscow`. The auto window is 19:00–21:00, so a restart after 21:00 does not message people late at night.
- Copy: a fixed pool of English templates with stable ids in `cmp/server/.../telegram/ReminderMessages.kt` (`ReminderTemplate(id, text)`), with an optional first name. No LLM generation. Do not rename ids: stats are keyed by id.
- CMP app: out of scope (no push).

## Flow

1. `launchDailyReminder` in `webhookScope` (webhook mode only, `Application.kt`) ticks every minute and calls `ReminderRunner.runRound(AUTO)` once per Moscow day inside the window.
2. `ReminderRunner` (one `Mutex`, so auto and manual rounds never overlap) calls `POST /internal/reminders/claim`. ai-service marks each target with `SET NX reminder:sent:{day}:{sessionId} EX 2d`, so restarts, redeploys, and a manual run on the same day do not double-send.
3. It sends `sendTextMessage` to each chat id with a 50 ms pause. Result per chat: `sent`; `blocked` (Telegram 403); `failed` (anything else). On 429 it waits `retry_after` and retries once.
4. It posts the round to `POST /internal/reminders/report` (mode, start/finish time, claimed, per-chat template id and status).

## Admin controls (tab `/admin/metrics/reminders`, same password cookie)

The dashboard has two tabs: "Сводка" (`/admin/metrics`, unchanged plus two reminder columns in the chat table) and "Напоминания" (`/admin/metrics/reminders`, buttons and all reminder stats). Button redirects return to the reminders tab with `?notice=`.

- "Отправить на себя": chat id + template (today's for that chat id, or any id). Sends only to that chat, never claims, never reported.
- "Отправить всем сейчас": `POST /admin/metrics/reminders/send` starts a manual round in the background and redirects to the tab with `?notice=started` or `busy`. The browser asks for confirmation with the current forecast.
- Routes live under `/admin/metrics/...` because the session cookie has `path=/admin/metrics`. Without webhook mode there are no buttons, only stats.

## Stats (ai-service `app/reminders.py`, Redis, no TTL except the claim key)

- Reply attribution: the first `/internal/funnel/voice` within 24 hours after the last sent reminder counts as `returned`, once, on the reminder's day and template. Any later voice still resets the ignore streak.
- Segment at send time: `active` if the funnel user has `activated_day`, else `new` (only `/start`).
- Reminders tab: sent today, 24-hour reply rate and block rate over 7 days, median time to reply (7 days), forecast for today (candidates not yet claimed), today's auto round; tables for rounds (last 30), days (14), templates (sorted by reply rate), segments (7 days). The chat table gains "last reminder" and "ignored in a row".

## Text rotation

`index = (day.toEpochDay() + chatId mod size) mod size`. One user does not see the same text twice within `size` days, and users on the same day get different texts. No state is stored.

Contract: `integrations/2026-09-18-run-and-ci.md` (metrics section).
