# Vocabulary assessment workflow

Status: revised locally on 2026-10-23; not deployed. This supersedes the
live-alternative workflow described in older integration notes. Product decision:
[the agreed plan](../plans/2026-10-21-live-naturalness-final-vocabulary-enrichment.md).

Review blocker: an earlier real-dialogue probe accepted `step` → `stop` while
calling `step` erroneous. The revised code requires source correctness,
meaning preservation, and learning value separately, but model judgment still
needs real-dialogue and held-out evaluation before merge or deployment.

## During a learner turn

The live notes request checks the entire recognized turn for clear syntax and
lexical errors. It returns `kind=grammar` and `vocabularyCategory=null`.
A natural but basic phrase does not produce a live correction. The parser
requires a copied context, one exact local edit, high confidence, and the
spoken-language/ASR safeguards. The bot displays at most one correction card
per turn. Other independent corrections are saved.

## At the end of onboarding and practice calls

Both flows collect saved errors, verify them in bounded batches, and select up
to five Grammar examples. The examples reuse Russian explanations saved during
the turn. Optional live alternatives from the previous local workflow are
ignored. Older saved `word` and `natural` corrections become Grammar candidates
and must pass the same strict verifier. A failed verification batch does not
remove successful batches. The review model sees verified examples when
writing diagnostic text and scores. Onboarding places the first score; calls
limit each score move to two points.

In parallel with correction verification and review composition, a separate
model request searches every numbered learner turn for optional Vocabulary
upgrades. It receives the learner transcript and brief assistant context.
Long dialogues are split into bounded chunks, with up to three concurrent
searches. Search results must quote an exact source in the given turn and
change only one word or a short local expression. A second model call verifies
the proposal in its full transcript. The final list has up to five
`word_upgrade` and five `expression_upgrade` suggestions. The output shape is
`{original, alternative, explanation, category, context, editedContext}`.
Model order is refined by the proposed benefit rank across chunks. An upgrade
is not an error and is not
sent to the score model as evidence of a mistake.

The model's category label is advisory. Grounding removes unchanged outer
words when the remaining source occurs once in context. It then classifies a
one-word-to-one-word edit as `word_upgrade`; any other short local edit is
`expression_upgrade`, including two words to one word. It accepts at most four
source words and five replacement words and does not reject an exact copied
context solely because the learner's turn exceeds 35 words. The separate
verifier decides separately whether the original is correct, the replacement
preserves meaning, and it offers learning value. Its accepted reason explains
when to use the replacement. Both categories retain their five-item limits.
The private debug trace keeps the proposed label and span when normalization
changes them.

The Telegram Vocabulary result is one card with score, diagnostic text, and
ranked optional lessons. Each lesson shows the learner's complete short phrase,
the edited phrase, and a usage explanation. The best lessons that fit the
message-length budget are shown, up to ten. If none survive, the card still
shows the score and diagnostic text, then navigates to Fluency.

## Failure and observability

The separate search has an 18-second deadline; upgrade verification has a
12-second deadline. One failed chunk or batch does not remove successful
results. A complete failure yields no upgrades; it does not remove diagnostic
examples or the score. Correction verification retains its own 10-second
batch deadline. `/metrics` exposes Vocabulary enrichment run outcomes and
duration, plus proposed, grounded, verified, and selected candidate counts by
flow. These metrics have no user, transcript, or category labels.

For future debugging, the upgrade verifier returns one accept/reject decision
and a short Russian reason for every candidate ID. The service keeps a private
trace of each proposal's source turn, local replacement, status, and reason.
The trace also names the failed grounding rule, overlap with an error,
verification failure or timeout, and proposals accepted by the verifier but
omitted by the final limit or deduplication. It is stored in Redis under
`vocabulary-review-debug:{onboarding|call}:{runId|callId}` and expires seven
days after the oldest included learner turn was received. The in-memory test
store enforces the same read deadline. No trace text is returned to the learner
or written to metrics and service logs. Existing probe results cannot be
backfilled with model reasons; only new reviews create these traces.

## Validation boundary

Local parser, review-flow, failure-isolation, and Telegram rendering tests
pass. The prompts and model gate still need a paired quality evaluation on
real dialogues and a held-out sample before deployment. In particular,
model-assigned benefit rank and learning value are judgments, not deterministic
facts, and transcripts alone cannot resolve uncertain audio.
