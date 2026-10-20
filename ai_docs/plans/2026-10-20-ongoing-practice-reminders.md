# Ongoing personalized practice reminders

Status: implemented locally; tests and DEV Telegram acceptance are required before deployment.

After the learner chooses a practice goal, reminders invite ordinary speaking practice rather than finishing at the first call. The existing one-time `Set reminder` / `Not now` question remains. `Not now` suppresses automatic reminders; a later explicit `/remind` opt-in can set a new time. A learner who saved a time receives at most one automatic reminder on a Moscow day without practice, at that chosen time. A learner who left the question unanswered receives at their inferred visit-start time after 24 hours without activity, for 14 sends plus one final note. A completed ordinary call does not end this practice series. Earlier onboarding stages keep their stage-specific copy and buttons.

Every practice invitation has `🎙 Start call`. General and personalized text alternate by Moscow day per user; a stable per-user offset puts both types in each calendar day. Personalized text is generated only from verified learner facts or recent user turns already available to the voice conversation. It must be short A2 English with one question and a cited source from the supplied context; avoid sensitive subjects that could appear on a lock screen. Missing or invalid context falls back to a general variant and is counted as general. No raw context or generated personal text is exported in analytics.

The invitation button starts with the paired spoken question from that specific reminder and supersedes an earlier reminder button; see [reminder-to-call continuity](2026-10-20-reminder-to-call-continuity.md).

The configured-time reminder ledger reports `practice_general_*` versus `practice_personalized` template IDs. The inferred-time series stores actual `copy_kind` in PostgreSQL and shows it in the onboarding reminder block. Compare accepted sends and 24-hour responses by actual text kind; these alternating observations are not randomized causal evidence. Live DEV checks must verify Telegram inline buttons, prompt grounding, `Not now`, coalescing, first-call continuation, and the 14-plus-final cap.
