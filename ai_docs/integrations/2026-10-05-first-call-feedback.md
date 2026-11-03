# Feedback after the first practice conversation

After the user continues past Fluency, Telegram edits that card into the final progress/recap card. For the first practice conversation with at least one user turn after onboarding, it then sends a separate message:

> Как тебе этот разговор со Speaky?
> [👍 Понравился] [😐 Так себе] [👎 Не понравился]

The selected rating is stored immediately. The message becomes one optional question: `Что тебе особенно понравилось?` for 👍, or `Что можно улучшить?` for 😐/👎, followed by `Напиши ответ текстом в чат или нажми «Пропустить».` and a `Пропустить` button. The next ordinary text message within 24 hours is saved as the answer, up to 500 characters. Telegram commands, Start call, and reminder-time input keep their normal behavior. A later rating or answer cannot replace the first one. Rating and answer are stored in Redis under `first-call-feedback:{sessionId}` without a TTL. Rated sessions are indexed in `first-call-feedback:rated` for bounded dashboard reads. The username at offer time is saved alongside the feedback; users without one appear by Telegram session ID.

The ai-service endpoint is `POST /internal/calls/feedback`, protected by the existing internal token. Actions: `offer` (`sessionId`, `callId`, `username`), `rate` (`sessionId`, `callId`, `choice` = `liked|neutral|disliked`), `answer` (`sessionId`, `text`), and `skip` (`sessionId`, `callId`). Each returns a `status` (`offered`, `rated`, `saved`, `ignored`, or `too_long`). `offer` checks that the call belongs to the session, the current onboarding state is `completed`, the call has a user turn, and its review succeeded. The Redis hash claim ensures repeated Continue callbacks cannot send a second survey; later Speaky calls do not offer another. For a user who completed onboarding before this feature was deployed, the first eligible result shown after rollout offers the survey even if they had earlier conversations. Failed scoring does not trigger the survey until a successful review reaches the final card. Ignored offers return a bounded reason and Ktor logs it for diagnosis.

`GET /internal/calls/feedback?offset=0&limit=25` returns only rated responses with `username`, `character`, `callId`, `choice`, and optional `message`, plus `total`. The authenticated `/admin/metrics/calls` page shows a paginated table below the calls. Comment and username are HTML escaped; the page uses `Cache-Control: no-store`. No comment is shown for a skipped or unanswered follow-up.

## Kora H feedback after every call, 2026-10-23

The first-call limit remains for Speaky. A completed Kora H call offers the same
rating and optional text question after its review's final progress card, on
every eligible call. The prompt names Kora H. The AI service records the
character on the call when its opening starts, so switching characters after
the call ends does not change which survey is offered. Kora H does not require
a completed onboarding state when the reviewed call already exists. A call without a user
turn or successful review does not receive a survey.

Kora H offers are claimed by call ID under `kora-call-feedback:{callId}`;
repeated Continue clicks cannot create another offer. Ratings and answers
remain immutable per call. The latest rated survey owns the next ordinary text
answer for up to 24 hours; older surveys can still be skipped with their own
button. Speaky keeps its existing `first-call-feedback:{sessionId}` record.
The rated dashboard index contains both survey types and shows the character
for each row. The same seven-day conversation-content retention does not apply
to the optional feedback text, matching the existing first-call survey.
