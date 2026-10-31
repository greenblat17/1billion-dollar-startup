# Telegram onboarding: first value in 30 seconds

Status: implemented locally; real DEV Telegram acceptance is pending. This document supersedes the user journey in [the original Telegram onboarding plan](2026-09-28-telegram-onboarding.md) where the two differ. Existing assessment, correction, personalization, and ordinary-call contracts remain the starting point.

## Goal and scope

Give a new Telegram user a first English assessment after 30 seconds of recognized voice recordings. Let them choose whether to see it then or continue the **same** conversation for up to two minutes. Let them start ordinary practice immediately after seeing the result. This flow applies to first-time `/start` users and to a deliberate `/onboarding` restart. Existing users are not automatically forced through it.

## User flow

The approved [user-flow screenshot](../design/screens/telegram-onboarding-30s-user-flow.png) shows both checkpoint choices converging on results, followed by optional detailed feedback and **Start practicing**.

```text
/start → Welcome → Let's chat → voice conversation
                                    ↓ reaches 0:30
                              text checkpoint
                              ↙           ↘
                      See results       Keep talking · 2 min
                              ↓                ↓ same conversation
                         level card       voice turns + See results
                              ↑                ↓ reaches 2:00
                              └────────── automatic result
                              ↓
                    View detailed feedback → Grammar → Vocabulary → Fluency
                              ↓                                  ↓
                      Start practicing ←──────────────────────────┘
                              ↓
                    Start call / Practice a situation
```

## 1. Welcome

On a new user's first `/start`, send this text, preserving the line breaks:

> 👋 Hey! I'm Speaky, your English speaking buddy.
>
> Let's talk for just 30 seconds to estimate your English level.
>
> 🎯 After our chat, you'll get your first speaking feedback.

Inline button: **🎙 Let's chat**. Do not send greeting audio before the tap. A repeated tap must not create a second attempt, a parallel conversation, or duplicate first questions. `/onboarding` intentionally starts a fresh attempt using this new flow.

## 2. First conversation

The first spoken line is exactly:

> Alright, now you can hear me. And yep, this is actually my voice. I’d really love to get to know you a little. Let's start with something simple. What do you do — do you work or study?

Under that voice message show **🎯 0:00 / 0:30** and **Subtitles** on the same row, using the compact button labels from the earlier onboarding. Show this hint once, with the first question:

> 💡 Reply with a voice message. You can give short answers — I'll keep the conversation going.

After each recognized voice answer, Speaky responds in English. She first reacts to the person's answer, answers any question they asked, and then asks one contextual question. She can pursue an interesting detail while learning about work or study, interests, and reasons for learning English. She uses simple language appropriate to the learner's demonstrated level. The dialogue must not sound like a form or mention the assessment timer. Keep the existing short correction card before a reply only for a sufficiently reliable error; do not speak the correction.

Count only the user's speech intervals in each recording with recognized speech; pauses do not advance either the 30-second checkpoint or the two-minute limit. Use the recording duration only if VAD fails or times out, following the speech-time fallback rule. Keep the full recording duration separate for STT usage and billing. Silence, failed recognition, and technical failures before recognition do not advance progress. Text is not assessed. Accept the complete recording that crosses a threshold; never truncate it at 30 or 120 seconds. Show progress on the latest relevant voice message, and clear stale progress controls on older bot messages.

## 3. Thirty-second checkpoint

After processing the recording that first brings the total to **at least 30 seconds but less than 120 seconds**, send this text **instead of** the next spoken reply:

> 🎉 Great job! Your first 30 seconds are done.
>
> I can show you your first English assessment now, or we can keep talking for a more accurate result.

Inline buttons:

- **🔥 See results**: finalize the attempt from all accepted answers and show the level card.
- **🎙 Keep talking · 2 min**: keep the same attempt, transcripts, profile facts, and conversation history; immediately send a contextual follow-up question by voice.

Show the checkpoint once per attempt. Both choices are mutually exclusive and idempotent. A new voice message while the checkpoint is open counts as choosing **Keep talking**: accept it in the same attempt, process it once, and send the next contextual reply or the automatic result if it crosses 120 seconds. An old checkpoint button cannot later finalize a completed attempt. A recording that crosses both 30 and 120 seconds bypasses the checkpoint and goes directly to the automatic result.

After **Keep talking**, show progress against **2:00**, starting from the actual accumulated duration (for example, `🎯 0:38 / 2:00`, not resetting to `0:30`). Keep **Subtitles** beside the progress button and **🔥 See results** on the row below under the latest Speaky voice. The user may stop at any later point before two minutes; that action finalizes the accepted answers so far. At **at least 120 seconds**, finalize automatically after processing the full recording. Do not send an additional voice goodbye. The next bot message is the result card. Remove or invalidate old stop and continue buttons when the attempt ends.

