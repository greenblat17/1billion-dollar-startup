# Vocabulary correction workflow: rebuild draft

Status: superseded by the [live-naturalness and final-enrichment plan](2026-10-21-live-naturalness-final-vocabulary-enrichment.md); the two-suggestion rule remains implemented locally but is not deployed. A small [real-dialogue replay](../researches/2026-10-21-two-suggestion-vocabulary-real-replay.md) found one useful suggestion in 28 turns; broader validation is still needed.

## Current decision (2026-10-21)

The learner removed Vocabulary error cards. Live notes now seek only two
Vocabulary teaching points in onboarding and ordinary calls:

1. Replace one correct word with one natural synonym at a demonstrably higher
   vocabulary level. Keep meaning, strength, tone, and sentence grammar.
2. Replace an awkward two- or three-word combination with a more natural
   two- or three-word combination. A wrong collocation may qualify, but it is
   shown as a phrase suggestion, not a Vocabulary error. An already natural
   phrase does not qualify merely because a second idiom exists.

Both categories use `kind=alternative`, labelled `word_upgrade` or
`native_expression`. Grammar remains an error category. The live parser rejects
`word` and `natural` notes; final candidate collection ignores older saved
Vocabulary error notes. The final review returns no Vocabulary error examples
and must not lower the Vocabulary score because suggestions exist. It still
judges vocabulary range and natural use from the full transcript. The same
search and verification runs for onboarding and ordinary calls. Ordinary calls
retain the existing two-point score movement limit.

The final verifier checks each suggestion against the full transcript before
it may appear in the final assessment. It must reject a natural source phrase
such as `advantages and disadvantages` -> `pros and cons`. Deterministic
span checks alone cannot decide that semantic question. Local tests verify
routing and shape; real-dialogue quality needs a fresh replay before deployment.
This change is local and has not been deployed.

An isolated seven-turn synthetic probe through the configured notes provider
returned the intended one-word upgrade (`big` -> `substantial`) and short phrase
suggestions (`made a photo` -> `took a photo`; `did a decision` -> `made a
decision`). It returned no note for `advantages and disadvantages` or
`scared` -> `frightened`, and a Grammar note for `am agree`. The model also
proposed an overlong `borrowed my friend my bike` phrase; the local parser
rejected it. The provider inconsistently filled the phrase flags, so the
parser now relies on shape, grounding, meaning-preservation flag, and the
independent final verifier, rather than those self-reported correctness flags.
After clarifying the verifier prompt, one isolated four-candidate check accepted
both good phrases and rejected the already-natural `advantages and
disadvantages` rewrite and the same-level `scared` -> `frightened` pair.
These small synthetic probes do not establish quality on real speech.

## Narrower product direction (2026-10-20)

The learner has narrowed the desired Vocabulary points to two concrete shapes:

1. **Single-word upgrade:** replace one word with one natural synonym at a demonstrably higher vocabulary level. The meaning, strength, tone, and edited sentence must still fit. A different word at the same level is not a teaching point. For example, `scared` → `frightened` is not a demonstrated upgrade: Cambridge marks both B1.
2. **Short phrase repair:** correct a clearly wrong or unnatural two- or three-word combination with a more natural short combination. Four words is an outer limit, not a target. Check the full sentence and preserve the intended meaning. An already natural phrase does not qualify just because another idiom exists. Classify this as a Vocabulary error and use the stricter error verifier.
3. **Clear single-word error:** correct a genuinely wrong word even when its replacement is not higher level. Mark only the wrong word as the source span, leaving the rest of the sentence as context.

The user explicitly confirmed the third rule. Newly generated notes use
`vocabulary_error` or `word_upgrade`; the earlier `native_expression`
alternative category is no longer generated. The parser limits `word_upgrade`
to one word on each side and `vocabulary_error` to one to four words on each
side. Grammar spans are unaffected. The final verifier ignores saved
`native_expression` alternatives and separately checks word upgrades.
The earlier benchmark labels have only been partially reconciled with this
narrower direction; rerun evaluation before using those counts to judge it.

