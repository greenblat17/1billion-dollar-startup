# Larger live-turn Vocabulary probe

Date: 2026-10-20. Status: isolated provider evaluation; no push or deployment.

## Method

The locally revised live notes prompt was sent to the configured `openai/gpt-5.6-luna` route through the PROD AI container's OpenRouter account. Requests were isolated: they did not create Telegram messages, call records, Redis entries, or assessments. The code's current parser and grounding gate were applied locally afterward. Final candidate verification used the local `openai/gpt-4o-mini` prompts in isolated requests.

The controlled set contained 46 synthetic turns: 15 with an opportunity for a useful alternative, 7 with a clear error, 8 with both, and 16 negative or spoken-artifact controls. One case originally labelled as an error was reclassified after checking that `take a decision` is accepted British English ([Cambridge Dictionary](https://dictionaryblog.cambridge.org/2018/10/17/making-an-effort-and-telling-a-joke-avoiding-common-errors-with-collocations/)). Each turn was run twice, for 92 requests. The opportunity labels are researcher judgments, not a complete list of valid alternatives. The comparison alternative-only prompt was run once on the same 46 turns; it is **not** in the product code.

A separate read-only sample used one turn from each of 34 retained, reviewed PROD records: 19 practice calls and 15 onboardings. Turns with contact details, URLs, digits, or very short/long transcripts were excluded. The sample is selected and small; it is not representative of users. Raw text and responses were held only in private temporary files during the evaluation, then deleted. They were not added to this repository. No independent audio check or human annotation of all real turns was performed.

## Results

| Prompt and sample | Alternative opportunities found | Error findings | Negative controls with an alternative |
| --- | ---: | ---: | ---: |
| Unified prompt, synthetic pass 1 | 5/23 | 15/15 error-bearing turns before safety fixes | 0/16 alternatives; 1 false error |
| Unified prompt, synthetic pass 2 | 5/23 | 15/15 before safety fixes | 0/16 alternatives; 1 false error |
| Alternative-only trial, synthetic pass 1 | 16/23 | 1/7 error-only turns mislabelled as an alternative | 3/16 |
| Unified prompt, retained real turns | 2/34 turns | 18/34 turns contained an error candidate | n/a |
| Alternative-only trial, same real turns | 5/34 turns | Several error repairs were mislabelled as alternatives | n/a |

The unified prompt found alternatives in 6/30 alternative-only synthetic runs and 4/16 mixed runs. It consistently found the obvious errors, but generated an invalid edit twice: `explained me` was replaced with words already present immediately after that span, producing a repeated object. The final error verifier accepted one copy of this invalid edit. A local deterministic gate now rejects an edit that copies an adjacent two-word phrase from outside its declared span. A second false correction changed the accepted British collocation `took a decision` to `made a decision`. The local parser and both prompts now explicitly reject that regional standardization. After these two safety fixes, error candidates remain in 12/14 error-only and 14/16 mixed runs, with zero findings in 32 negative-control runs. The `explained me` edit would require the model to select a wider original span.

The unified prompt produced no alternatives on 32 negative-control runs, but before the regional-variant fix it produced the false `took a decision` correction twice. In the real sample it produced two alternatives; one was a useful activity term supported by context, while `switch the topic` → `change the subject` was too small a gain, especially in an irritated turn. The final alternative verifier accepted both. Its synthetic verification accepted all six distinct alternatives, including a similarly weak free-time synonym. This shows the verifier does not reliably filter low-value suggestions.

The alternative-only trial recovered more intended opportunities in the synthetic set. It also proposed collocation repairs as optional alternatives, a meaning-changing rewrite, and several tiny synonym swaps. In the real sample it produced nine proposals in five turns. The final verifier accepted eight, including disguised error repairs and tiny style edits. More discovery without a stronger quality gate would worsen the learner experience.

All 34 unified real-turn provider requests completed without an API error or 10-second timeout. The measured provider-call p50 was 2.12 seconds and p95 was 4.39 seconds in this concurrent probe. These are not production end-to-end latency measurements.

## Conclusion

Moving alternative discovery into each live turn fixes the workflow boundary but does **not** yet deliver enough useful Vocabulary suggestions. The combined prompt is too conservative on this sample. A dedicated alternative prompt raises recall but has poor precision, and the present final verifier accepts weak or incorrect options. Do not deploy the local Vocabulary revision on the strength of this evaluation. The next iteration needs explicit human-labelled real-turn examples and a stronger criterion for learning value and meaning preservation; a new prompt should be judged on both useful accepted alternatives and false cards, not proposal count alone.
