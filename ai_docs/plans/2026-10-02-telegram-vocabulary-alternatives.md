# Optional Vocabulary alternatives in Telegram reviews

Status: implemented on `feature/telegram-onboarding-polish`; local verification passed. The synthetic DEV model probe and the user's choice of the softer prompt are recorded in [the evaluation](../researches/2026-10-02-vocabulary-alternatives-model-eval.md). Deployed Telegram appearance and broader model quality remain unverified.

## Decision

The Vocabulary slide may show an optional `Another way to say it` section only when its review has no accepted Vocabulary correction examples. This applies to both onboarding and completed practice calls. Show zero to three suggestions; an empty section is omitted. A suggestion is a genuinely useful, natural spoken-English alternative to an already correct phrase, never a claim that the learner made an error. It must keep the intended meaning and tone. More complicated vocabulary is not a goal. A tiny stylistic edit, such as `I use the same words every single time` → `I keep using the same words`, is not worth showing. A clear, context-appropriate expression such as `I tend to fall back on the same words` may be useful when the transcript supports that meaning.

## Contract and flow

The existing onboarding or call assessment model request may return `vocabularySuggestions: [{original, alternative, explanation}]` in the same response. It must return `[]` when it cannot find a worthwhile alternative or when `vocabularyExamples` is nonempty. No extra provider call is made. The AI service accepts only short, nonidentical alternatives whose `original` appears exactly in one transcript, with a short explanation, and rejects repeats and suggestions overlapping any correction candidate, even one omitted from the report. The model is instructed to reject ASR artifacts, false starts, grammar repairs, changes of meaning or tone, rare words for their own sake, and trivial synonym swaps. These checks cannot prove semantic quality; live examples need human review.

The public Vocabulary skill adds optional `suggestions` alongside the existing `examples`. Existing review payloads without the field still deserialize. Telegram shows `original`, a bold alternative after `→`, and `💡` with the explanation. It never strikes through the original. Verified corrections retain the `What I noticed` layout and take priority if both fields are present. Grammar and Fluency are unchanged. Completed reviews are cached, so existing results are not regenerated.

## Verification

Python tests from `ai-service/` and `./gradlew :server:test :server:detekt` from `cmp/` passed locally. Tests cover grounded, empty, duplicate, and correction-overlap suggestions, plus Telegram rendering without strikethrough and correction priority. An isolated DEV model probe on an authorized onboarding sample exposed an awkward original and a meaning-changing verb suggestion; tightening the prompt led the same sample to return `[]`. The later synthetic comparison and final softer wording are documented in [the evaluation](../researches/2026-10-02-vocabulary-alternatives-model-eval.md). No code was deployed, so live Telegram appearance and model behavior across users remain to be checked.
