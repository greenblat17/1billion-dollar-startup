# Corrections for spontaneous speech

Applies to **all clip conversations**, including ordinary Telegram dialogue, onboarding voice turns, and the final onboarding assessment. The shared policy is `ai-service/app/correction_policy.py`. The same `OpenAiChatModel.complete_notes` produces live error corrections and optional Vocabulary alternatives for ordinary and onboarding turns; final assessment separately re-verifies saved candidate IDs.

## Selection policy

Prefer precision over recall: a missed error is better than falsely correcting normal spoken English. Four checks must pass: definitely wrong, plausibly the learner's own error rather than ASR/spoken-language artifacts, useful to correct, and understandable without reading the surrounding conversation. ASR punctuation, false starts, repetitions, self-repairs, abandoned phrases and natural discourse markers are not grammar errors. Standard regional variants and fine-but-fancier alternatives are accepted. `natural` is reserved for clearly unidiomatic phrasing. `alternative` is a separate optional teaching point for an already correct phrase.

The display unit is a short contiguous clause or sentence copied from the full transcript. It includes the words needed to understand the edit, even across a pause; Whisper punctuation is not a trusted boundary either. The model returns exact `context`, `error` inside it, and `replacement`. Code requires a whole-word context match and a unique error inside that context, limits context to 30 words, and constructs `wrong=context` and `better=context` with only the local replacement. Identical repeated contexts receive the same edit; long or ambiguous candidates are omitted. Telegram strikes only the changed words and places the bold replacement beside them inside the context.

## Internal model contract and code gate

In the same completion, the notes model first returns `speech_artifacts` (exact spans of repetitions, false starts, self-repairs or discourse markers), then candidates with `context`, `error`, `replacement`, `kind`, plus:

- `reason`: nonempty explanation of the actual error and learning value;
- `confidence`: only `high` passes;
- `definitely_wrong`, `worth_showing`, `understandable_alone`: must be boolean `true`;
- `is_spoken_language_artifact`, `is_asr_uncertain`: must be boolean `false`.

For `alternative`, `definitely_wrong` must be `false`, while `already_correct` and `meaning_preserved` must be `true`. Its `error` field names the original local phrase for grounding; the saved `wrong` field is that correct phrase, not a claim of error. The model scans for errors and alternatives independently. Grammar, wrong word choice, and clearly unnatural wording keep the strict error gate. Alternatives need a useful expression, matching meaning, and a displayable Russian explanation.

Missing or incorrectly typed fields, unknown kinds, unchanged pairs, legacy bare strings, nonmatching original text, ambiguous edits, edits overlapping marked speech artifacts, duplicate or overlapping candidates are discarded. Whole-word matching includes straight and curly apostrophes so `can` cannot match inside `can't` or `can’t`. Malformed notes JSON becomes an empty correction list; provider/network errors retain existing handling. Priority is grammar > word > natural > alternative for the one live teaching card shown in onboarding, practice calls, and ordinary dialogue. The alternative card does not strike through the original. All accepted nonoverlapping candidates from the turn are retained for final assessment when that turn belongs to onboarding or a practice call; zero is valid.

The code gate also rejects an edit that copies a neighboring two-word phrase from outside its declared edit span, or rewrites the accepted British collocation `take a decision` as `make a decision`. It also rejects a proposed edit that removes a correct modal or main verb from a `modal + to + verb` construction. A live synthetic evaluation once proposed `he can to swim` → `he swim`; that card must be omitted even though the original clause contains an error. The safe correction is `he can swim`.

These flags are model judgments, not calibrated probabilities or audio verification. Code validates their shape and decision, but cannot prove their semantic truth. Decision flags stay internal. The model's `reason` must still be nonempty for a candidate to pass; a separate display check allows it as an optional Russian `explanation` only when it is one line, one sentence, at most 160 characters, and not a generic phrase. A poor explanation is omitted while the accepted correction remains. Public `corrections: [{wrong, better, kind, explanation?}]` carries the optional field; legacy output `notes: ["wrong|||better"]` remains unchanged. The generation budget for notes is 1200 tokens; reply budget is unchanged. `NOTES_MODEL` and `LLM_MODEL` both default to `openai/gpt-5.6-luna`.

Telegram renders contiguous Latin words in a Russian live-correction explanation as one inline monospace fragment (for example, `an AI` and `a person`). This changes only presentation: the explanation text and the existing correction decision stay unchanged. Cyrillic text and punctuation remain regular text.

Live correction generation starts in parallel with the reply and has a 10-second deadline from request start (fixed in `pipeline.py`). On expiry, the request is cancelled and a ready spoken reply is delivered without correction cards. A malformed result or generation failure likewise omits cards without failing the voice reply. The model can still misclassify a spoken artifact while marking its own candidate as high confidence; grounding and decision-field checks do not independently establish linguistic correctness. Prompt examples explicitly distinguish repeated short words from a complete future conditional. The final assessment retains its separate verification. The deadline choice and evidence are in [the 2026-10-02 experiment](../researches/2026-10-02-correction-timeout-experiment.md).

