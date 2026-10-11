# Telegram practice calls

Situation practice extends the same call lifecycle. Its menu, scenario state, fictional
memory boundary, and MVP scope are recorded in
[Telegram practice situations](../plans/2026-10-09-telegram-practice-situations.md).

After onboarding, a voice is a practice call with a start and an end. Onboarding stays Session 0. The rolling dialogue and learner memory continue across calls.

## When a call opens

Ktor asks ai-service for the onboarding state, then `GET /internal/profile/{sessionId}`.

- `waiting`, `active`, and `pending` stay on the onboarding path. No call is opened.
- No numeric `overallScore` sends a new user into `/onboarding`. An existing user exempt from automatic onboarding keeps the ordinary voice conversation, even if a later onboarding attempt finished without a numeric score: the voice is answered with available dialogue and learner context, without opening a call or inventing a baseline.
- A score without `practice-goal:{sessionId}` shows the same 5 / 10 / 15 / no-goal choice as the end of onboarding. There is no 10-minute placeholder. An explicit no-goal choice is stored as zero minutes and permits the call to open.
- Otherwise `POST /internal/calls/open` get-or-creates the open call, and the clip runs.

A daily reminder is still only an invitation. It does not open a call or start the clock. `/start` does not seal a call. `/onboarding` seals an open call and removes the keyboard.

For an existing exempt user without a score, the first successfully answered ordinary voice is followed by the existing Sasha onboarding announcement and its `🎙 Пройти onboarding` button. This in-chat copy adds: `Если пока не хочешь проходить onboarding — просто продолжай отправлять голосовые. Speaky будет отвечать на них, как раньше 💙`. A durable claim allows this follow-up only once, independently of the admin announcement: someone who received the broadcast may receive the follow-up once too. Later voices remain ordinary until onboarding is chosen. The legacy-user flag survives an explicit onboarding reset, so an inconclusive assessment still allows ordinary voice replies without a numeric baseline. Sending the invitation does not reset the onboarding attempt. A failed Telegram send releases the claim for a later voice. An old/stale `🎙 Start call` button shows the invitation instead of silently starting a call without a level.

After the daily goal is selected or skipped, Telegram sends a persistent lower reply keyboard with `🎙 Start call` on the visible daily-goal commitment that remains in the chat, styled green where the Telegram client supports button styles. The button appears before the reminder choice. Returning eligible users receive it again on `/start` when no call is open today. The button sends its label as a text message, which Ktor handles before reminder-time input. It uses the same onboarding/level/goal gate as an incoming voice. An eligible tap calls `POST /internal/calls/start`: ai-service get-or-creates the call, generates one short spoken question with one LLM call and synthesizes it at the saved voice speed. Relevant personal memory and recent dialogue may guide the question; the model must not invent details. A short message with `ReplyKeyboardRemove` hides the lower button, then Telegram sends the voice with the existing clock, Subtitles, and End call inline keyboard and confirms successful send through `POST /internal/calls/starter-delivered`. The question and delivery flag live in `call:{id}`. A repeat tap during an active call prompts the user to reply by voice and removes any stale lower button. A direct voice still opens a call through `/internal/calls/open`; its `alreadyActive` flag lets Ktor hide the lower button after the first processed reply, including a call that was already active when the new bot version deployed. `End call` restores it with a short message before the review. `/internal/calls/status` lets `/start` check for an open call without creating one. The starter adds no goal seconds. A new call after End or the next Moscow day may receive a new starter.

## During the call

On an eligible `🎙 Start call` tap, Ktor first checks whether a call is already active, then shows `Speaky is joining the chat… 💙` with `ReplyKeyboardRemove` while ai-service prepares the starter voice. A generation failure restores the button with a retry message. At End, the review-wait message carries the persistent `🎙 Start call` reply keyboard and remains in the chat. For onboarding goal selection, the daily-goal commitment carries the same keyboard. The bot does not delete messages that set or remove the lower keyboard.