## 4. Results and feedback

Use the existing Telegram level card layout, CEFR and score mapping, evidence rules, and insufficient-evidence state. The result is generated from the accepted transcript, not from a fixed 30-second score. Any result finalized before 120 seconds must be marked **Preliminary estimate**. Longer conversations may support more detail, but the report must only make claims supported by the actual speech sample. If there is too little connected English for a reliable CEFR estimate, show the existing no-clear-level state; never invent a level to unlock practice.

The `levelText` explanation on the card is one plain English sentence of at most 25 words about an observed language ability. Use a short exact excerpt only when it clarifies the evidence. Avoid formal openings such as “You demonstrate a strong ability”. For weak evidence, keep the explanation tentative; for insufficient evidence, say briefly that this sample cannot support a clear estimate.

Under the level card show two actions:

- **📊 View detailed feedback** opens the existing Grammar → Vocabulary → Fluency sequence.
- **🎙 Start practicing** opens the ordinary Speaky menu directly.

On the Fluency card, **🎙 Start practicing** replaces the current transition into daily-goal selection. Keep verified examples and the existing unknown/insufficient-evidence states in the detailed cards. The result and feedback are available again on a valid repeated user tap; duplicate delivery of the same Telegram callback must not produce duplicate messages.

## 5. Practice handoff

**Start practicing** shows the existing reply keyboard with **🎙 Start call** and **🎭 Practice a situation**. It does not start a call by itself. Both options must work after a completed onboarding attempt even when no daily goal was chosen or the estimate has no numeric score. Preserve the collected conversation and personal facts for later replies. The score may be refined by subsequent full calls under the existing review rules.

Daily-goal choice (`5/10/15 min`) and reminder setup are removed from the required onboarding path. Do not show either prompt after the level card or detailed feedback, and do not silently save a zero-minute goal. Existing voluntary `/remind` and previously saved goals/reminders remain usable. A later, contextual offer after ordinary product use is a separate follow-up decision; this change does not define its trigger or copy.

## State, integration, and analytics requirements

- Extend the AI-service onboarding state machine with a persistent checkpoint and continuation state. A technical retry reuses the accepted transcript and duration; it must not count a recording twice. Persist the chosen path and cached result so restarts and repeat callbacks cannot fork the flow.
- Telegram/Ktor renders the new copy and buttons, serializes actions per chat, rejects stale attempt IDs, and keeps the existing voice queue limit. Update both `callGate()` and the separate `canStartCall()` check: `completed` onboarding opens ordinary practice without requiring a non-null score or daily-minutes choice. Do not weaken the gate for `waiting`, `active`, or pending review.
- Reuse the existing assessment and review pipeline for both exit points. Do not require missing work, leisure, or English-goal facts to close. An assessment/review failure offers a retry that uses saved answers and does not ask the learner to repeat speech.
- Update onboarding journey analytics to distinguish `30s_checkpoint_reached`, `results_at_checkpoint`, `continued_to_2min`, `results_early_after_continue`, `results_at_2min`, `feedback_opened`, and `practice_menu_opened`. Record the actual accepted duration at finalization. Update incomplete-onboarding follow-up eligibility and buttons for the new checkpoint and continuation states; never send a prompt that would restart or misroute an active attempt.

## Acceptance criteria

1. First-time `/start` shows the exact Welcome copy and button. A double tap produces one first question.
2. The first voice, one-time hint, progress, and subtitles use the specified copy. A short answer gets a contextual spoken turn and at most one reliable correction card.
3. The first crossing of 30 seconds shows one text checkpoint, with no spoken next question. A single recording crossing 120 seconds goes directly to results.
4. **Keep talking** immediately produces one contextual voice question. Progress keeps its accumulated value and changes its target to 2:00; **See results** stays accessible on the latest voice.
5. Either stop action and automatic two-minute completion show one cached level card without a goodbye voice. A 30-second result is marked preliminary; insufficient evidence remains an honest no-clear-level result.
6. The level card opens detailed feedback or practice. The last feedback card opens practice. The menu offers **Start call** and **Practice a situation**; both are usable without a goal or numeric assessment.
7. Silence, failed STT, retries, repeated callbacks, concurrent voice arrivals, stale buttons, and `/start` or `/onboarding` interruption do not duplicate time, results, or conversations.
8. No daily-goal or reminder prompt appears in the onboarding path. Existing users, saved choices, ordinary calls, and voluntary `/remind` retain their intended behavior.
9. Verify the complete flow in a real DEV Telegram chat before claiming live acceptance. Local tests alone do not verify button rendering, voice delivery, or conversation quality.
