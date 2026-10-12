# Telegram progress profile

`/profile` is the overview of the user's English progress. It is registered in the bot command menu and accepts `/profile@bot`. It sends a text card with the Telegram first name, latest completed assessment (CEFR, overall score, points to next band, Grammar/Vocabulary/Fluency scores), saved daily minutes and current streak. It adds no onboarding screen. The card explicitly labels the assessment as latest; ordinary practice does not rescore it.

The profile card has no inline `🔥 View streak` button. `/streak` remains the detailed calendar view. The old `profile:streak` callback still works on cards already sent before this change. History, best streak, total speaking time and a progress-history button are outside this MVP.

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

After saving the daily goal, leave the goal-choice card's text in place and remove its inline choices. Send the commitment as a new, persistent message with the `🎙 Start call` reply keyboard. For a five-minute goal and a two-day streak, that message is:

```text
5 minutes a day. Deal 🤝

🔥 Day 2 of your streak

Whenever you're ready to chat again, tap 🎙 Start call or just send me a voice message.
```

Then send a separate reminder question with the existing `🔔 Set reminder` and `Not now` inline buttons. No temporary keyboard-setting message is sent or deleted. The streak is loaded at that moment, never hardcoded to day one. Zero or unavailable streak omits the streak line; a streak read failure does not undo the saved goal or block the question. `No goal for now` sends its existing no-goal text with the same persistent reply keyboard.

`Set reminder` edits the reminder question to ask for a Moscow time as text, `8:05` or `08:05`. Hours are 0–23 and minutes 00–59. The saved and confirmed value is zero-padded `HH:MM`. Anything else asks again. `/remind` asks the same way later, and a new time replaces the saved one. A saved onboarding time edits the reminder message to a confirmation. `Not now` edits that reminder message to the skip confirmation. Both paths send Sasha's existing feedback note immediately afterward. `/profile` remains available as a command; its card has no inline `View streak` button, and `/streak` remains a command. Old onboarding callbacks from already-sent messages remain compatible.

The call handler checks that the profile has a numeric assessment and a saved daily-goal choice before starting a call. Sasha's later note attaches the lower button only when no call is active, so it cannot reopen the keyboard during a conversation. The lower `🎙 Start call` keyboard appears immediately after the goal is chosen, including `No goal for now`, and remains available while the user answers the reminder question.

## Verification and release

Tests cover summary persistence in memory and Redis, reopening storage, legacy completed attempts, reset and replacement by a new completed assessment, goal/streak preservation, authenticated endpoint and client, rendering unknown values, actual streak in the final message, and calendar callback data. Deploy AI-service before Ktor through the existing deployment workflows. Live Telegram acceptance remains a separate check; no messages or deployments are part of local tests.
