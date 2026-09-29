# Telegram voice onboarding

Status: implemented on `feature/telegram-onboarding`; automated verification below. Live DEV acceptance is still required before production deployment.

## Agreed experience

Only new Telegram users automatically enter onboarding. Resolve eligibility before recording their first new funnel event: an existing funnel/chat record means the old greeting and ordinary conversation continue. `/onboarding` explicitly starts a fresh attempt for anyone.

1. `/start` sends the short bilingual invitation and inline `Давай 👋` button. Name comes from Telegram, with a nameless fallback. No greeting audio until the button is pressed.
2. The button sends a spoken introduction: Speaky names herself, says this is her voice, and that she wants to know them, then asks what is taking up their days. Then `🎙 Ответь голосовым на английском. Не переживай насчёт ошибок.` That hint carries one inert button, `0:00 из 2:00` plus a 16-cell bar. After each later answer the button is removed from the previous message and attached to the latest reply. The bar counts recognized speech and stops at 2:00. Tapping it does nothing. Speaky does not mention it.
3. Voice answers receive the existing correction quote and an English voice reply. The reply is a full turn: a specific warm reaction, then one question. The code still picks the next missing fact — work, then free time, then why English, then a personal follow-up — but the turn is not allowed to be only that question. No biography, no shared-interest claim, no Russian hint under the question, no timer.
4. The closing turn is still a correction quote plus a spoken English question. Telegram then sends `Я тебя запомнила. Давай просто говорить.` The progress button moves under that line. There is no continue button. The level is not shown. A grammar, vocabulary, and fluency review is deferred.
5. The next voice is ordinary conversation. Old `Продолжить разговор →` buttons still work; new attempts do not send that button.

## Timing and boundaries

- Count whole recordings with recognized speech, including pauses, using STT duration with Telegram duration as fallback. This is not measured speaking time.
- Keep going until the checklist is filled: work, free time, and why English. "I don't work" or "no hobbies" fills the field.
- Close when the checklist is filled and recognized speech has reached 120 seconds. A missing level does not block this close.
- Close earlier when there are 10 recognized answers, the checklist is filled, and a CEFR estimate exists.
- A missing checklist field does not stop at 120 seconds, and there is no later safety cap.
- Accept the recording that crosses a close condition in full.
- Silence, failed recognition, and technical failures before recognition do not spend the recording budget. Recognized non-English speech spends time but is not evidence of English proficiency. Onboarding uses STT language autodetection; ordinary English practice retains its English hint.
- Text gets the voice-message hint and is not analyzed. Voice before `Давай 👋` gets the start-button hint and is not consumed.
- `/start` during an incomplete attempt resets it. Voice without `/start` continues the saved attempt. `/start` after completion uses the ordinary greeting.

## Implementation

Ktor owns Telegram rendering and serializes chat actions through reply delivery, with at most three admitted voice messages. AI-service owns the onboarding state machine. Existing STT, correction generation, LLM, TTS, polling jobs, usage metrics and streaks are reused; no new analytics or dashboards.

`onboarding:{sessionId}` stores one current attempt without the dialogue TTL: run id, state (`waiting`, `active`, `pending`, `completed`, `exempt`), recording duration, questions, transcripts, corrections, partial analyses, profile and final CEFR/text. Redis has an in-memory equivalent for tests. Restart/explicit reset replaces the attempt rather than archiving it. Raw audio is only transient in clip jobs, not archived.

Checkpoints prevent technical retries from counting or transcribing a recognized recording again. The current run id rejects stale buttons/jobs. Recent command and callback receipts are persisted (256 each per chat/attempt); the Telegram process also suppresses repeated incoming actions. Per-session AI locks follow the existing single-worker deployment model; horizontal multi-worker deployment requires distributed serialization first.

At the final boundary the attempt becomes `pending`. Failure to build the result produces `Повторить`, which reuses the saved answers. Successful results are cached. Intermediate failures after recognition can also be retried with saved data; failure before recognition asks for another voice message. Telegram send/ack and Redis writes are not a distributed transaction; exactly-once external delivery is not guaranteed during a network loss.

On close, the introduction's recognized turns are copied into ordinary dialogue history. Later replies also receive a hidden note with work, free time, goal, and CEFR. The level may change how simple the English is, and it must not be spoken. Ordinary conversation does not update the profile. `/onboarding` replaces the attempt immediately, so the saved profile is gone until the new attempt completes. `/start` after completion does not reset it.

## Validation and release

Automated coverage: the 120-second close, the 10-answer close, missing fields past two minutes, missing level, silence, a recording that crosses the cap, duplicate actions, Redis persistence, profile wipe on `/onboarding`, hidden profile on the next reply, provider failures/retry, authenticated endpoints, closing audio, chat ordering/capacity and legacy clip behavior.

Verified locally: Python 3.12 `pytest` — 82 passed; `:server:test` and repository `detekt` — passed. `git diff --check` — passed. No live provider calls or Telegram acceptance run.

Repeat with Python 3.12 `pytest`; from `cmp/`, `GRADLE_USER_HOME=$HOME/.gradle ./gradlew :server:test detekt`.

Deploy AI-service first: older clients still use ordinary clips, while new fields are optional. Deploy Ktor second. Both use the existing Redeploy DEV / Redeploy PROD workflows. Before PROD, manually test the flow on DEV with new and existing users, brief/long answers, limited English, silence, repeated buttons, interrupted onboarding and the CEFR outcome. No live bot messages or deployments are part of local implementation verification.
