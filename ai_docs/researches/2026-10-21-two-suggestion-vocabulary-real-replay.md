# Two-suggestion Vocabulary: real-dialogue replay

Date: 2026-10-21. Status: isolated read-only evaluation of local, undeployed code.
No Telegram message, Redis record, or saved assessment was changed.

## Sample and method

Five completed conversations from retained production Redis data: three calls
and two onboardings, 28 learner turns. Selection was deterministic from
eligible reviewed records. Contact details, explicit school terms, and
underage conversations were excluded. This is a small selected sample, not a
representative benchmark. Audio was not reviewed; ASR uncertainty remains.
Raw transcripts and provider responses were held only in private temporary
files during the run and then deleted. This evaluation covered turn-time notes,
the local parser, final candidate collection, the independent verifier, and
final Vocabulary suggestion selection. It did not generate assessment prose or
scores.

The configured notes route was `openai/gpt-5.6-luna`, with a 1200-token
completion budget and 10-second request limit. The first run exposed an
article/preposition-only suggestion, so a deterministic Grammar-only filter
was added. The notes stage was rerun on the same adult sample using the updated
prompt and parser. The original verifier `openai/gpt-4o-mini` and the locally
selected `openai/gpt-5.6-luna` were then compared on the same five saved
Vocabulary candidates. Grammar verification remained on `gpt-4o-mini`.

## Results of the updated notes run

| Stage | Grammar | Short phrase suggestions | One-word upgrades |
| --- | ---: | ---: | ---: |
| Raw notes | 22 | 7 | 0 |
| Parsed and grounded | 18 | 5 | 0 |
| Final verifier accepted with `gpt-4o-mini` | 10 | 4 | 0 |
| Final verifier accepted with `gpt-5.6-luna` for Vocabulary | 10 | 1 | 0 |

All 28 notes requests returned without a request error. Median isolated
notes duration was 1.38 seconds, p95 5.30 seconds, maximum 8.06 seconds.
The four separate Vocabulary verifier requests to Luna took 1.49-2.19
seconds and had no error. These timings exclude STT, TTS, queue, Telegram,
and assessment generation.

The five grounded Vocabulary candidates were:

- `connected with economics` -> `related to economics`: already natural;
  a same-level paraphrase. Mini accepted; Luna rejected.
- `exit the market` -> `leave the market`: already natural, possibly a
  downgrade. Mini accepted; Luna rejected.
- `medical cars` -> `medical spas`: the intended words are uncertain from
  ASR; the replacement guesses meaning. Mini accepted; Luna rejected.
- `early on` -> `in the past`: loses the original timing nuance. Both rejected.
- `do filter coffee` -> `make filter coffee`: useful local collocation
  suggestion in the observed sentence. Both accepted.

The same notes model produced a different candidate set in two runs of the
same sample at temperature zero. One-word upgrades appeared in neither updated
run. This demonstrates unstable discovery and low observed Vocabulary yield;
it does not establish whether useful one-word upgrade opportunities existed.
A follow-up isolated search asked the same model to find only single-word
upgrades in the same 28 turns. It produced four candidates, all weak on manual
context review: `fun` -> `fascinating` changes the tone of `fun facts`;
`effects` -> `consequences` adds a possible negative sense; `change` ->
`switch` in a topic-change question is not a clear level gain; and `huge` ->
`extensive` makes `huge problem` unnatural. A separate check also rejected
`huge` -> `major` in that sentence. Thus the shared prompt is not the only
reason for zero accepted upgrades in this small sample. The sample has not
been exhaustively annotated for every valid missed opportunity.
A separate five-case synthetic control for Luna accepted three clear positives
and rejected two negatives in 1.52 seconds. That control is too small to
establish real-dialogue accuracy.

## Decision and limits

The local code now routes only Vocabulary suggestions to Luna and leaves
Grammar verification on mini. This avoids the three observed false approvals
in this sample. The improvement is supported by five real candidates and a
small synthetic control, not by a broad precision or recall estimate. Review
more conversations and disputed audio before deployment. Do not interpret
one accepted suggestion in 28 turns as the expected production rate.
