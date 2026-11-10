# Telegram progress profile

`/profile` is the overview of the user's English progress. It is registered in the bot command menu and accepts `/profile@bot`. It sends a text card with the Telegram first name, latest completed assessment (CEFR, overall score, points to next band, Grammar/Vocabulary/Fluency scores), saved daily minutes and current streak. It adds no onboarding screen. The card explicitly labels the assessment as latest; practice-call reviews may update the stored assessment under their own scoring rules.

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

- `assessment:{sessionId}`: latest completed score summary, no TTL. Legacy completed attempts populated this summary; retained practice-call reviews can update it. In-memory storage has the same behavior. Starting another attempt preserves the snapshot until a new assessment completes.
- `practice-goal:{sessionId}`: existing daily-minutes choice, outside the attempt.
- Existing streak storage: current display value including expiry, using the existing Moscow-day rule. A completed voice exchange counts; fulfilling the full daily goal is not required.

Legacy completed attempts without a snapshot remain readable. `/onboarding` no longer resets legacy attempts; it continues the text tutor. Already-reset attempts from before this release cannot be recovered. This score snapshot is separate from the durable conversational learner memory described in [personalized conversation](2026-09-30-personalized-conversation.md); both now survive a new attempt.

No assessment gives `assessment: null`; goal and streak are still shown. The current rendering still says “Complete /onboarding to get your first assessment.” This is stale runtime copy: `/onboarding` now opens the text tutor and cannot produce that assessment. It is a documented open inconsistency, not a supported assessment path. Missing CEFR or individual scores remain unknown, never invented zeros. No goal, including an explicit `No goal for now` choice stored as zero minutes, shows `Not set yet`. C2 has no numeric overall score or next-band target. No active streak is `0 days`.

## Совместимость шкалы старых оценок

В `review_score.py` прежние оценки используют band, position и shade, а не прямой выбор числа моделью.
Центры low/mid/high: A1 12/20/27, A2 32/38/43, B1 47/52/57, B2 63/70/77, C1 83/90/94.
Shade `--`, `-`, `0`, `+`, `++` сдвигает центр максимум на два пункта с ограничением внутри ячейки.
Начала диапазонов: A1 10, A2 30, B1 46, B2 61, C1 81; `pointsToNext` — расстояние до начала следующего.
Общий CEFR не усредняет три skill score. При недостатке данных оценка отсутствует; C2 не имеет числового балла.
Это совместимость исторических assessment и calls, не методика пяти показателей AI-репетитора.

## Граница нового продукта

Это прежний профиль голосовой практики. Он не читает TutorStore и не показывает roadmap из v0.5.
Старый handoff через daily goal/reminder после voice onboarding удалён, как и call gate по оценке.
`/onboarding` теперь открывает текстовый чат; он не пересчитывает CEFR.

## Verification and release

Tests cover summary persistence in memory and Redis, reopening storage, legacy completed attempts, reset and replacement by a new completed assessment, goal/streak preservation, authenticated endpoint and client, rendering unknown values, actual streak in the final message, and calendar callback data. Deploy AI-service before Ktor through the existing deployment workflows. Live Telegram acceptance remains a separate check; no messages or deployments are part of local tests.
