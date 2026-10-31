# Telegram personality, mode, and history refactor

Status: Phases A–C implemented locally; DEV acceptance pending. Phase D pending.
Decision date: 2026-10-31.

This plan supersedes the *future architecture* implied by the optional-character
integration in [Kora in Telegram](../integrations/2026-10-20-telegram-kora.md).
That document remains the record of current behavior and past rollout decisions.
The scope here is Telegram. Do not change the CMP app or deploy as part of writing
this plan.

## Product contract

The user sees one conversation with a selected speaking personality. Speaky and
Kora H differ in how they form a spoken reply and how they synthesize it. They do
not own a role, mode, assessment, or dialogue history.

```text
Telegram turn
  -> current mode and its rules
  -> mode-owned history and verified learner context
  -> selected personality's reply formation
  -> selected personality's speech synthesis
  -> delivery, review, and analytics for the mode
```

The modes are onboarding, free conversation, and situation practice. A situation
may assign a fictional role such as interviewer or manager. That role takes
priority over personality style. Onboarding keeps its profile questions, 120
seconds of recognized speech, score schema, retry rules, and result flow.
Personality can change the wording of a spoken response within those rules.
Review, correction, streak, goal, reminder, and feedback mechanics belong to
the mode or shared session, not the personality.

Free conversation has one chronological history across personalities. An
onboarding attempt retains its own attempt history. A situation retains its
own call-local history and does not add fictional facts to free conversation or
learner memory. The history service chooses and returns the relevant history;
the personality receives it as input and never chooses a storage key.

Every voice message, including fixed greetings, the first onboarding question,
clarifications, call openings, and closing lines, uses the selected personality's
TTS. Existing fixed wording may remain fixed. A fixed phrase does not require an
extra LLM call solely to restate it.

Personality is fixed for an active call and an onboarding attempt. A pending clip
also blocks a change. During onboarding, a new choice takes effect after the
attempt completes. The first release retains the current menu and switching
behavior. The final release makes Kora H mandatory and removes `/voice` from
the command menu; old `/voice` commands and buttons must give a clear status
response rather than silently selecting Speaky.

## Current code and incompatibilities

| Concern | Current implementation | Target owner |
| --- | --- | --- |
| Selection | `CharacterStore` uses `character:{session}`; absent means Speaky | Selection policy, independent of history and mode |
| Free history | Speaky uses `{session}` and Kora H uses `{session}:kora_h` | Shared free-conversation history |
| Free reply | `ClipPipeline` branches between `OpenAiChatModel` and `KoraHModel` | Personality reply adapter, given mode context |
| Free speech | Speaky uses `SessionSpeech`; Kora H uses `GeminiKoraSpeech` | Personality speech adapter behind one delivery API |
| Onboarding | `OnboardingService` uses its own model and `SessionSpeech` | Onboarding mode plus personality reply and speech |
| Situations | `Characters.variant(..., scenario=...)` forces Speaky | Situation mode plus personality reply and speech |
| Call metadata | Call stores `character`; switching can seal an opening | Mode owns call lifecycle; personality is a recorded dimension |
| Feedback | Speaky is once per user; Kora H is per call | Once per user per mode after first accepted rating |

Primary source files: `ai-service/app/characters.py`, `pipeline.py`,
`call_start.py`, `onboarding.py`, `onboarding_model.py`, `dialogue.py`,
`speech.py`, `gemini_speech.py`, `calls.py`, `first_call_feedback.py`, `main.py`,
and Telegram `VoiceMessages.kt`, `TelegramHandlers.kt`, `TelegramWebhook.kt`.

The present code intentionally excludes onboarding and situations from Kora H.
The change is a new product decision, not a claim that the prior release was
implemented incorrectly against its documented scope.

## Interfaces and ownership

Define explicit, typed inputs rather than a single `character` flag that makes
decisions throughout the pipeline:

- `ConversationMode`: `onboarding`, `free`, or `situation`, with mode-specific
  state and output constraints. The situation includes its kind and untrusted
  description. The mode determines what the next turn must accomplish.
- `Personality`: `speaky` or `kora_h`, with its model, style instructions, TTS,
  delivery tone behavior, and provider configuration. It does not access Redis
  histories, call records, onboarding state, or feedback records.
