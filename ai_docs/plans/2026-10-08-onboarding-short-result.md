# Optional short onboarding result

Status: implemented locally on 2026-10-08. A DEV Telegram check on 2026-10-09 reached the short-result choice but received HTTP 400 because the AI action endpoint omitted `short` from its allowlist. After that fix was deployed, the primary review provider returned HTTP 403 (`Blocked by Google AI Studio`). The short review now tries the configured review fallback model, as the full review already does. Repeat live acceptance after deployment of the fallback fix.

Related: [current Telegram onboarding](2026-09-28-telegram-onboarding.md), [Telegram contract](../integrations/2026-09-18-telegram.md), [onboarding analytics](../integrations/2026-09-30-onboarding-analytics.md).

## User experience

- The first invitation promises a small first step: about 30 seconds of voice answers, possibly across several messages. Continuing to about two minutes gives Speaky more examples for a fuller review.
- Before 30 seconds, the voice conversation and corrections work as they do now. The progress button counts toward `0:30` until the first threshold, then toward `2:00` for the optional full route.
- The answer that crosses 30 seconds gets the ordinary contextual voice reply and next question. It also offers a choice under that reply: `🎙 Keep talking` and `See result`. The surrounding copy explains that continuing to two minutes gives a fuller review. Sending another voice message is itself a choice to continue; no button press is required. Keep the result option accessible on later replies until completion. The offer caption is sent at the first crossing; later voice replies retain the buttons without repeating the caption.
- Do not compose the short review just because the user crossed 30 seconds. Compose it when they choose `See result`. That action ends this onboarding attempt. Show a text result directly, without a second closing voice that repeats the reaction to the last answer.
- At 120 seconds, retain the current automatic closing voice and full result flow.

## Result after the short route

- Show one compact card headed as an early estimate. It **always includes a preliminary CEFR and its numeric 0–100 score** for an eligible short result, even when the sample is small. Label both as preliminary. Do not show points to the next level or separate Grammar, Vocabulary and Fluency slides. The model chooses a CEFR band and low/mid/high position; code maps these to the existing score table.
- Include one or two observations supported by the user's actual English. Show only confidently verified correction examples when available; an empty correction section is valid. Do not invent observations or examples to fill the card.
- Continue to the existing daily goal and reminder choices. The profile labels this saved assessment as preliminary. Choosing the short result does not silently upgrade it to a full review; the full route is reached by continuing to two minutes before choosing the result.

## Measurement and boundary

- The current `seconds` counter uses STT recording duration (Telegram duration fallback), including pauses; it is not pure speaking time. User-facing copy should say "voice answers" or similar, not promise 30 seconds of spoken words unless the measurement changes.
- Analytics version `v3` records the short offer, short choice, short delivery, explicit continuation button and implicit continuation by voice. The agent export schema is `onboarding-analytics.v8`. The required journey now goes from 30 seconds to result delivery. The dashboard's decision block separately reports 120-second reach and full-result delivery so these remain visible. Compare the share of all eligible entrants who receive any result, the share who reach 120 seconds, and later ordinary practice; use the same entry denominator for both route outcomes. The displayed decision rates currently use selected attempts as their denominator, so use the eligible-entry cohort for the all-entrant comparison.
- If the 30-second recording contains no usable English (for example, recognized speech in another language), ask for an English voice answer before offering the result. Once the short result is available, it always contains a preliminary CEFR, even if the English sample is small.

## Local verification and release boundary

- Python 3.12: `ai-service/tests` — 300 passed.
- From `cmp/`: `:server:test detekt` passed. PostgreSQL integration tests were also run against a disposable local Postgres database and passed; without `TEST_POSTGRES_URL` their test bodies return without database assertions.
- Before deployment, exercise both routes in a real DEV Telegram client, including a 30-second answer, continuing by button and by voice, a recording that crosses 120 seconds, a short-result Retry, and profile/goal/reminder after the short route. Review the actual model's short CEFR explanations for groundedness and compare route counts and latency against the baseline.
