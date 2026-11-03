# Vocabulary discovery on ordinary practice calls

Date: 2026-10-20. This is a read-only PROD evaluation. No discovery model or
new correction was enabled in the final assessment. Raw transcripts, audio,
user identifiers, and model outputs were kept in private temporary files for
the evaluation and were not committed.

## Sample

The retained AI store held 32 practice calls with at least three turns: 548
user turns total. Every turn matched an incoming OGG recording in the private
CMP audit store by chat and receipt time, with a maximum time difference of
one second. The existing privacy and size filters left 15 assessed calls for
model comparison: 260 turns, 13 calls with at least eight turns, and 93 saved
local correction edits. This is a selected retained sample, not a random or
population-wide estimate.

The two discovery models received each full conversation and its saved local
edit spans. A proposal counted as grounded only when its quoted error appeared
in the cited turn. Proposals matching saved local edits were removed. The
read-only probe is `ai-service/evals/compare_real_final_discovery.py`.

| Discovery run | Parseable calls | Total proposals | Grounded, novel proposals labelled Vocabulary |
| --- | ---: | ---: | ---: |
| Haiku 5.5, broad Grammar/Vocabulary prompt | 14/15 | 165 | 55 |
| GPT-4o mini, same prompt | 15/15 | 74 | 19 |
| Haiku 5.5, strict Vocabulary-only prompt | 15/15 | 70 | 51 |
| GPT-5 mini, same strict prompt | 15/15 | 70 | 45 |

The strict prompt asked for zero findings when appropriate and at most five
per call. Both models returned almost five findings on nearly every call. Many
were grammar changes, repetitions, optional wording, or unchanged text despite
the Vocabulary-only instruction. A valid JSON response was therefore not a
useful quality gate. The broad Haiku run also produced more than the requested
five examples per skill in several calls; the probe retained only its first 15
proposals per response.

## Audio check

Sixteen selected original clips were sent through an independent OpenAI
transcription model. Five ambiguous clips were also checked by an audio-native
model. This is model-assisted review of original audio, not a human listening
study; either audio model can mishear speech. The two audio routes nevertheless
exposed clear cases where the original transcript contained a mistaken word,
omitted a self-repair, or turned a learner's question into an apparent error.

Conservative review found at least four new, useful collocation or word-choice
edits in these calls. Other plausible proposals were word-form or grammar
errors rather than Vocabulary. Many model-labelled Vocabulary proposals were
false or uncertain, including optional rewrites and changes to correct speech.
This sample does not establish a reliable recall or precision rate for either
model. Further calls and human listening would be needed for such a rate.

## Existing final verifier is not an adequate filter

The 74 grounded, novel, model-labelled Vocabulary proposals from the first
comparison were sent through the current final verifier prompt in groups of
five. GPT-4o mini accepted 42; GPT-5 mini accepted 46. Both accepted obvious
false positives, including spoken self-repairs, a name reconstruction, and an
ASR error independently contradicted by the original audio. Both accepted
some clear useful edits too. More permissive acceptance is not the missing
piece; the verifier's semantic precision needs an independent improvement.

## Decision

Do not enable the full-dialogue discovery pass in the final assessment yet.
No tested discovery/verifier combination met the basic requirement that new
displayed examples be trustworthy. A safe next implementation would require
audio-grounded or otherwise independently validated candidate screening,
then a fresh blind quality evaluation on more ordinary calls and onboarding.
The existing five-examples-per-skill display limit remains appropriate.

The local partial-failure recovery for verification of *already saved*
conversation corrections is separate and remains ready for review. It does
not make the new discovery proposals safe to display.