- `HistoryContext`: ordered turns for the mode, with a stable scope ID and
  retention. The history service reads and commits it. Preserve the current
  delivery and retry rules, including no duplicate turn on a replay.
- `SpokenTurn`: final text plus an optional allowed delivery tone and private
  diagnostic metadata. TTS consumes the final text; subtitles and saved reply
  use that same text. Provider or audio failure must not silently switch the
  personality or count a failed turn as delivered.

The orchestration layer chooses mode, history, profile, and personality. Mode
rules are composed before personality style. Do not insert Kora H's current
free-conversation prompt unchanged into a situation: its topic-shifting rules
can break the fictional role. Do not put scoring instructions in the
personality. Keep structured assessment and review validation independent.

For onboarding, the current `assess()` response contains both assessment data
and the next spoken `question`. First preserve that path behind an adapter.
When Kora H onboarding is enabled, separate assessment facts and next-question
obligations from personality wording. The Kora H writer must use the required
ask, verified facts, and prior attempt turns. Validate that it asks one
answerable question, does not re-ask a filled field, and cannot change the
assessment. This may add a model call; measure its latency and cost before
release. Keep the Speaky path equivalent until separately accepted.

## Release sequence

### A. Behavior-preserving architecture

1. Record a baseline on DEV for model route, TTS route, call opening, retry,
   feedback offer, and analytics for both current choices. Use synthetic or
   consented real conversations; keep raw audio and transcripts out of logs.
2. Introduce the mode, personality, history, and spoken-turn interfaces.
   Move reusable provider and speech mechanics behind them; keep lifecycle and
   assessment policy in the mode orchestrators.
3. Wrap the current Speaky and Kora H implementations without changing their
   prompts, provider settings, selection default, `character` API, history
   keys, menu, feedback rule, or current mode exclusions. Keep the current
   lock on switching during an active call or pending job.
4. Route call opening, ordinary clip replies, fixed clarification, and current
   onboarding speech through the new interfaces. Preserve the old output and
   failure contracts, including speech speed and OGG/MP3 delivery.
5. Run focused AI and Kotlin regressions. On DEV, complete a Speaky free call,
   a Kora H free call, onboarding, and each situation type. Check actual audio,
   subtitles, stored turn count, call review, and current feedback behavior.

**Gate A:** No intended user-visible change. Compare route/provider logs,
response schema, state transitions, and Telegram outcomes with the baseline.
Do not infer equivalence from green unit tests alone.

Local implementation: `ai-service/app/conversation.py` defines the mode,
personality, history context, spoken turn, routing, history access, and provider
adapters. `pipeline.py` and `call_start.py` use them for free calls and
situations. Onboarding speech uses the same speech adapter.
The selection default, provider prompts, API, history keys, feedback rule, and
Telegram menu remain as before. Local AI suite: 492 passed. Targeted Kotlin
tests did not reach execution because a fresh Gradle setup stalled while
configuring tasks. DEV baseline and end-to-end audio checks remain required
before Gate A is accepted.

### B. Shared free-conversation history

1. Finish A on all AI instances before enabling B. Add a versioned history
   migration that reads the current `{session}` and `{session}:kora_h` histories.
   `ChatMessage.created_at` permits chronological ordering; only retained
   seven-day content is eligible. Legacy turns without a valid timestamp stay
   excluded under the existing retention rule.
2. Merge by timestamp into a **new** canonical free-history key, distinct
   from both legacy keys. Remove only exact
   duplicate stored records, including timestamp, role, and content. Repeated
   utterances at different times are distinct. Preserve the pair order for
   equal timestamps. Apply the existing message-window limit after merging.
3. Make migration idempotent and race-safe with Redis compare-and-set or an
   equivalent transaction over both old keys and the new destination.
   Mark completion before serving a new turn. During the rollback window,
   write each new turn to the canonical history and its original personality's
   legacy history atomically. This preserves the old revision's view of each
   history. Keep the old keys usable for a seven-day rollback window, without
   extending the seven-day content retention; do not log raw content. After
   that window, use a B-compatible revision as the rollback
   target rather than an A revision that depends on old keys.
