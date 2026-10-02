# Telegram progress profile

`/profile` is the overview of the user's English progress. It is registered in the bot command menu and accepts `/profile@bot`. It sends a text card with the Telegram first name, latest completed assessment (CEFR, overall score, points to next band, Grammar/Vocabulary/Fluency scores), saved daily minutes and current streak. It adds no onboarding screen. The card explicitly labels the assessment as latest; ordinary practice does not rescore it.

`🔥 View streak` uses callback `profile:streak` to open the same rolling-week image and caption as `/streak`. Repeated deliberate taps load current data; duplicate delivery of the same callback is suppressed. `/streak` remains the detailed calendar view. History, best streak, total speaking time and a progress-history button are outside this MVP.

## Data and persistence

Authenticated `GET /internal/profile/{session_id}` returns:

```json
{
  "assessment": {
    "cefr": "B1",
    "overallScore": 52,
    "nextBand": "B2",
    "pointsToNext": 9,
    "grammar": 52,
    "vocabulary": 52,
    "fluency": 52
  },
  "dailyMinutes": 10,
  "currentStreak": 4
}
```

AI-service composes this response without creating an onboarding attempt, calling a model or recording activity. It reads:

- `assessment:{sessionId}`: latest completed score summary, no TTL. Saving a completed attempt atomically writes both the attempt and this summary in Redis. In-memory storage has the same behavior. Starting another attempt preserves the snapshot until a new assessment completes.
- `practice-goal:{sessionId}`: existing daily-minutes choice, outside the attempt.
- Existing streak storage: current display value including expiry, using the existing Moscow-day rule. A completed voice exchange counts; fulfilling the full daily goal is not required.

Legacy completed attempts without a snapshot remain readable. Before `/onboarding` resets such an attempt, its summary is preserved. Already-reset attempts from before this release cannot be recovered. This score snapshot is separate from the durable conversational learner memory described in [personalized conversation](2026-09-30-personalized-conversation.md); both now survive a new attempt.

No assessment gives `assessment: null` and an invitation to complete `/onboarding`; goal and streak are still shown. Missing CEFR or individual scores remain unknown, never invented zeros. No goal, including an explicit `No goal for now` choice stored as zero minutes, shows `Not set yet`. C2 has no numeric overall score or next-band target. No active streak is `0 days`.

## End of onboarding

After saving the daily goal, edit the existing goal-choice card into the commitment and reminder choice:

```text
10 minutes a day. Deal 🤝

🔥 Day 4 of your streak

Whenever you're ready to chat again, tap 🎙 Start call or just send me a voice message.

Want a gentle reminder so we don't forget to talk?
```

The first line is bold, with a blank line between paragraphs. It does not mention `/profile`. Choosing the goal edits the goal-choice card into this commitment; it does not send another bot message. Buttons are `🔔 Set reminder` and `Not now`. The streak is loaded at that moment, never hardcoded to day one. Zero or unavailable streak omits the streak line; a streak read failure does not undo the saved goal or block the question. `No goal for now` uses the same next-chat invitation and reminder question without a commitment or streak.

`Set reminder` edits the same card to ask for a Moscow time as text, `8:05` or `08:05`. Hours are 0–23 and minutes 00–59. The saved and confirmed value is zero-padded `HH:MM`. Anything else asks again. `/remind` asks the same way later, and a new time replaces the saved one. A saved onboarding time edits that card to a confirmation and the next-chat invitation. `Not now` leaves the commitment and next-chat invitation in the same card and removes the reminder question and inline buttons. Both paths send Sasha's existing feedback note immediately afterward, with blank paragraphs between its blocks. They do not send a separate `/profile` hint or a `Profile` / `See you tomorrow 👋` button card. `/profile` remains available as a command and does not send the founder note. Old `Keep talking 🎙` buttons still continue the conversation. A voice message while the time is still open stays an ordinary conversation; a later time is still accepted until the step is closed.

Choosing a daily goal, including `No goal for now`, continues to the reminder choice. The persistent `🎙 Start call` reply keyboard appears with Sasha's note after `Not now` or after a reminder time is saved, when a numeric assessment and a saved daily-goal choice exist. Without a numeric assessment the bot does not offer Start call. Existing `Profile` / `See you tomorrow 👋` callbacks from old messages remain compatible, but the current onboarding flow does not show them.

## Verification and release

Tests cover summary persistence in memory and Redis, reopening storage, legacy completed attempts, reset and replacement by a new completed assessment, goal/streak preservation, authenticated endpoint and client, rendering unknown values, actual streak in the final message, and calendar callback data. Deploy AI-service before Ktor through the existing deployment workflows. Live Telegram acceptance remains a separate check; no messages or deployments are part of local tests.
