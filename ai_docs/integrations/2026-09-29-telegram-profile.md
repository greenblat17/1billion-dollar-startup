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
    "pointsToNext": 11,
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

Legacy completed attempts without a snapshot remain readable. Before `/onboarding` resets such an attempt, its summary is preserved. Already-reset attempts from before this release cannot be recovered. This snapshot is separate from the conversational work/leisure/goal profile, which still resets with the attempt.

No assessment gives `assessment: null` and an invitation to complete `/onboarding`; goal and streak are still shown. Missing CEFR or individual scores remain unknown, never invented zeros. No goal shows `Not set yet`. C2 has no numeric overall score or next-band target. No active streak is `0 days`.

## End of onboarding

After saving the daily goal, the existing final message becomes:

```text
10 minutes a day. Deal 🤝
🔥 Day 4 of your streak
Come back tomorrow for your 10-minute practice.
You can check your progress anytime with /profile.
```

The streak is loaded at that moment, never hardcoded to day one. Zero or unavailable streak omits the streak line; a streak read failure does not undo the saved goal or block the final message. `Keep talking 🎙` and `See you tomorrow` remain. No extra profile screen is sent.

## Verification and release

Tests cover summary persistence in memory and Redis, reopening storage, legacy completed attempts, reset and replacement by a new completed assessment, goal/streak preservation, authenticated endpoint and client, rendering unknown values, actual streak in the final message, and calendar callback data. Deploy AI-service before Ktor through the existing deployment workflows. Live Telegram acceptance remains a separate check; no messages or deployments are part of local tests.