4. Make both personalities read and write the same canonical free history. Keep
   onboarding-attempt and situation-call histories separate by mode. A switch
   between calls changes the reply/speech adapters, not the history scope.
5. Test parallel first access, retry after a failed write, duplicate delivery,
   expired turns, legacy untimestamped turns, and rollback while old keys still
   exist. Verify a real two-personality DEV conversation follows both earlier
   topics without invented claims.

**Gate B:** Existing free turns are ordered once, no retained turn is lost,
and no fictional situation turn enters free history. This stage intentionally
changes the context seen after a personality switch.

Local Phase B implementation: Telegram free calls always use
`session:{session}:free:v1`; there is no activation flag. The first
access merges `session:{session}` and `session:{session}:kora_h` with Redis
`WATCH`/`MULTI`, then writes a migration marker in the same transaction.
Only records with a valid timestamp inside seven days enter the merge. Exact
duplicates are removed, equal-time order is stable, and the normal 40-message
window applies. Each subsequent turn writes the canonical history and its
personality's legacy history in one Redis transaction for seven days after
the first shared-history access across that Redis deployment. Turn IDs make retries
idempotent; a conflicting retry fails. The deadline key remains in Redis, so
a process restart cannot restart the rollback window. After the deadline,
the canonical history remains active and legacy keys expire under their
existing content retention. Speaky free turns now commit after successful
audio synthesis. Onboarding's existing remembered
turns and old continue action use the shared history through Speaky's rollback
stream. An opening uses the personality stored on its call for the rollback
write, even if the current selection later changes. Situation calls remain
call-local.
The local AI suite passed 504 tests, including parallel migration, timestamp
retention, exact deduplication, retry identity, dual-write expiry, and an
opening write retry. Live two-personality DEV conversation quality is untested.

Release: shared history starts as soon as this revision runs. Complete Gate A
and DEV acceptance before deployment. Coordinate replacement of all AI
instances so an older writer cannot update a legacy key after its migration.
Keep an A-compatible rollback image for the seven-day dual-write window;
after that, rollback must use a B-compatible image. This code change did not
deploy to DEV or PROD.

### C. Personalities in every mode and mode-based feedback

1. Add a Kora H onboarding reply adapter that respects the same assessment
   schema, required ask, 120-second threshold, and delivery retry behavior.
   Keep fixed text fixed where appropriate; synthesize every spoken line with
   the active personality. Lock the personality to the onboarding attempt.
2. Add Kora H situation opening and turn adapters. The situation role and
   call-local history control content; Kora H affects language, conversational
   move within the scene, delivery tone, and TTS. Validate role continuity and
   prevent generic free-conversation topic changes.
3. Apply the selected personality to free, onboarding, and situation modes.
   Retain Speaky as default at this stage, and keep the current `/voice` menu.
   A Kora H provider failure returns an explicit retryable error; it never
   silently uses Speaky.
4. Replace persona-based feedback frequency with three independent groups:
   onboarding, free conversation, and all situations together. After each
   completed mode instance, offer the current short button prompt until the
   user submits the first accepted rating in that group. A skip or unanswered
   prompt does not stop future offers. Keep optional comment handling.
5. Migrate only historical ratings whose mode can be established from a linked
   call or onboarding record. Do not guess a mode for older ambiguous ratings.
   Keep old feedback data readable and separately labelled. Ensure prompt
   delivery failure does not mark an offer as delivered.
6. Update analytics to record mode and personality as independent dimensions.
   Preserve historical `call_character` facts and their original labels. Do
   not relabel past calls or imply historical mode/personality for unknown rows.
7. Run a matrix on DEV: both personalities × three modes, plus situation kinds
   job, manager, and custom. Include opening, normal turn, silence/clarify,
   retry, close, review, feedback, `/speed`, and provider failure. Review full
   conversations for role, onboarding goal, and quality, not just transport.

**Gate C:** Both personalities work in every mode. Mode state, assessment,
role, history scope, and feedback completion are independent of personality.
Compare per-mode latency, errors, and model/TTS cost with the baseline before
release; accept any material increase explicitly.

