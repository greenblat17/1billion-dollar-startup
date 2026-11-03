# Full real-conversation replay after Vocabulary categories

Date: 2026-10-20. Status: isolated read-only replay; local code and prompts were not deployed. No Telegram message, Redis record, or assessment was changed.

## Method

Three completed ordinary calls and three completed onboardings were selected deterministically from retained reviewed records. The sample contains all 79 turns in those conversations: 56 call turns and 23 onboarding turns. A text filter excluded records with contact details and explicit minor-age phrases. This selected sample is not representative, and original audio was not checked.

Each turn went through the current local notes prompt on the configured production route (`openai/gpt-5.6-luna`, 1200 output tokens). The current local parser grounded and classified its response. Saved candidates then went through the current final verification prompts on `openai/gpt-4o-mini`, in separate error and optional-alternative batches of five with the ten-second per-conversation deadline. The current selection functions assembled up to five Grammar errors, five Vocabulary errors, and three optional Vocabulary alternatives per conversation. The current review prompts then composed final assessment text on `openai/gpt-4o-mini`; all six responses passed structural validation. For ordinary calls, the replay used the current stored assessment as a baseline, which may differ from the baseline at the time of the original call. Therefore score moves from this replay are not historical scores.

The provider calls were isolated. Turn requests were run with up to five concurrent calls rather than the original conversation timing. The replay made one notes request per turn and did not reproduce the live retry on transient failures. Every request returned successfully, so the retry difference did not affect this sample. Raw transcripts and model responses were held only in private temporary files and removed after analysis.

## Funnel

| Stage | Grammar | Vocabulary errors | Native expressions | Word upgrades |
| --- | ---: | ---: | ---: | ---: |
| Raw model notes | 47 | 39 | 3 | 0 |
| Local parser accepted | 36 | 29 | 0 | 0 |
| Final verifier accepted | 31 | 14 | 0 | 0 |
| Displayed after per-conversation limits | 26 | 10 | 0 | 0 |

The raw model also returned one unsupported `speech_artifact` note kind. All 79 turn requests returned a response. Final verification had no failed or timed-out batches. The three raw alternatives were rejected before final verification: one had only medium confidence, and two supplied a context that did not occur verbatim in the transcript. All three claimed `native_expression`; none claimed `word_upgrade`. One of the mismatched contexts also proposed a same-level phrase change. The exact-context filter correctly prevented the two ungrounded cards, but the end result was **zero optional Vocabulary suggestions** in six finished assessments.

Displayed Vocabulary errors by conversation: 3, 5, 2, 0, 0, 0. Four additional accepted errors in the second call did not fit the five-example limit. The final review text did not add or alter the displayed pairs.

## Manual review of displayed Vocabulary pairs

The three strong word/collocation repairs were `imaging` → `imagination`, `by my own` → `on my own`, and `do preserves` → `make preserves`. `anything of art` → `anything related to art` also repairs an unnatural phrase. `do something art` → `do something artistic` is a word-form repair, with a plausible Vocabulary/Grammar boundary. `different taste for movies` → `different tastes in movies` mixes a collocation repair with plural grammar.

Several verifier approvals are unreliable:

- `seeding garlic` → `growing garlic` can change planting into the whole growing process. The transcript alone does not justify that change.
- `I thought I stay garlic as it is` → `I thought I leave garlic as it is` leaves a tense problem in the edited sentence. The proposed repair is not clean English.
- `such feeling` → `that feeling` is mainly a determiner choice, not a clear lexical teaching point.
- `go off to choir` → `go to choir` may remove a valid nuance of setting off for choir. This is not a definite Vocabulary error from the transcript alone.

The final verifier accepted these despite their `vocabulary_error` category. The category field confirms what the first model claimed; it does not independently establish that the edit is useful or correct. These are transcript-only judgments and may change after audio review.

One onboarding review contained no verified Vocabulary examples, yet its generated Vocabulary text cited an article error in “very good level in English” as a Vocabulary limitation. Structural validation accepted that prose. The review composer therefore can make an unsupported skill claim even when the examples list is empty.

## Conclusion

This replay reproduces the user's missing-alternatives experience. The primary loss is in turn-time discovery: the notes model generated only three optional candidates in 79 turns and no word upgrades. Grounding rejected all three. The final verifier also let several weak or incorrect Vocabulary error repairs through. Explicit categories improved traceability but did not fix recall or precision. Assessment prose needs a stronger evidence check when a skill has no verified examples.
