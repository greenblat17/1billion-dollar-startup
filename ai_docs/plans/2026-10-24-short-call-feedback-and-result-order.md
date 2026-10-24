# Short call feedback and practice result order

Status: implemented locally; automated verification complete, live DEV Telegram acceptance pending.
Decision from the 2026-10-24 interview.
Scope: ordinary Telegram practice calls. Onboarding keeps its existing result
order and its two-minute completion rule.

## Product behavior

- Measure call length as the sum of recognized learner speech seconds already
  stored on the call. Less than 30 seconds always yields an insufficient-speech
  result. Exactly 30 seconds can proceed to the model check.
- At or above 30 seconds, the existing call-review model decides whether the
  learner produced enough connected, substantive English for a useful review.
  Isolated yes/no answers, repetition, or recognition noise can be clearly
  insufficient. If the judgment is uncertain, deliver a cautious full review.
- An insufficient-speech result is one English text message without inline
  buttons, skill sections, correction examples, Vocabulary lessons, score
  changes, or a character-feedback survey. Use this copy: "Let’s talk a bit
  more. I need to hear more of your English to give you useful feedback.
  Start a new chat when you’re ready." The persistent Start call keyboard
  remains available. The spoken seconds still count toward daily activity and
  time; the saved assessment and proficiency are not changed.
- A new full review uses this order in one editable message: Grammar →
  Vocabulary → Fluency → Level. The final Level card shows the level, score,
  progress to the next band, current streak, and reminder offer when relevant.
  It omits the diagnostic paragraph, accuracy note, daily speaking time, and
  recap. It has no separate progress card. Show the
  existing Speaky/Kora H feedback survey after entering this final card.
- Reviews already cached before the change retain their existing level-first
  navigation and recap/progress screen. The new policy applies to new reviews.

## Implementation plan

1. Add a review result kind (`full` or `insufficient`) and a presentation
   version to the saved call-review payload and Kotlin response DTO. Missing
   fields on historical cached reviews mean the existing level-first format.
   Keep the response parseable during deployment of AI service before Ktor.
2. In `CallReviews.review`, inspect the sealed call's recognized speech time
   before starting correction verification, Vocabulary discovery, or review
   generation. Cache an insufficient result immediately below 30 seconds.
   Return it without calling `save_assessment` or syncing proficiency.
3. Add an evidence-sufficiency decision to the existing structured
   `compose_call_review` response and prompt. In `_compose`, preserve the
   current correction and enrichment work for full reviews. If the model
   decisively reports insufficient connected speech, omit the generated
   scores, examples, suggestions, and recap; do not save an assessment. Cancel
   outstanding enrichment work when possible. No additional sequential model
   request is required. Keep the conservative rule that uncertainty yields a
   cautious full review.
4. Remove recap generation from new full reviews. Keep the field optional or
   defaulted in transport so historical cached reviews still render their
   original recap. Keep the existing idempotent cached-review behavior.
5. In Telegram, route new full reviews to Grammar first, then Vocabulary,
   Fluency, and a final combined Level card. The last transition replaces
   the current separate progress transition and triggers the existing durable
   feedback-offer action once. Route historical reviews through their current
   level-first actions. Route insufficient reviews to the single text message
   with no inline keyboard and no feedback offer. Preserve Retry for actual
   review failures.
6. Make the initial thank-you/wait message valid for both full and short
   outcomes. Retain its persistent Start call keyboard. Distinguish an
   insufficient result from a failed review in delivery events and metrics,
   so it does not inflate model failures or score-delivery counts.

## Verification

- Server: 29.9 seconds skips all models and leaves every score unchanged;
  30.0 seconds reaches the model. Test a clearly insufficient 30+ second
  transcript, an uncertain transcript that gets a full review, and an ordinary
  full call. Check stored review idempotency and unchanged daily time/streak.
- Telegram: new and historical cached reviews follow their respective
  orders; the combined Level card fits Telegram limits and offers feedback
  once. A short result has only the agreed text and no inline button or
  character-feedback survey. Review failures still show Retry.
- Run Python and Kotlin server tests, inspect the combined diff, then test
  both branches in a real DEV Telegram chat before calling the behavior
  accepted. No production deployment is part of this plan.

## Implementation note

The Python review response now stores `reviewKind` and `presentationVersion`.
The Kotlin DTO defaults missing fields to the historical full-review format.
New reviews use presentation version 2. The original progress and recap card
remains available only for previously cached reviews. New full reviews no
longer ask the model for a recap.
