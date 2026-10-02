# Corrections for spontaneous speech

Applies to **all clip conversations**, including ordinary Telegram dialogue, onboarding voice turns, and the final onboarding assessment. The shared policy is `ai-service/app/correction_policy.py`. The same `OpenAiChatModel.complete_notes` produces live corrections for ordinary and onboarding turns; final assessment separately re-verifies candidate IDs.

## Selection policy

Prefer precision over recall: a missed error is better than falsely correcting normal spoken English. Four checks must pass: definitely wrong, plausibly the learner's own error rather than ASR/spoken-language artifacts, useful to correct, and understandable without reading the surrounding conversation. ASR punctuation, false starts, repetitions, self-repairs, abandoned phrases and natural discourse markers are not grammar errors. Standard regional variants and fine-but-fancier alternatives are accepted. `natural` is reserved for clearly unidiomatic phrasing, not optional stylistic changes.

The display unit is a short contiguous clause or sentence copied from the full transcript. It includes the words needed to understand the edit, even across a pause; Whisper punctuation is not a trusted boundary either. The model returns exact `context`, `error` inside it, and `replacement`. Code requires a whole-word context match and a unique error inside that context, limits context to 30 words, and constructs `wrong=context` and `better=context` with only the local replacement. Identical repeated contexts receive the same edit; long or ambiguous candidates are omitted. Telegram strikes only the changed words and places the bold replacement beside them inside the context.

## Internal model contract and code gate

In the same completion, the notes model first returns `speech_artifacts` (exact spans of repetitions, false starts, self-repairs or discourse markers), then candidates with `context`, `error`, `replacement`, `kind`, plus:

- `reason`: nonempty explanation of the actual error and learning value;
- `confidence`: only `high` passes;
- `definitely_wrong`, `worth_showing`, `understandable_alone`: must be boolean `true`;
- `is_spoken_language_artifact`, `is_asr_uncertain`: must be boolean `false`.

Missing or incorrectly typed fields, unknown kinds, unchanged pairs, legacy bare strings, nonmatching original text, ambiguous edits, edits overlapping marked speech artifacts, duplicate or overlapping candidates are discarded. Whole-word matching includes straight and curly apostrophes so `can` cannot match inside `can't` or `can’t`. Malformed notes JSON becomes an empty correction list; provider/network errors retain existing handling. Priority remains grammar > word > natural, capped at three **after** filtering; zero is valid. Onboarding still shows at most one live correction, ordinary dialogue at most three.

These flags are model judgments, not calibrated probabilities or audio verification. Code validates their shape and decision, but cannot prove their semantic truth. Decision flags stay internal. The model's `reason` must still be nonempty for a candidate to pass; a separate display check allows it as an optional Russian `explanation` only when it is one line, one sentence, at most 160 characters, and not a generic phrase. A poor explanation is omitted while the accepted correction remains. Public `corrections: [{wrong, better, kind, explanation?}]` carries the optional field; legacy output `notes: ["wrong|||better"]` remains unchanged. The generation budget for notes is 1200 tokens; reply budget is unchanged. `NOTES_MODEL` and `LLM_MODEL` both default to `openai/gpt-5.6-luna`.

Telegram renders contiguous Latin words in a Russian live-correction explanation as one inline monospace fragment (for example, `an AI` and `a person`). This changes only presentation: the explanation text and the existing correction decision stay unchanged. Cyrillic text and punctuation remain regular text.

Live correction generation makes one model call per turn. A malformed result or generation failure omits cards without failing the voice reply. The model can still misclassify a spoken artifact while marking its own candidate as high confidence; grounding and decision-field checks do not independently establish linguistic correctness. Prompt examples explicitly distinguish repeated short words from a complete future conditional. The final assessment retains its separate verification.

The final assessment uses the same shared policy and a stricter second review of original transcripts. Earlier acceptance is not evidence of correctness. Legacy saved pairs without metadata also receive full review. An unclear fragment is rejected rather than expanded by this second call, which can only select existing candidate IDs. The final onboarding assessment selects at most five examples per skill after verification. Its existing review call supplies a short explanation for each selected pair; a pair without a confident explanation is omitted. The assessment card keeps sentence context and highlights only the local edit, as dialogue cards do. Verification failure omits examples. Call reviews keep their two-example limit. Completed cached reports are not regenerated.

## Validation

Unit tests verify strict decisions, exact context and local edits, contraction boundaries, overlaps, filtering before limits, malformed/legacy model output, unchanged public fields, and the actual `complete_notes` path. They do not prove model judgment quality.

`ai-service/evals/spoken_corrections.json` contains 100 synthetic cases, including pauses that cut a conditional, a tense adverb, or `it'll` away from the broken words and a long no-pause monologue. Positive cases now include `expected_context` and `expected_edits` (two for the two-error case); three pause cases that the old contract marked `omit` are relabeled as real errors. Exact differences are still inspected manually because alternative context widths and corrections can be valid. Opt-in live check from `ai-service/`:

```sh
PYTHONPATH=. .venv/bin/python evals/run_spoken_corrections.py --live
```

It uses existing `OPENAI_API_KEY`, `OPENAI_BASE_URL`, and `NOTES_MODEL` settings, sends synthetic cases through the live generation path, and prints surviving pairs. It reports false-correction cases and missed positives separately, plus edits and contexts for human review. Inspect usefulness and meaning preservation manually. The pause-bounded baseline had six false cards and two missed errors among these 100 cases; its labels included the three real errors described above. A real-audio sample is needed before deployment because synthetic transcripts cannot reveal Whisper mistakes.

On 2026-09-30, two complete Luna-only passes on the 100 synthetic cases each had zero false-correction cases and zero missed-positive cases. The final pass had two edit mismatches that are valid alternatives (`an advice` → `some advice`; `suggested me to` → `suggested that I`) and one context mismatch that retained a spoken `um` while showing the full error. A first targeted repeat exposed a false `the the` card in three of four calls and two missed complete conditionals; after the prompt clarification, four repeats of each risky negative, two of each risky positive, and 27 calls on nine unseen variants matched their labels. These are small synthetic samples, not a measured production error rate. The separate live reviewer was removed; ordinary turns now use one Luna call for the reply and one Luna call for corrections, in parallel. Final onboarding assessment verification remains separate.
