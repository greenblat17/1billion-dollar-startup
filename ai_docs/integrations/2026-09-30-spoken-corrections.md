# Corrections for spontaneous speech

Applies to **all clip conversations**, including ordinary Telegram dialogue, onboarding voice turns, and the final onboarding assessment. The shared policy is `ai-service/app/correction_policy.py`. The same `OpenAiChatModel.complete_notes` produces live corrections for ordinary and onboarding turns; final assessment separately re-verifies candidate IDs.

## Selection policy

Prefer precision over recall: a missed error is better than falsely correcting normal spoken English. Four checks must pass: definitely wrong, plausibly the learner's own error rather than ASR/spoken-language artifacts, useful to correct, and understandable without reading the surrounding conversation. ASR punctuation, false starts, repetitions, self-repairs, abandoned phrases and natural discourse markers are not grammar errors. Standard regional variants and fine-but-fancier alternatives are accepted. `natural` is reserved for clearly unidiomatic phrasing, not optional stylistic changes.

The display unit is the smallest **understandable utterance fragment**, not the fewest tokens. The original is an exact contiguous quote from the transcript. Expansion may include subjects/objects/clauses to explain the contrast, but the replacement must preserve meaning, register and surrounding text. Never invent referents, facts or intended speech. If expansion cannot make the correction understandable, skip it.

## Internal model contract and code gate

The notes model returns `wrong`, `better`, `kind`, plus:

- `reason`: nonempty explanation of the actual error and learning value;
- `confidence`: only `high` passes;
- `definitely_wrong`, `worth_showing`, `understandable_alone`: must be boolean `true`;
- `is_spoken_language_artifact`, `is_asr_uncertain`: must be boolean `false`.

Missing or incorrectly typed fields, unknown kinds, unchanged pairs, legacy bare strings, nonmatching original text, duplicate or overlapping candidates are discarded. Whole-word matching includes straight and curly apostrophes so `can` cannot match inside `can't` or `can’t`. Malformed notes JSON becomes an empty correction list; provider/network errors retain existing handling. Priority remains grammar > word > natural, capped at three **after** filtering; zero is valid. Onboarding still shows at most one live correction, ordinary dialogue at most three.

These flags are model judgments, not calibrated probabilities or audio verification. Code validates their shape and decision, but cannot prove their semantic truth. Internal fields are consumed at the gate, not sent to Telegram or persisted in the public `Correction` payload. Public `corrections: [{wrong, better, kind}]` and legacy output `notes: ["wrong|||better"]` remain unchanged. The generation budget for notes is 1200 tokens to fit decision metadata; reply budget is unchanged. No additional model call is added per live turn.

The final assessment uses the same shared policy and a stricter second review of original transcripts. Earlier acceptance is not evidence of correctness. Legacy saved pairs without metadata also receive full review. An unclear fragment is rejected rather than expanded by this second call, which can only select existing candidate IDs. At most two examples per skill remain; verification failure omits examples. Completed cached reports are not regenerated.

## Validation

All 153 AI-service tests passed. They verify strict decisions, exact context, contraction boundaries, overlaps, filtering before limits, malformed/legacy model output, unchanged public fields, and the actual `complete_notes` path. They do not prove model judgment quality.

`ai-service/evals/spoken_corrections.json` contains 15 synthetic cases: false starts, fillers, normal phrases/variants, optional `any`, names, garbled ASR, missing context, and clear errors with understandable expected pairs. Opt-in live check from `ai-service/`:

```sh
PYTHONPATH=. .venv/bin/python evals/run_spoken_corrections.py --live
```

It uses existing `OPENAI_API_KEY`, `OPENAI_BASE_URL`, and `LLM_MODEL` settings, sends synthetic cases, and prints live pairs plus their final-assessment selection. It reports false-correction cases separately from missed positives and unexpected positive pairs requiring human review. Inspect usefulness, context and meaning preservation manually; exact fixture matching alone is not semantic validation. This run was not performed locally because no provider credential was configured.
