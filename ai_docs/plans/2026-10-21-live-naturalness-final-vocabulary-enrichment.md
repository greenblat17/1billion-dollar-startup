# Live naturalness and final Vocabulary enrichment

Status: revised locally on 2026-10-23; not deployed. The owner clarified the
product categories and the single-card Vocabulary lesson. Quality evaluation
remains open.
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

During each spoken turn, detect clear errors under the existing spoken-language
safeguards. All verified errors, including wrong words, collocations, and
awkward expressions, belong to Grammar. A valid but simple word is not an error.

One live card per turn remains a presentation rule. Save all reliable
independent corrections for final review, with Grammar priority for the card.
The final verifier selects up to five Grammar examples, reusing the explanations
written during the turn. Legacy saved `word` and `natural` errors are checked
as Grammar; legacy optional alternatives are ignored.

At the end of either flow, start a separate whole-dialogue model search for
**Vocabulary enrichment**. It looks for two kinds of optional alternatives in
otherwise acceptable speech:

1. Word upgrade: one correct word replaced by a useful, context-appropriate
   word. A moderate step in difficulty is preferred, but a formal CEFR jump is
   not required.
2. Expression upgrade: a short correct expression replaced by a useful,
   reusable expression. The rest of the sentence stays intact. Do not force
   complexity or change meaning, intensity, time,
   register, or grammatical correctness.

The single Vocabulary slide shows the score, diagnostic text, and up to five
verified word upgrades plus five expression upgrades, ranked together by
learner benefit. Each lesson shows the learner's short complete phrase, its
local replacement, and a Russian explanation of when to use it. If the message
would exceed Telegram's limit, show the best lessons that fit. If none survive,
show only the score and diagnostic text. Upgrades are teaching options, not
mistakes or score evidence. Vocabulary scoring uses the learner's own lexical
range, complexity, precision, and natural usage. Repeated basic choices in
substantial speech can support a lower Vocabulary score than an advanced
baseline; one simple word or a narrow sample cannot. Onboarding sets the first
score, and later calls keep the existing ±2 limit.

## Data flow

```text
Each spoken turn -> all clear errors as Grammar
                 -> exact transcript grounding -> save all -> one live card

Finished dialogue -> verify saved Grammar errors, including legacy lexical errors
                  -> search whole dialogue for word/expression upgrades
                  -> ground and independently verify upgrades
                  -> rank up to five of each type
                  -> compose assessment text and scores from demonstrated speech
                  -> show score and optional lessons in one Vocabulary slide
```

The search receives ordered, numbered learner turns and only enough assistant
context to disambiguate meaning. Every proposed alternative identifies its
turn, quotes an exact source span, supplies a local replacement, declares one
of the two upgrade categories, and gives one short Russian explanation. The
model may return no suggestions. Discovery should not be forced to fill the
presentation quotas. A source span that cannot be located exactly in the
specified turn is rejected. The same source cannot appear as both a live
Grammar error and an optional upgrade.

The category returned by discovery is not a rejection rule. Derive it from
the minimal local edit after removing identical outer words. Allow a short
expression to become one word, or one word to become a short expression.
Keep exact grounding and the semantic verifier as the quality gate.

For long conversations, cover every turn through bounded chunks with stable
turn numbers, then rank globally. Preserve useful completed chunks if another
chunk fails. The enrichment search runs alongside existing final work where
dependencies allow. A timeout or invalid enrichment response removes only its
suggestions; Grammar examples, scores, and the review must still complete.
Record request duration, failures, candidate counts, and
survival at grounding, verification, and final selection without user IDs in
metric labels.

## Implementation steps

1. **Live notes:** collect clear syntax and lexical errors as Grammar. Keep
   speech-artifact, ASR, exact-span, overlap, and local-edit checks.
2. **Shared final evidence service:** extract the duplicated candidate
   collection/verification/selection orchestration from onboarding and calls.
   Include saved Grammar and legacy lexical candidates as Grammar; keep saved
   explanations. Map older saved `word`/`natural` records to Grammar and ignore
   legacy optional alternatives from the superseded live workflow.
3. **Dedicated enrichment request:** implement the whole-dialogue prompt and
   typed response for word and expression upgrades. Ground every source to a
   numbered turn, enforce a short local edit, preserve meaning in the edited
   sentence, deduplicate, and reject overlap with verified live corrections.
4. **Quality gate:** use a separate strict verification pass for enrichment.
   Check source correctness, full edited sentence, learning value, regional
   English, ASR uncertainty, and meaning. Reject pointless paraphrases,
   inflated intensity, and error repairs disguised as upgrades. Reuse the
   verifier's explanation of when the new phrase is useful.
5. **Final assembly:** run this path in both onboarding and ordinary calls.
   Keep the existing initial-score versus plus/minus-two scoring difference.
   Pass verified errors as evidence for diagnostic text; do not let optional
   upgrades become evidence of mistakes. Score Vocabulary from demonstrated
   lexical range and complexity, not from offered alternatives.
6. **Telegram display and limits:** display score, diagnosis, and up to five
   word and five expression upgrades on one card. Show complete short source
   and edited phrases plus usage explanations. Keep the best that fit.
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
  Grammar example in the final review.
- Final enrichment searches all learner turns regardless of live corrections.
- Only clearly useful, meaning-preserving upgrades are displayed, with up to
  five words and five expressions.
- Search/verification failure never removes successful Grammar examples and
  never prevents the assessment from completing.
- Onboarding and ordinary calls share discovery, verification, and selection;
  only the existing score baseline rule differs.