Local Phase C implementation: Telegram resolves the selected personality in
all three modes. An onboarding attempt pins it at the first spoken question;
the character API blocks changes while that attempt is active or pending.
The existing assessment still chooses profile facts, CEFR, and the required
ask. Kora H writes the spoken next question from those facts and the attempt
history, then uses Kora H speech. A separate Kora H closing callback keeps the
existing review and 120-second completion path. A situation pins the call's
personality, gives Kora H its fictional role and only that call's turns, and
keeps the existing situation review and learner-memory exclusion. Fixed spoken
lines use the pinned personality's speech. Stored Kora H selection fails
explicitly when its providers are unavailable.

Feedback now uses three mode groups. Every eligible completed instance can
offer the existing short buttons until one rating is accepted in that group.
An unanswered offer does not block the next instance. Telegram confirms an
offer only after sending the prompt and releases a failed send. Old ratings
remain readable; only ratings with a linked, unexpired call can suppress a
new mode's prompt. New free/situation ratings mirror the old feedback record
when possible during rollback. Dashboard rows show mode and personality
separately; old rows without a proven mode remain unlabelled. Local Python
and Kotlin tests cover routing, onboarding assessment independence, situation
context, pinning, feedback state, and dashboard output. DEV audio, dialogue
quality, latency, and cost acceptance remain open. No deployment occurred.

### D. Mandatory Kora H

1. After C is accepted, release Kora H as the effective personality for every
   Telegram user, including users who previously selected Speaky. Treat this
   as an intentional product change. Keep the ability to roll back the release
   operationally, without a hidden per-turn fallback.
2. Existing active calls and onboarding attempts finish with their pinned
   personality. New calls and attempts use Kora H after the rollout. A later
   Speaky selection from an old button cannot override the mandatory policy.
3. Remove `/voice` from registered bot commands and the new UI. Handle typed
   `/voice` and old inline callbacks with a short status message. Remove copy
   that asks users to choose a partner. Keep `/speed` working with Kora H.
4. Confirm Kora H provider readiness and all-user eligibility before rollout.
   Do not use an allowlist setting that silently reroutes users to Speaky. If
   Kora H is unavailable, return an explicit error and retry path.
5. Run DEV end-to-end acceptance, then a controlled PROD release with health,
   error, latency, cost, and feedback monitoring. Require a separate scoped
   authorization before any PROD deployment. The rollback restores the prior
   image and selection policy; preserve current state and history for recovery.

**Gate D:** New onboarding attempts, free calls, and situations all use Kora H
for generated replies and every spoken line. Old selection controls cannot
select Speaky. Existing attempts complete consistently.

## Verification and risk controls

- Unit tests: mode/personality precedence, structured onboarding validation,
  situation-role constraints, fixed-line TTS routing, speed, unavailable
  provider behavior, and feedback once per user per mode.
- State tests: active-call and onboarding pinning, no character switch on
  retries, delivery acknowledgment, no double grade or turn, seven-day
  retention, and shared-history migration under concurrent requests.
- API and Telegram tests: old and new callback data, `/voice` compatibility,
  call metadata, subtitles, voice file format, menu registration, and review
  ordering. Run Gradle from `cmp/`.
- DEV acceptance: listen to complete conversations, inspect the exact model
  and TTS routes, verify mode-specific behavior and analytics denominators,
  then measure p50/p95 reply latency and per-completed-turn cost. A green CI
  run or valid audio file is insufficient evidence of conversational quality.
- Rollback: each release has a compatible revision and stored data.
  B keeps a distinct canonical history and dual-writes legacy histories during
  the rollback window. C preserves old feedback records and dual-writes the
  legacy Speaky free-call rating where possible. D can restore the C route
  without changing historical labels. Never use a partial rollback that
  silently changes a user's selected personality.

The first release can preserve current behavior. Releases B, C, and D make
the explicit changes agreed above, each with separate acceptance. No code,
deployment, or live user state changes are part of this plan document.

## Planning estimate

For one engineer, allow roughly 2–4 working days for A, 1–3 for B, 3–6 for C,
and 1–2 for D, plus separate DEV conversation review and PROD release windows.
These are planning ranges, not delivery commitments. The earlier 3–6 day
estimate covered a narrower refactor and does not cover the agreed history
migration, all-mode support, feedback change, and mandatory-Kora rollout.