The live correction request sends `reasoning.effort: none` to Luna. This is set in code only for `complete_notes_result`, the request bounded by the 10-second deadline; spoken replies and final assessment/review calls keep their existing reasoning settings. In a paired check of 100 synthetic transcripts, default and none each showed corrections in 46 of 47 positive cases and none in all 53 negative cases, but missed different positive cases. In 10 long stored DEV transcripts, none completed within 8 seconds in all 10; manual review found an unjustified rewrite of the already valid phrase `like a native speaker`. This is a speed experiment on DEV, not proof of equal precision on real audio.

In onboarding, the same bounded correction request runs alongside question assessment. Once the question is ready, TTS can run while corrections finish. The final assessment waits for corrections before verifying and showing examples.

SDK automatic retries are disabled for the AI service's OpenAI-compatible STT, LLM, TTS, and review clients. Live corrections use one explicit controller for at most two provider attempts within the single 10-second deadline, including backoff and parsing. It retries 429/5xx, network/connection timeout, no `choices`, empty text, or malformed JSON only if time remains. A valid empty result or safety-filtered candidate is never retried. Other STT, TTS, and review operations retain their own single explicit retry. Ordinary and onboarding voice turns use the same bounded correction path; the final assessment review is separate.

One final outcome is counted per live correction request by Moscow day in `metrics:corrections:YYYY-MM-DD` and returned under `corrections` by `/internal/metrics`. Each outcome has `count`, summed `elapsedMs`, and `secondAttempts`: `shown`, `empty`, `filtered`, `deadline`, `rate_limit`, `provider_timeout`, `provider_5xx`, `provider_4xx`, `network`, `no_choices`, `empty_text`, `invalid_json`, `invalid_schema`, `token_limit`, or `other_error`. `empty` means a valid empty notes list; `filtered` means model candidates were rejected by the safety gate. The existing `/admin/metrics` dashboard shows daily totals, failure share, retry count, and a per-outcome latency table. Counters start at deployment and are not backfilled. Do not log transcript text or provider response bodies in these metrics.

The final assessment uses a stricter second review of original transcripts. Error and alternative candidates use separate verifier prompts and batches because an alternative must start from correct phrasing. Earlier acceptance is not evidence of correctness. Legacy saved pairs without metadata also receive full review. An unclear fragment is rejected rather than expanded by this second call, which can only select existing candidate IDs. The check divides candidates into groups of five and runs up to three groups concurrently under one 10-second deadline. Verified groups keep their examples when another group fails or times out. A complete verifier failure still omits all examples; unverified pairs are never shown. Onboarding and practice-call reviews each select at most five error examples for Grammar, five error examples for Vocabulary, and three optional Vocabulary alternatives after verification. The selected examples reuse the Russian explanations saved during the conversation. The final review model does not write explanations for correction pairs. A legacy pair without a saved explanation remains visible with no explanation line. The assessment card keeps sentence context and highlights only the local edit, as dialogue cards do. When available, each explanation starts on a separate `💡` line, matching the live correction cards. Completed cached reports are not regenerated.

The local 2026-10-20 change routes this second verification through `CORRECTION_VERIFICATION_MODEL` (default `openai/gpt-4o-mini`) instead of the ordinary reply model. The [PROD audit](../researches/2026-10-20-production-correction-audit.md) found three long calls where the old model exceeded the verification deadline and all examples were omitted; isolated probes of the saved batches on `gpt-4o-mini` completed within two seconds. This is not yet deployed or a validation of every accepted correction's linguistic quality.

### Candidate collection versus live display (2026-10-20)

The former three-candidate limit was removed from the notes prompt and parser. It applied before the final review's five-per-skill selection and could exclude Vocabulary when Grammar filled the per-turn quota. The single live correction is a Telegram display choice, not a cap on saved candidates. Ordinary dialogue without a practice call is the legacy path for a user without a numeric onboarding baseline; it has no sealed call and no post-call assessment. A free `Start call` or direct voice from an eligible user is a practice call and gets the same final assessment as a situation call.

This change does not guarantee complete error recall. The notes request still has a 1200-token output limit and a 10-second deadline; an incomplete or timed-out response yields no candidates for that turn. The final verifier only accepts or rejects saved pairs and has its own 10-second deadline. Existing correction metrics count request outcomes, not candidate counts by Grammar/Vocabulary or later verifier rejection. A real-transcript audit is needed before attributing sparse Vocabulary examples to a particular stage. Old cached reviews are not regenerated.

Further code-audit findings for Vocabulary UX:

- The notes outcome `shown` means at least one candidate survived the AI-service filter. It does not mean a Telegram correction card was delivered, and it does not identify the candidate's kind. The live card still selects Grammar before Vocabulary.
- If the final verifier rejects all candidates, times out, or fails, the review model receives no correction examples. It cannot recover a clear mistake that the live notes request missed.
- Optional Vocabulary alternatives can appear alongside selected verified Vocabulary errors. Both are discovered during live turns and checked at the end. Alternatives are grounded in correct phrases and are not evidence of a mistake or a lower score. Missing saved explanations no longer remove verified examples.
- Local synthetic evaluations and unit tests do not establish which of these losses occurs in real conversations. The restricted interaction-history view retains transcript content for seven days; a dated sample is needed for stage-by-stage review.

