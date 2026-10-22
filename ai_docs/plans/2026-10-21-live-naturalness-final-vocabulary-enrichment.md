# Live naturalness and final Vocabulary enrichment

Status: implemented locally on 2026-10-21; not deployed. Local tests pass.
The [initial three-dialogue probe](../researches/2026-10-21-final-vocabulary-enrichment-real-probe.md)
found verifier false approvals. The larger annotated and held-out evaluation
remains open; do not deploy until quality is established.
Implementation contract: [Vocabulary assessment workflow](../integrations/2026-10-21-vocabulary-assessment-workflow.md). This decision
supersedes the live word-upgrade and live `native_expression` search in
[the prior local draft](2026-10-20-vocabulary-rebuild-draft.md).

## Product contract

The same workflow serves onboarding and ordinary conversations. Onboarding
establishes the first score; later call reviews still limit each score move
to plus or minus two points.

During each spoken turn, detect two correction classes:

1. Grammar: a clear grammatical error under the existing spoken-language
   safeguards.
2. Vocabulary naturalness: a clearly wrong lexical choice, collocation, or
   awkward expression in this context. A fluent speaker would not normally
   use the original for the intended meaning. A valid but less advanced phrase
   is **not** a live error. Supply a small local edit and a Russian explanation.

One live card per turn remains a presentation rule. Save all reliable
independent corrections for final review, with Grammar priority for the card.
The final verifier selects up to five Grammar examples and five Vocabulary
naturalness error examples, reusing the explanations written during the turn.

At the end of either flow, start a separate whole-dialogue model search for
**Vocabulary enrichment**. It looks for two kinds of optional alternatives in
otherwise acceptable speech:

1. Word upgrade: one correct word replaced by a context-appropriate word at
   a clearly higher vocabulary level.
2. Expression upgrade: a short local expression or construction replaced by a
   useful, more advanced or established expression. The rest of the sentence
   stays intact. Do not force complexity or change meaning, intensity, time,
   register, or grammatical correctness.

The final Vocabulary slide shows at most five verified word upgrades and five
verified expression upgrades, ranked by learner benefit. These are teaching
options, not evidence of mistakes or a reason to lower the Vocabulary score.
Vocabulary scoring remains based on the learner's demonstrated range,
precision, and natural usage in the transcript. Final alternatives are found
independently of whether live corrections exist.

## Data flow

```text
Each spoken turn -> Grammar + clear Vocabulary naturalness corrections
                 -> exact transcript grounding -> save all -> one live card

Finished dialogue -> verify saved Grammar/naturalness corrections
                  -> search whole dialogue for word/expression upgrades
                  -> ground and independently verify upgrades
                  -> rank up to five of each type
                  -> compose assessment text and scores
                  -> show errors and optional upgrades in Vocabulary slide
```

The search receives ordered, numbered learner turns and only enough assistant
context to disambiguate meaning. Every proposed alternative identifies its
turn, quotes an exact source span, supplies a local replacement, declares one
of the two upgrade categories, and gives one short Russian explanation. The
model may return no suggestions. Discovery should not be forced to fill the
presentation quotas. A source span that cannot be located exactly in the
specified turn is rejected. The same source cannot appear as both a live
naturalness error and an optional upgrade.

The category returned by discovery is not a rejection rule. Derive it from
the minimal local edit after removing identical outer words. Allow a short
expression to become one word, or one word to become a short expression.
Keep exact grounding and the semantic verifier as the quality gate.

For long conversations, cover every turn through bounded chunks with stable
turn numbers, then rank globally. Preserve useful completed chunks if another
chunk fails. The enrichment search runs alongside existing final work where
dependencies allow. A timeout or invalid enrichment response removes only its
suggestions; Grammar examples, naturalness examples, scores, and the review
must still complete. Record request duration, failures, candidate counts, and
survival at grounding, verification, and final selection without user IDs in
metric labels.

## Implementation steps

1. **Live notes:** remove live `word_upgrade` and optional
   `native_expression` passes. Add one narrow Vocabulary-naturalness pass next
   to Grammar. Keep the existing speech-artifact, ASR, exact-span, overlap,
   and local-edit checks. Map the live naturalness correction to the existing
   Telegram correction presentation, not the optional-alternative style.
2. **Shared final evidence service:** extract the duplicated candidate
   collection/verification/selection orchestration from onboarding and calls.
   Include saved Grammar and new naturalness candidates; keep saved explanations.
   Define how older saved `word`/`natural` records are treated, while ignoring
   legacy optional alternatives from the superseded live workflow.
3. **Dedicated enrichment request:** implement the whole-dialogue prompt and
   typed response for word and expression upgrades. Ground every source to a
   numbered turn, enforce a short local edit, preserve meaning in the edited
   sentence, deduplicate, and reject overlap with verified live corrections.
4. **Quality gate:** use a separate strict verification pass for enrichment.
   Check the original and edited full sentence, claimed level/expressive gain,
   regional English, ASR uncertainty, and meaning. Reject same-level
   paraphrases, downgrades, inflated intensity, and grammar repairs disguised
   as upgrades. Keep phrase explanations from discovery only after verification.
5. **Final assembly:** run this path in both onboarding and ordinary calls.
   Keep the existing initial-score versus plus/minus-two scoring difference.
   Pass verified errors as evidence for diagnostic text; do not let optional
   upgrades become evidence of mistakes. Return two grouped suggestion lists
   or an equivalent typed representation in the review contract.
6. **Telegram display and limits:** display up to five word and five
   expression upgrades, with short original/replacement pairs and their
   explanations. Check message-size limits and split the slide safely if ten
   alternatives plus five error examples exceed one Telegram message.
7. **Validation:** test exact span/category parsing, one-card live behavior,
   independent alternatives despite errors, failure isolation, scoring
   invariants, both review flows, and Telegram rendering. Run the old and new
   paths on an annotated real-dialogue sample and a held-out sample. Review
   every displayed pair and disputed ASR audio. Report useful finds, misses,
   false approvals, latency, and failure rate by category before deployment.

## Acceptance criteria

- A natural but simple phrase gets no live correction and remains eligible
  for final enrichment.
- A clearly awkward phrase can be corrected live and shown as a verified
  Vocabulary example in the final review.
- Final enrichment searches all learner turns regardless of live corrections.
- Only clearly useful, meaning-preserving upgrades are displayed, with up to
  five words and five expressions.
- Search/verification failure never removes successful Grammar or naturalness
  examples and never prevents the assessment from completing.
- Onboarding and ordinary calls share discovery, verification, and selection;
  only the existing score baseline rule differs.
