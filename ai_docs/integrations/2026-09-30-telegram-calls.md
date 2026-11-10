# Telegram practice calls

Situation practice extends the same call lifecycle. Its menu, scenario state, fictional
memory boundary, and MVP scope are recorded in
[технический контракт](voice-runtime.md).

Это сохранённая функция голосовой практики, не trial нового репетитора и не Mini App.
Профиль `learner:` и rolling voice history сохраняются между calls; с `tutor:v1` они не связаны.

## When a call opens

Явный `Start call` или выбор ситуации вызывает `POST /internal/calls/start`.
Ktor проверяет активный Vocabulary Builder и существующий call; старого onboarding/score/goal gate нет.
Обычное голосовое сообщение **не открывает** call: без активного call оно игнорируется.
Внутренний `/internal/calls/open` остаётся API, но не делает голос входом в onboarding.
`/start` и `/onboarding` переходят к Саше, сохраняют память и не завершают call.
Напоминание само по себе не открывает call и не запускает clock.

Старт сохраняет вопрос и delivery flag в `call:{id}`; подтверждение доставки идёт через
`POST /internal/calls/starter-delivered`. Повторное нажатие при активном call не создаёт второй звонок.
Стартовый вопрос не добавляет goal seconds. `End call` возвращает прежнюю call-клавиатуру и запускает review.

## During the call

On an eligible `🎙 Start call` tap, Ktor first checks whether a call is already active, then shows `Speaky is joining the chat… 💙` with `ReplyKeyboardRemove` while ai-service prepares the starter voice. A generation failure restores the button with a retry message. At End, the review-wait message carries the persistent `🎙 Start call` reply keyboard and remains in the chat. The bot does not delete messages that set or remove the lower keyboard.

When a Telegram response will contain voice, Ktor shows the recording-voice action during voice preparation and again immediately before upload. This includes the greeting and the `Start call` opening, as well as Vocabulary Builder turns.

The starter voice now opens with `Hi, <Telegram first name>! How are you?` when the name is usable, or `Hi! How are you?` otherwise, followed by a short question. The name is supplied by Ktor in the internal start request; the LLM does not invent it. The model prefers an unfinished topic from an actual prior user turn and varies natural phrasing such as `Last time you told me about ...` or `We talked about ...`. It may claim a previous discussion only when that turn supports it; otherwise it uses durable personal context without that claim, or an everyday topic. In the retained call voice path, the `Your turn—send a voice message.` text carrying `ReplyKeyboardRemove` remains in the chat so the keyboard removal is retained. The uploaded voice filename follows `audioContentType` (`.ogg` or `.mp3`). Telegram draws the waveform, so its shape and the effect of deleting the removal message require a live DEV check.

The latest Speaky voice carries one inline keyboard: today's recognized speech against the daily goal (`8:24 / 10:00`), `Subtitles`, and `End call` on the next row, styled red where the Telegram client supports button styles. Without a goal, it shows elapsed time only (`8:24 today`). The keyboard moves off the previous voice. Crossing a chosen goal does not end the call. The clock shows overtime, such as `12:40 / 10:00`, and one text says the day's minutes are done; no-goal calls do not send this nudge.

A recognized turn stores transcript, reply, corrections, Whisper seconds, and word timings on `call:{id}`. Silence and the clarify line add no seconds. One live correction uses the shared correction card.

For a call opened with `🎙 Start call`, Speaky puts 👍 on the user's first processed voice reply. It saves the first and latest accepted Telegram voice message IDs in `call:{id}`, so a Ktor restart does not lose the target. When `End call` seals a call, Speaky puts ❤ on that call's latest accepted user voice. If the call has only one user voice, ❤ replaces its earlier 👍. Reaction failures are logged and do not block the voice reply or review.

The same Moscow day keeps the call open across pauses. `End call` waits until the voice already in the chat queue finishes, then seals. After End call, a voice alone does not open another call; the user must explicitly start it. Minutes for the day are the sum. `End call` is handled on `TelegramChatActions`, so it does not need a second lock.

At the next Moscow day, `open` seals the previous call. It does not push the review at night. The internal open operation can create a new call and offer yesterday's review; Telegram still requires an explicit start when no call is active. That button is offered once.

## Review

`POST /internal/calls/end` returns the sealed id. Telegram shows a typing indicator while `POST /internal/calls/review` checks the call. An insufficient result sends one formatted `🎙 Let’s talk a bit more` message with the persistent Start call keyboard. A full review sends `Thanks for the chat 💙` with that keyboard, followed by the first inline result card. A failed review sends a retry card after the thank-you message; Retry edits that card after success. A failed review is not cached; Retry calls the same route.

The model returns diagnostic text and a move from -2 to 2 for each existing score.
A null skill score receives its first independent placement from sufficient current-call evidence.
Existing score anchors remain for compatibility; the scale ends at A1/C1 (10–96).
Grammar examples are separately verified, with up to five retained examples and optional saved explanations.
Vocabulary upgrades are separate learning suggestions, not evidence of mistakes or a lower score.
[Correction contract](2026-09-30-spoken-corrections.md) and
[Vocabulary workflow](2026-10-21-vocabulary-assessment-workflow.md) describe the validation.
Review model and fallback retain the ONBOARDING_REVIEW_MODEL names for compatibility.

The stepped snapshot is written back to `assessment:{sessionId}` and, when learner memory already exists, onto its proficiency scores and derived skill bands/positions. A later save of the same onboarding attempt does not overwrite that snapshot. SP-01 no longer creates completed voice-onboarding attempts. Ordinary turns still do not change proficiency.

Deploy ai-service before Ktor. Live Telegram acceptance is separate from the unit tests.

## Short calls and result order, 2026-10-24

For new ordinary calls, fewer than 30 recognized learner-speech seconds produces
one insufficient-speech message. It has no skill cards, score changes, inline
button, or character-feedback survey. At 30 seconds or more, the existing
review model marks only clearly insufficient connected speech as insufficient;
uncertain evidence gets a cautious full review. Insufficient calls still count
their recognized seconds toward daily activity and speaking time.

New full reviews use Grammar → Vocabulary → Fluency → Level. The Level card
shows the level, score, next-band progress, streak, and reminder offer when
needed. It omits the diagnostic paragraph, accuracy note, and daily time.
The character-feedback survey follows that card. The model no longer writes a
conversation recap for new reviews. Cached reviews without a presentation
version retain the old level-first carousel, followed by Grammar, Vocabulary, Fluency and a recap/progress card.
The review response distinguishes `reviewKind=full|insufficient` and
`presentationVersion=2`; Ktor defaults missing fields to the old format.
The agreed behavior and verification boundary are in
[технический контракт](voice-runtime.md).

## DEV call-review timeout, 2026-10-23

Two DEV calls showed `Couldn't score this conversation. Try again.` because
Ktor timed out after about 10 seconds on `POST /internal/calls/review`. AI
continued and saved both reviews; the model did not fail. The review request
now has a 90-second request and socket timeout, like call start. Other AI
requests keep their existing limits. A delayed-response HTTP test covers the
override. The existing Retry button can read the saved review without scoring
the call again. A live Telegram retry remains to be checked after deployment.
