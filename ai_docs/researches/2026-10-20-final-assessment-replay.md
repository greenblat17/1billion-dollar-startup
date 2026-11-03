# Synthetic conversation replay: Vocabulary and Grammar

Date: 2026-10-20. The reproducible input and runner are in
[`replay_final_assessment.py`](../../ai-service/evals/replay_final_assessment.py); the full
synthetic output is in
[`final_assessment_replay_results_2026-10-20.json`](../../ai-service/evals/final_assessment_replay_results_2026-10-20.json).

## Method and boundary

Eight connected learner utterances about redesigning a website were sent, one by one, to
the **deployed production AI container's** `OpenAiChatModel.complete_notes_result`. Each
request had the live 10-second deadline. The returned corrections were kept on synthetic
turns and passed through the deployed onboarding placement, correction verifier, onboarding
final review, then ordinary practice-call final review with that onboarding score as its
baseline. The replay used the production provider settings but did not create a Telegram
chat, call, Redis record, or database row. It did not run speech recognition, reply
generation, audio timing, or Telegram card rendering. Its `seconds` field is synthetic.

The deployed notes model was `openai/gpt-5.6-luna`. The configured primary final-review
model was `google/gemini-3.5-flash-lite`, with `openai/gpt-4o-mini` as fallback. The first
review attempt in both branches returned `PermissionDeniedError`; each fallback succeeded.
This is an observed production configuration/provider issue, but the replay does not
establish how frequently real reviews encounter it.

## Stage results

| Utterance | Intended teaching point | Notes output | Final output |
| --- | --- | --- | --- |
| 1 | `did a decision` → `made a decision` | Vocabulary | Vocabulary, both branches |
| 2 | `made a photo` → `took a photo` | Absent | Absent |
| 2 | `an advice` → `some advice` | Grammar | Rejected by verifier in both branches |
| 3 | `borrowed my laptop to a colleague` → `lent my laptop to a colleague` | Absent | Absent |
| 4 | `interesting in` → `interested in` | Classified Vocabulary | Vocabulary, both branches |
| 4 | `suggested her to move` → `suggested that she move` | Grammar | Grammar, both branches |
| 5 | `lost the train` → `missed the train` | Vocabulary | Vocabulary, both branches |
| 7 | `did a mistake` → `made a mistake` | Vocabulary | Vocabulary, both branches |
| 6, 8 | Correct English | No correction | No correction |
| 7 | `I, I, uh` and `the the` speech artifacts | No correction | No correction |

Eight intended teaching points yielded six saved candidates, five verified examples,
and five examples in each generated final report (one Grammar, four Vocabulary). The
`made a photo` example is somewhat context dependent; the other omitted points are
clear in this constructed conversation. `interesting in` was taught under Vocabulary,
although the adjective form could reasonably be treated as Grammar. The displayed
examples use sentence context and change only the local phrase.

The `an advice` pair is a valid correction. The batch verifier rejected it in both final
branches. In a follow-up probe, the same saved pair alone was accepted in three of three
verifier requests. The cause of the batch rejection is not established; the result shows
that acceptance can depend on the submitted candidate set. The final review cannot add
the missed `made a photo` or `borrowed ... to` pairs because it only selects saved IDs.

The onboarding score was B1 high, 57 for overall and all three skills. The subsequent
call review moved each to 56, within the intended ±2 bound. Both final reports carried
the same five examples. Onboarding described fluent speech with no significant hesitations,
while the call review cited `I, I, uh` as a hesitation; this is a text judgment difference,
not evidence from audio. The generated 97 WPM derives from the synthetic 112 seconds.

## Interpretation

This replay demonstrates concrete recall loss before the final review and one lost
candidate during final verification. It also shows that the final review can provide
multiple Vocabulary examples when multiple verified candidates reach it. None of these
turns produced more than two candidates, so the deployed three-candidate cap did not
affect this sample. Neither the 1200-token limit nor the 10-second notes deadline fired.
One synthetic run does not measure the real-user miss rate or isolate whether the notes
model's instruction, its conservative policy, or model variability caused each omission.
The repository's removal of the three-candidate cap had not been deployed for this run.
The subsequent [production audit](2026-10-20-production-correction-audit.md) found
additional loss at the final verifier's 10-second deadline in long real calls.

Reproduce from a trusted machine with access to the AI container by piping the runner to
`docker exec -i ai-service python -` over SSH. Inspect the output JSON before drawing a
product conclusion; model output and provider permissions can change.