The starter voice now opens with `Hi, <Telegram first name>! How are you?` when the name is usable, or `Hi! How are you?` otherwise, followed by a short question. The name is supplied by Ktor in the internal start request; the LLM does not invent it. The model prefers an unfinished topic from an actual prior user turn and varies natural phrasing such as `Last time you told me about ...` or `We talked about ...`. It may claim a previous discussion only when that turn supports it; otherwise it uses durable personal context without that claim, or an everyday topic. When a direct voice starts the call, the `Your turn—send a voice message.` text carrying `ReplyKeyboardRemove` remains in the chat so the keyboard removal is retained. The uploaded voice filename follows `audioContentType` (`.ogg` or `.mp3`). Telegram draws the waveform, so its shape and the effect of deleting the removal message require a live DEV check.

The latest Speaky voice carries one inline keyboard: today's recognized speech against the daily goal (`8:24 / 10:00`), `Subtitles`, and `End call` on the next row, styled red where the Telegram client supports button styles. Without a goal, it shows elapsed time only (`8:24 today`). The keyboard moves off the previous voice. Crossing a chosen goal does not end the call. The clock shows overtime, such as `12:40 / 10:00`, and one text says the day's minutes are done; no-goal calls do not send this nudge.

A recognized turn stores transcript, reply, corrections, Whisper seconds, and word timings on `call:{id}`. Silence and the clarify line add no seconds. One live correction uses the onboarding card, not the `You said` quote.

For a call opened with `🎙 Start call`, Speaky puts 👍 on the user's first processed voice reply. It saves the first and latest accepted Telegram voice message IDs in `call:{id}`, so a Ktor restart does not lose the target. A direct voice that starts a call does not get the opening 👍. When `End call` seals a call, Speaky puts ❤ on that call's latest accepted user voice. If the call has only one user voice, ❤ replaces its earlier 👍. Reaction failures are logged and do not block the voice reply or review.

The same Moscow day keeps the call open across pauses. `End call` waits until the voice already in the chat queue finishes, then seals. A voice that arrives after that tap opens the next call. Minutes for the day are the sum. `End call` is handled on `TelegramChatActions`, so it does not need a second lock.

At the next Moscow day, `open` seals the previous call. It does not push the review at night. The next voice is a new call, and one button offers yesterday's review. That button is offered once.

## Review

`POST /internal/calls/end` returns the sealed id. Telegram then immediately sends `Thanks for the chat 💙\nI’m putting your feedback together now.` while `POST /internal/calls/review` scores the call. That thank-you message remains in the chat. The result appears as a new level card; a failed score sends a new retry card, and Retry edits that retry card into the level card after a successful retry. A failed score is not cached; Retry calls the same route.

The model returns a short level note, a one-or-two-sentence recap of what you discussed, and a move from -2 to 2 for the overall level and for grammar, vocabulary, and fluency. Code applies that move to the stored snapshot and keeps each score inside its current table cell, so 50 cannot become 80 or 20 in one call. Examples still come from this call, after the same verification used by onboarding. Fluency measurements reuse `fluency_metrics`.

The cards show the updated level, then Grammar, Vocabulary, and Fluency. Continue edits the Fluency card into the final progress and recap card, preserving the same message. The line after Fluency is today's time (`🎯 0:51 / 5:00 today`), a blank line, then `recap`: one or two sentences about what you just discussed, with no score and no CEFR band. Then `🔥 2 day streak`. The final card has no inline `Profile` or `Finish for today` buttons; `/profile` remains available as a command. The persistent lower `🎙 Start call` keyboard is already restored by the review-wait message. When no reminder time is saved, the last line is `Want me to remind you tomorrow? Tap /remind`.

The stepped snapshot is written back to `assessment:{sessionId}` and, when learner memory already exists, onto its proficiency scores. A later save of the same onboarding attempt does not overwrite that snapshot. A new completed onboarding attempt does. Ordinary turns still do not change proficiency.

Deploy ai-service before Ktor. Live Telegram acceptance is separate from the unit tests.
