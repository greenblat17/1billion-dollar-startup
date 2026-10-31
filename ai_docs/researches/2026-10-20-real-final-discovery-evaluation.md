# Independent correction search on retained real conversations

Date: 2026-10-20. Read-only evaluation against retained PROD data. No new
correction search was enabled for users, and no transcripts were committed.
The reproducible probe is `ai-service/evals/compare_real_final_discovery.py`.

## Sample and method

The retained store contained 67 assessed calls and 21 assessed onboardings with
transcripts. After excluding empty, oversized, and obviously sensitive text, the
probe selected 11 conversations by a stable hash of their keys: seven calls and
four onboardings. They comprised three calls with at least eight turns, four
calls with one or two turns, one 37-turn onboarding, and three shorter
onboardings. Four selected conversations had no saved local correction edits.
One short call was pasted technical material, outside the intended spontaneous
speech population. Another was largely a name/spelling exchange. We retained
both as negative controls but did not count their proposals as learner errors.

Both models received the same numbered user transcripts and short local changed
spans from saved corrections. The prompt requested only clear new Grammar or
Vocabulary edits, anchored to an exact turn substring. Model identity was hidden
while 54 proposals were manually labelled from short redacted excerpts. These
labels are a conservative analyst review, not a second expert review or audio
ground truth. The original speech audio was not replayed, so ASR ambiguity remains.

| Model | Parsed JSON | Confirmed new proposals | Uncertain | Rejected |
| --- | ---: | ---: | ---: | ---: |
| `openai/gpt-4o-mini` | 11/11 | 6 | 4 | 30 |
| `anthropic/claude-haiku-5.5` | 7/11 | 9 | 4 | 1 |

The 15 confirmed proposals contain about 12 distinct edits after overlap between
models. They came from four onboarding conversations. In one onboarding with no
saved local edits, the models found several clear errors. No selected ordinary
call yielded a confirmed new edit in this run. That result is too small and
skewed to conclude that calls have no missed errors. Many sampled calls were
one or two turns, and Haiku failed to return parseable JSON on the two long calls.

Only three confirmed proposals were labelled `word` by the models, and two more
were labelled `natural`. Some of these are arguably Grammar, so this does not
establish a reliable Vocabulary recall gain. Category assignment needs separate
human review before any Vocabulary-specific product claim.

## Failure and precision findings

`gpt-4o-mini` returned valid JSON on every conversation, but proposed many
invalid changes: seven substrings absent from the cited turn, four duplicates,
four transcript artifacts, five false edits, and proposals on name spelling,
non-English content, and pasted technical text. Exact-span grounding and
deduplication are necessary but cannot alone filter all false edits.

Haiku's four unparsed outputs were truncated (`finish_reason=length`) under a
1500-token response cap. Repeating the same 11 cases with JSON-object mode
still left three truncated outputs. A targeted retry on those three cases with
reasoning disabled and the same cap parsed two; the remaining long call parsed
after raising the cap to 3000. That last response contained 15 proposals even
though the prompt requested at most five per skill. These retries checked
response completeness, not correction quality. They were not added to the
blind quality totals above.

The measured request times in the first pass include waiting for the probe's
three-slot semaphore. They are not provider latency measurements and cannot
predict assessment delay. This sample also cannot estimate production costs,
population-wide precision, or the share of missed Vocabulary errors.

## Decision

An independent full-dialogue search can find real missed edits, especially in
onboarding. Do not enable either candidate directly in the final assessment
yet. First, require a bounded, parseable response, exact-turn grounding,
deduplication against saved edits, and independent verification of every new
pair. Then evaluate a larger set of spontaneous calls and onboardings with
audio-aware review and a separate Vocabulary scorecard. Run the search in
parallel with saved-candidate verification so a search failure cannot remove
the already saved examples. Preserve the limit of five displayed examples per
skill.

The partial saved-candidate verifier recovery is implemented locally in
`ai-service/app/correction_verification.py`; it is not deployed. It preserves
verified groups when another group times out or fails. A complete failure still
omits all examples, so the independent search must not rely on unverified pairs.
