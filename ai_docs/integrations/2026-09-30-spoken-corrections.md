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

The code gate also rejects a proposed edit that removes a correct modal or main verb from a `modal + to + verb` construction. A live synthetic evaluation once proposed `he can to swim` → `he swim`; that card must be omitted even though the original clause contains an error. The safe correction is `he can swim`.

These flags are model judgments, not calibrated probabilities or audio verification. Code validates their shape and decision, but cannot prove their semantic truth. Decision flags stay internal. The model's `reason` must still be nonempty for a candidate to pass; a separate display check allows it as an optional Russian `explanation` only when it is one line, one sentence, at most 160 characters, and not a generic phrase. A poor explanation is omitted while the accepted correction remains. Public `corrections: [{wrong, better, kind, explanation?}]` carries the optional field; legacy output `notes: ["wrong|||better"]` remains unchanged. The generation budget for notes is 1200 tokens; reply budget is unchanged. `NOTES_MODEL` and `LLM_MODEL` both default to `openai/gpt-5.6-luna`.

Live correction generation starts in parallel with the reply and has an 8-second deadline from request start (fixed in `pipeline.py`). On expiry, the request is cancelled and a ready spoken reply is delivered without correction cards. A malformed result or generation failure likewise omits cards without failing the voice reply. The model can still misclassify a spoken artifact while marking its own candidate as high confidence; grounding and decision-field checks do not independently establish linguistic correctness. Prompt examples explicitly distinguish repeated short words from a complete future conditional. The final assessment retains its separate verification. The deadline choice and evidence are in [the 2026-10-02 experiment](../researches/2026-10-02-correction-timeout-experiment.md).

In onboarding, the same bounded correction request runs alongside question assessment. Once the question is ready, TTS can run while corrections finish. The final assessment waits for corrections before verifying and showing examples.

SDK automatic retries are disabled for the AI service's OpenAI-compatible STT, LLM, TTS, and review clients. Live corrections use one explicit controller for at most two provider attempts within the single 8-second deadline, including backoff and parsing. It retries 429/5xx, network/connection timeout, no `choices`, empty text, or malformed JSON only if time remains. A valid empty result or safety-filtered candidate is never retried. Other STT, TTS, and review operations retain their own single explicit retry. Ordinary and onboarding voice turns use the same bounded correction path; the final assessment review is separate.

One final outcome is counted per live correction request by Moscow day in `metrics:corrections:YYYY-MM-DD` and returned under `corrections` by `/internal/metrics`. Each outcome has `count`, summed `elapsedMs`, and `secondAttempts`: `shown`, `empty`, `filtered`, `deadline`, `rate_limit`, `provider_timeout`, `provider_5xx`, `provider_4xx`, `network`, `no_choices`, `empty_text`, `invalid_json`, `invalid_schema`, `token_limit`, or `other_error`. `empty` means a valid empty notes list; `filtered` means model candidates were rejected by the safety gate. The existing `/admin/metrics` dashboard shows daily totals, failure share, retry count, and a per-outcome latency table. Counters start at deployment and are not backfilled. Do not log transcript text or provider response bodies in these metrics.

The final assessment uses the same shared policy and a stricter second review of original transcripts. Earlier acceptance is not evidence of correctness. Legacy saved pairs without metadata also receive full review. An unclear fragment is rejected rather than expanded by this second call, which can only select existing candidate IDs. At most two examples per skill remain; verification failure omits examples. Completed cached reports are not regenerated.

## Validation

Unit tests verify strict decisions, exact context and local edits, contraction boundaries, overlaps, filtering before limits, malformed/legacy model output, unchanged public fields, and the actual `complete_notes` path. They do not prove model judgment quality.

`ai-service/evals/spoken_corrections.json` contains 100 synthetic cases, including pauses that cut a conditional, a tense adverb, or `it'll` away from the broken words and a long no-pause monologue. Positive cases now include `expected_context` and `expected_edits` (two for the two-error case); three pause cases that the old contract marked `omit` are relabeled as real errors. Exact differences are still inspected manually because alternative context widths and corrections can be valid. Opt-in live check from `ai-service/`:

```sh
PYTHONPATH=. .venv/bin/python evals/run_spoken_corrections.py --live
```

It uses existing `OPENAI_API_KEY`, `OPENAI_BASE_URL`, and `NOTES_MODEL` settings, sends synthetic cases through the live generation path, and prints surviving pairs. It reports false-correction cases and missed positives separately, plus edits and contexts for human review. Inspect usefulness and meaning preservation manually. The pause-bounded baseline had six false cards and two missed errors among these 100 cases; its labels included the three real errors described above. A real-audio sample is needed before deployment because synthetic transcripts cannot reveal Whisper mistakes.

On 2026-09-30, two complete Luna-only passes on the 100 synthetic cases each had zero false-correction cases and zero missed-positive cases. The final pass had two edit mismatches that are valid alternatives (`an advice` → `some advice`; `suggested me to` → `suggested that I`) and one context mismatch that retained a spoken `um` while showing the full error. A first targeted repeat exposed a false `the the` card in three of four calls and two missed complete conditionals; after the prompt clarification, four repeats of each risky negative, two of each risky positive, and 27 calls on nine unseen variants matched their labels. These are small synthetic samples, not a measured production error rate. The separate live reviewer was removed; ordinary turns now use one Luna call for the reply and one Luna call for corrections, in parallel. Final onboarding assessment verification remains separate.

On 2026-10-02, a further 100-case live pass through the new two-attempt, 8-second path returned zero labeled false-correction cases and zero missed-positive cases, but manual inspection found the unsafe `can to swim` → `swim` edit described above. Aggregate labels alone therefore do not establish semantic safety. The modal-preservation gate was added after this run; it has a focused regression test, while the full live pass has not been repeated with the new gate.