An isolated four-turn synthetic probe using the configured notes model returned
`borrowed` → `lent`, `did a decision` → `made a decision`, and
`big` → `substantial` in a cost-increase context; it returned no note for
`advantages and disadvantages`. All three proposed notes survived the local
parser. A separate final-verifier probe exposed a false approval of
`advantages and disadvantages` → `pros and cons` under the old phrase
alternative route. The new parser and verifier therefore exclude that route.
These synthetic cases do not establish quality on real conversations.

## Earlier broader contract (historical)

For each learner turn, find three distinct teaching points:

1. **Vocabulary error:** the learner used a wrong word, meaning, or collocation. The original phrase is incorrect in this context.
2. **Established expression:** the original phrase is correct, but a reusable idiom, phrasal verb, or conventional expression gives the learner a new way to express the same idea.
3. **Word upgrade:** the original phrase is correct, but a context-appropriate, more precise or higher-level word expands the learner's vocabulary without changing meaning.

A same-level paraphrase is not a teaching point. Grammar errors belong to Grammar even when their repair changes words. Alternative suggestions do not lower the Vocabulary score. The same detection and selection process serves onboarding and ordinary calls; only scoring differs, because a later call starts from a baseline and limits its score movement.

## Proposed path

```text
turn transcript + recent dialogue context
        ├─ Grammar search
        └─ dedicated Vocabulary search: all three categories
                  ↓
        exact source-span check + full-sentence edited form
                  ↓
        independent meaning/quality check
                  ↓
        save every accepted teaching point with source turn and reason
                  ↓
        rank one point for the live card
                  ↓
        final assessment ranks saved points for each skill
```

The Vocabulary search has a short, task-specific output: exact source span, replacement, one of the three categories, and a brief reason. It receives recent dialogue for meaning but may edit only the current turn. It does not write a quoted context sentence or a collection of self-reported confidence booleans. Code derives the surrounding sentence from the transcript and checks that the source span occurs verbatim. When grounding fails, record the failure and allow one targeted regeneration; never silently invent a different original sentence.

The independent quality check sees the **entire original and edited sentence**, the recent dialogue, the claimed category, and the claimed learner benefit. It rejects a meaning shift, invalid English after replacement, a grammar-only repair labelled Vocabulary, and a same-level paraphrase. It must make an explicit decision for each candidate and return a reason code. Its acceptance rate and false approvals are measured separately for the three categories. The check is a gate before a candidate becomes eligible for a live card or the final assessment. The latency of this gate must be measured before deciding whether it can finish in the live-turn budget.

The live card displays at most one teaching point per turn. That presentation limit does not discard other accepted points. The final assessment uses the saved pool; it does not ask a model to rediscover or rewrite corrections. It ranks up to five Grammar errors, five Vocabulary errors, and three Vocabulary alternatives per conversation under the current display rules. These limits apply only at presentation. The original turn-time explanation travels with the selected point. Assessment prose may cite only verified examples; when none exist, it cannot claim a specific error.

## Evaluation before implementation

Start with the 79 turns from the six complete conversations in the latest replay, then expand if a category lacks enough examples. Review audio for disputed ASR spans. Label candidate pairs as useful, wrong, or ambiguous; also mark useful Vocabulary opportunities the system missed. Include examples of all three categories and clean turns. Keep identities and raw transcripts out of the repository.

Run the old and new workflows on the same set. Record, by category: useful suggestions found, useful suggestions missed, bad suggestions accepted, loss at grounding/quality/display, timeout rate, and latency. Inspect every displayed pair, not only counts. A new workflow passes only if it finds real upgrades that the present workflow misses **without** accepting meaning-changing or ungrammatical replacements. Do not deploy on the basis of synthetic prompt examples alone.

## First implementation step

Freeze a reviewable candidate schema and create the labelled evaluation set. Then prototype the dedicated Vocabulary search and quality check as an isolated replay. Only after the replay should the live call and onboarding paths be switched to the shared new workflow.
