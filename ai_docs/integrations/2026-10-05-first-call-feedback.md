# Feedback after the first practice conversation

After the user continues past Fluency, Telegram edits that card into the final progress/recap card. For the first practice conversation with at least one user turn after onboarding, it then sends a separate message:

> Как тебе этот разговор со Speaky?
> [👍 Понравился] [😐 Так себе] [👎 Не понравился]

The selected rating is stored immediately. The message becomes one optional question: `Что тебе особенно понравилось?` for 👍, or `Что можно улучшить?` for 😐/👎, with a `Пропустить` button. The next ordinary text message within 24 hours is saved as the answer, up to 500 characters. Telegram commands, Start call, and reminder-time input keep their normal behavior. A later rating or answer cannot replace the first one. Rating and answer are stored in Redis under `first-call-feedback:{sessionId}` without a TTL. Rated sessions are indexed in `first-call-feedback:rated` for bounded dashboard reads. The username at offer time is saved alongside the feedback; users without one appear by Telegram session ID.

The ai-service endpoint is `POST /internal/calls/feedback`, protected by the existing internal token. Actions: `offer` (`sessionId`, `callId`), `rate` (`sessionId`, `callId`, `choice` = `liked|neutral|disliked`), `answer` (`sessionId`, `text`), and `skip` (`sessionId`, `callId`). Each returns a `status` (`offered`, `rated`, `saved`, `ignored`, or `too_long`). `offer` checks that the call belongs to the session, has a completed review, and is the first call with a user turn. The Redis hash claim ensures repeated Continue callbacks and later calls cannot send a second survey. Failed scoring does not trigger the survey until a successful review reaches the final card.

`GET /internal/calls/feedback?offset=0&limit=25` returns only rated responses with `username`, `choice`, and optional `message`, plus `total`. The authenticated `/admin/metrics/calls` page shows a paginated table below the calls. Comment and username are HTML escaped; the page uses `Cache-Control: no-store`. No comment is shown for a skipped or unanswered follow-up.
