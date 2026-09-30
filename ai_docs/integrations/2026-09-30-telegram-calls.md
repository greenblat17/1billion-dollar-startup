# Telegram practice calls

After onboarding, a voice is a practice call with a start and an end. Onboarding stays Session 0. The rolling dialogue and learner memory continue across calls.

## When a call opens

Ktor asks ai-service for the onboarding state, then `GET /internal/profile/{sessionId}`.

- `waiting`, `active`, and `pending` stay on the onboarding path. No call is opened.
- No numeric `overallScore` sends the person into `/onboarding`. The voice is not transcribed as a call, and no baseline is invented.
- A score without `practice-goal:{sessionId}` shows the same 5 / 10 / 15 choice as the end of onboarding. There is no 10-minute placeholder.
- Otherwise `POST /internal/calls/open` get-or-creates the open call, and the clip runs.

A daily reminder is still only an invitation. It does not open a call or start the clock. `/start` does not seal a call. `/onboarding` seals an open call and removes the keyboard.

## During the call

The latest Speaky voice carries one inline keyboard: today's recognized speech against the daily goal (`8:24 / 10:00`), `Subtitles`, and `☎️ End conversation` on the next row. The keyboard moves off the previous voice. Crossing the goal does not end the call. The clock shows overtime, such as `12:40 / 10:00`, and one text says the day's minutes are done.

A recognized turn stores transcript, reply, corrections, Whisper seconds, and word timings on `call:{id}`. Silence and the clarify line add no seconds. One live correction uses the onboarding card, not the `You said` quote.

The same Moscow day keeps the call open across pauses. `End conversation` waits until the voice already in the chat queue finishes, then seals. A voice that arrives after that tap opens the next call. Minutes for the day are the sum. `End conversation` is handled on `TelegramChatActions`, so it does not need a second lock.

At the next Moscow day, `open` seals the previous call. It does not push the review at night. The next voice is a new call, and one button offers yesterday's review. That button is offered once.

## Review

`POST /internal/calls/end` returns the sealed id. `POST /internal/calls/review` scores it once and caches the result. A failed score is not cached; Retry calls the same route.

The model returns a short level note, a one-or-two-sentence recap of what you discussed, and a move from -2 to 2 for the overall level and for grammar, vocabulary, and fluency. Code applies that move to the stored snapshot and keeps each score inside its current table cell, so 50 cannot become 80 or 20 in one call. Examples still come from this call, after the same verification used by onboarding. Fluency measurements reuse `fluency_metrics`.

The cards show the updated level, then Grammar, Vocabulary, and Fluency. The line after Fluency is today's time (`🎯 0:51 / 5:00 today`), a blank line, then `recap`: one or two sentences about what you just discussed, with no score and no CEFR band. Then `🔥 2 day streak`. Buttons are `Profile` and `Finish for today`. `Profile` sends the same card as `/profile`. Neither button opens a call; the next voice does. When no reminder time is saved, the last line is `Want me to remind you tomorrow? Tap /remind`.

The stepped snapshot is written back to `assessment:{sessionId}` and, when learner memory already exists, onto its proficiency scores. A later save of the same onboarding attempt does not overwrite that snapshot. A new completed onboarding attempt does. Ordinary turns still do not change proficiency.

Deploy ai-service before Ktor. Live Telegram acceptance is separate from the unit tests.
