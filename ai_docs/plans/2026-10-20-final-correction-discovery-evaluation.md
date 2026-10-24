# Independent final correction discovery evaluation

Status: initial real-conversation evaluation completed on 2026-10-20; larger
audio-aware review and implementation remain open. Existing final reviews still
show only corrections found during the conversation and accepted by the verifier.
Results: `../researches/2026-10-20-real-final-discovery-evaluation.md` and
`../researches/2026-10-20-real-call-vocabulary-audio-evaluation.md`.
The second evaluation found useful misses but failed the precision gate; do not
enable discovery in the final assessment from these results.

## Question

Does a second model reading the entire completed conversation find useful Grammar or
Vocabulary errors that the per-turn notes model missed, without adding too many false
corrections to spoken English or ASR artifacts?

## Candidates

Use `openai/gpt-4o-mini` as the current verifier and baseline for an independent
discovery prompt. Compare it with models from other families available on OpenRouter,
starting with `anthropic/claude-haiku-5.5` and `mistralai/mistral-small-2603`.
The public model catalog does not establish that any of these is trained specifically
for spoken-English correction. Do not treat provider descriptions or general benchmarks
as evidence of accuracy on this task.

## Protocol

1. Assemble a dated, de-identified sample of completed onboarding and practice calls.
   Include short and long conversations, examples with many/few/no saved candidates,
   and calls whose per-turn notes timed out or returned an empty list. Keep raw content
   within the existing retention boundary; do not commit transcripts to the repository.
2. Give each model the same transcripts, the saved candidate pairs, and a strict task:
   propose only genuinely new, exact-span, meaning-preserving local edits. Require a
   turn index and category. Compare the local changed spans, not the full sentence
   context stored in each candidate, when deduplicating.
3. Blindly review every novel proposal. Mark confirmed missed error, false correction,
   ASR/spoken-language artifact, duplicate, or uncertain. Review Vocabulary separately.
4. Compare confirmed novel corrections per conversation and false corrections per
   conversation, plus latency, provider failures, and cost. Break out onboarding and
   practice calls, and inspect long calls separately.
5. Only if the gains justify the errors and delay, add the independent pass to the
   final assessment. Run it in parallel with saved-candidate verification. Ground each
   proposed edit in an exact transcript span, deduplicate, and verify new candidates
   before selecting at most five examples per skill. Do not show unverified proposals.

The 2026-10-20 synthetic eight-turn replay can exercise the harness but cannot decide
whether a model improves real conversational feedback.