## Validation

Unit tests verify strict decisions, exact context and local edits, contraction boundaries, overlaps, retention of more than three nonoverlapping candidates, malformed/legacy model output, unchanged public fields, and the actual `complete_notes` path. They do not prove model judgment quality.

`ai-service/evals/spoken_corrections.json` contains 100 synthetic cases, including pauses that cut a conditional, a tense adverb, or `it'll` away from the broken words and a long no-pause monologue. Positive cases now include `expected_context` and `expected_edits` (two for the two-error case); three pause cases that the old contract marked `omit` are relabeled as real errors. Exact differences are still inspected manually because alternative context widths and corrections can be valid. Opt-in live check from `ai-service/`:

```sh
PYTHONPATH=. .venv/bin/python evals/run_spoken_corrections.py --live
```

It uses existing `OPENAI_API_KEY`, `OPENAI_BASE_URL`, and `NOTES_MODEL` settings, sends synthetic cases through the live generation path, and prints surviving pairs. It reports false-correction cases and missed positives separately, plus edits and contexts for human review. Inspect usefulness and meaning preservation manually. The pause-bounded baseline had six false cards and two missed errors among these 100 cases; its labels included the three real errors described above. A real-audio sample is needed before deployment because synthetic transcripts cannot reveal Whisper mistakes.

On 2026-09-30, two complete Luna-only passes on the 100 synthetic cases each had zero false-correction cases and zero missed-positive cases. The final pass had two edit mismatches that are valid alternatives (`an advice` → `some advice`; `suggested me to` → `suggested that I`) and one context mismatch that retained a spoken `um` while showing the full error. A first targeted repeat exposed a false `the the` card in three of four calls and two missed complete conditionals; after the prompt clarification, four repeats of each risky negative, two of each risky positive, and 27 calls on nine unseen variants matched their labels. These are small synthetic samples, not a measured production error rate. The separate live reviewer was removed; ordinary turns now use one Luna call for the reply and one Luna call for corrections, in parallel. Final onboarding assessment verification remains separate.

On 2026-10-02, a further 100-case live pass through the new two-attempt, 8-second path returned zero labeled false-correction cases and zero missed-positive cases, but manual inspection found the unsafe `can to swim` → `swim` edit described above. Aggregate labels alone therefore do not establish semantic safety. The modal-preservation gate was added after this run; it has a focused regression test, while the full live pass has not been repeated with the new gate.

## Unified Vocabulary workflow (local revision, 2026-10-20)

The final review model no longer generates `vocabularySuggestions`. Live notes discover both errors and optional alternatives in each turn. The final verifier accepts or rejects the saved IDs with kind-specific prompts; the assessment selects verified candidates and reuses their explanations. The [Vocabulary decision](../plans/2026-10-02-telegram-vocabulary-alternatives.md) records the local provider probe and the remaining quality risk. This revision has not been pushed or deployed.

New live notes also declare an internal `vocabularyCategory`: `vocabulary_error`, `native_expression`, or `word_upgrade`; Grammar uses `null`. The parser requires a label consistent with `kind`, stores it with the correction, and forwards it to final verification. Old saved candidates without this field are still reviewed. The user-visible correction format is unchanged. The category makes model intent auditable but does not prove the suggestion is useful.

## Narrow Vocabulary spans (local revision, 2026-10-21)

The shared live notes prompt and final verifier now use the [narrower Vocabulary rule](../plans/2026-10-20-vocabulary-rebuild-draft.md): correct a clearly wrong single word or a short, clearly unnatural combination; optionally upgrade one correct word to a natural synonym at a demonstrably higher level. The parser limits new Vocabulary error spans to four words and word upgrades to one word on each side. It rejects new `native_expression` alternatives. The final verifier also ignores saved `native_expression` alternatives, because an isolated check falsely accepted a natural phrase rewrite. Phrase repairs must pass the stricter Vocabulary error verifier. This revision is local and needs real-dialogue evaluation before deployment.

## Current Vocabulary contract (local revision, 2026-10-21)

The earlier Vocabulary sections above describe historical local revisions. The
[current decision](../plans/2026-10-20-vocabulary-rebuild-draft.md) removes
Vocabulary error cards. Live notes produce only `word_upgrade` (one word to one
higher-level synonym) and `native_expression` (awkward two- or three-word
combination to a more natural two- or three-word combination). Both use
`kind=alternative`; only Grammar uses error cards. The same parser and final
verifier serve onboarding and ordinary calls. The final assessment ignores
legacy `word` and `natural` notes and supplies no Vocabulary error examples.
Vocabulary suggestions do not lower the Vocabulary score. This revision is
local, not deployed; model quality still needs a new real-dialogue replay.

## Superseding local Vocabulary workflow (2026-10-21)

The older live alternatives and final suggestions described above are superseded
by the [current local workflow](2026-10-21-vocabulary-assessment-workflow.md).
Live turns now seek Grammar and clear Vocabulary naturalness errors. Final
assessment separately searches the whole dialogue for optional word and short
expression upgrades. This change is local and not deployed.
