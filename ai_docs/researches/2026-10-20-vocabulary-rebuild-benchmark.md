# Vocabulary rebuild: first paired benchmark

Date: 2026-10-20. Status: isolated evaluation; **the proposed workflow failed this benchmark and must not be enabled**. No Telegram, Redis, review, or user-facing production state was changed. Candidate worksheets are private thread artifacts, not repository data.

## Sample and annotation

The sample is the same six complete reviewed conversations as the previous category replay: three ordinary calls (56 learner turns) and three onboardings (23 turns), 79 turns total. Selection is deterministic and privacy-filtered, but not representative. For this run, every turn was reviewed for candidate pairs and obvious missed opportunities. A private worksheet contains one row per turn and 224 grounded prototype candidate pairs. Each pair has a preliminary agent judgment: `useful`, `wrong`, or `uncertain`. These are not expert-validated gold labels. The `known_missed` entries are a lower bound on misses; blank entries do not prove a clean turn.

Seven disputed call clips were matched to incoming OGG files within two seconds and checked with an independent Groq Whisper transcription. This was model-assisted audio cross-checking, not human listening. It confirmed the spoken `seeding garlic` and `I thought I stay garlic as it is`; it made the saved `go off to choir` span uncertain. Other checked clips contained ambiguous or garbled words, so those candidates remain uncertain. Onboarding audio was not reviewed. Short learner phrase pairs are available in private thread storage; raw transcripts, audio, user IDs, and model responses are not in the repository.

## Compared workflows

Both turn searches used the same configured provider route, `openai/gpt-5.6-luna`, at temperature zero, on the same 79 turns. The **current local workflow** used its combined Grammar/Vocabulary notes prompt, parser, final verifier, and selection functions. The **prototype** used a dedicated Vocabulary prompt with a short `source`, `replacement`, `type`, and `benefit` response. It saw the preceding turn and current coach question when present. A deterministic check required a unique verbatim source span. A separate `openai/gpt-4o-mini` request then judged all candidates from each turn against the original and edited full turn. It returned an accept/reject decision per candidate. Presentation was simulated by taking the first five accepted Vocabulary errors and first three accepted alternatives per conversation. This simple ranking is a baseline, not a production design.

The prototype had one local validation defect: a no-op replacement reached the quality gate. The gate rejected it. Counts below include that candidate at the gate stage. The current model output varied from the previous replay despite temperature zero, so the current-run figures are paired measurements, not a reconstruction of the earlier run.

| Stage | Current local workflow | Dedicated Vocabulary prototype |
| --- | ---: | ---: |
| Turn requests returned | 79/79 | 79/79 |
| Vocabulary candidates after local parsing | 24: 23 errors, 1 alternative | 223 distinct, grounded, non-no-op candidates; 224 sent to gate including one no-op |
| Candidates accepted by model check | 19 | 127 |
| Cards after display limits | 16: 15 errors, 1 alternative | 41: 25 errors, 16 alternatives |
| Clearly useful displayed cards in preliminary review | 4 | 6 |
| Clearly wrong displayed cards | 8 | 30 |
| Uncertain displayed cards | 4 | 5 |

The prototype search actually returned 231 raw candidates. Five quoted spans were absent from the current transcript, two were not unique, and one was a no-op. One absent span differed only in capitalization from the transcript: `take a lot of my time` → `take up a lot of my time`. A case-insensitive match would retain this candidate, but its teaching value is uncertain: both expressions are acceptable, and the added particle alone is not a clear level upgrade. The other missing spans were not safely recoverable by a case-insensitive match.

Across the 224 candidates sent to the prototype gate, revised preliminary manual review found **25 useful, 178 wrong, and 21 uncertain**. The gate accepted **20 useful, 90 wrong, and 17 uncertain**. It rejected five useful candidates. In this sample it did not provide a dependable quality filter. It also returned reason strings outside its requested enum, showing that a parseable JSON object did not enforce the decision contract.

The 25 useful prototype candidates comprise 18 Vocabulary errors, four established-expression suggestions, and three word upgrades. That is evidence that a focused search can find both kinds of optional teaching point. It is not evidence that the present prototype is safe: most of its suggestions were wrong or miscategorised. Onboarding contributed only one of those 25 useful candidate pairs. These labels remain provisional and need a broader human review.

## Concrete misses and false approvals

- `put makeup on my face` → `put on makeup` was found by the dedicated search and accepted by its gate, but its source span exceeds the later proposed short-phrase limit. The current run did not retain it.
- `I feel scared` → `I feel frightened` was accepted by the gate, but both adjectives are B1 in Cambridge Dictionary; this is not a demonstrated level upgrade. It is now labelled wrong under the learner's stricter rule. The pair did not reach the prototype's displayed cards, so their reported precision is unchanged.
- `go to work` → `commute to work` was supported by the speaker's daily trip, but the verb adds a regular-home/work-travel meaning. It is now labelled uncertain, not an established useful upgrade; the gate rejected it.
- `maintain my level of English` → `keep up my English` is a valid alternative but not a clear upgrade: `maintain` is already an advanced verb. Neither parsed search retained it, and it should not count as a known miss.
- `a random choice` → `a random draw` in a paper-draw context was found and accepted by the prototype. It did not reach the current final assessment.
- `seeding garlic` → `planting garlic` preserves the planting action heard in the audio. The previous assessment's `growing garlic` changed that action into the whole growing process.
- The prototype accepted article, plural, word-order, and self-repair edits labelled `vocabulary_error`, as well as optional rewrites that only paraphrased or changed meaning. In arrival-order selection, these occupied places before stronger teaching points.
- Several useful opportunities were assigned the wrong category: `happened that` → `it turned out that`, `give us their harvest` → `bear fruit`, and `watch only best moment` → `watch the highlights` repair faulty original expressions; they are not optional alternatives.
- The current verifier also approved a same-level `exchange our thoughts and opinions` → `share our thoughts and opinions` alternative. Its displayed error pairs included incomplete repairs and a tense problem.

## Time and interpretation

The current search had a median isolated model duration of 2.07 seconds and p95 of 4.95 seconds. The dedicated search plus gate had a combined median of 4.27 seconds and p95 of 8.13 seconds; the maximum observed sum was 10.6 seconds. These exclude STT, TTS, Telegram, and queue time and do not prove live latency. No model call timed out in this isolated replay.

The first prototype is **worse than the current path at display precision**. Its increased candidate count is not a quality gain. A stronger or differently structured validator may help, but that claim requires a fresh comparison against these labels and then a separate holdout sample. Do not tune a prompt on these six conversations and report the same six as unbiased proof. No runtime behavior should change from this evaluation.
