# Onboarding review model comparison

Status: initial live comparison on synthetic cases completed 2026-10-02; real onboarding cases and human quality review pending.

## Goal

Compare the reliability, assessment quality, latency, and cost of the model that generates the final Telegram onboarding review. The DEV failure on 2026-10-01 was a syntactically valid JSON response with a skill `band` but no required `position`; this comparison must count that as an invalid result. A model change alone is not the fix for the missing-field failure.

## Candidates

Use the same OpenRouter route, review prompt, payload, temperature, and output-token limit for every candidate:

| Candidate | OpenRouter model ID | Role |
| --- | --- | --- |
| Current DEV baseline | `openai/gpt-4o-mini` | Existing behavior |
| OpenAI alternative | `openai/gpt-5.6-luna` | Quality and reliability comparison |
| Google alternative | `google/gemini-2.5-flash` | Flash comparison |
| Google alternative | `google/gemini-3.5-flash-lite` | Newer low-latency Flash Lite comparison |
| Google alternative | `google/gemini-3.8-flash` | Newer Flash comparison |

Pin each model ID for the run; do not use a moving `latest` alias. Record the provider selected by OpenRouter, request settings, date, and model ID with the results. If a candidate requires a provider-specific setting to return the contract, record it and repeat the baseline with any equivalent setting before drawing a speed conclusion.

## Inputs and scoring

- Use the same anonymized saved onboarding payloads for all four candidates. Include the failed DEV case if its saved input can be recovered, plus thin speech, mixed proficiency, missing fluency signals, and examples with uncertain corrections. Do not save raw user transcripts in this document or commit them to the repository.
- Run every payload three times per candidate, including a repeat of the failed case. Keep one request at a time for comparable latency.
- Parse each output with the production `parse_review` validator. Report valid outputs / total, failure reason by field, and whether retrying the identical request succeeds. An invalid result remains a failure even if a later retry succeeds.
- Review valid reports blind to model name: grounded CEFR explanation, justified skill bands and positions, accurate short diagnostic sentences, no invented evidence, and safe omission of uncertain examples. Valid JSON alone is insufficient.
- Record per-request wall time and provider-reported token usage and cost. Compare median and p95 latency, plus total cost per completed valid review. Report retries and failures separately; the user-visible wait also includes other pipeline steps.

## Decision

Choose a replacement only if it improves the assessment and schema success rate without unacceptable user wait or cost. Independently add a strict output contract and a bounded recovery path for rejected reviews; neither can be assumed from the chosen model. Keep ordinary dialogue on its existing model while testing the onboarding review, since changing `LLM_MODEL` currently affects other calls too.

## Initial live result (2026-10-02)

Run from `ai-service/` using the current production review prompt, `json_object` response format, temperature 0, and three synthetic payloads (`thin`, `connected`, `mixed`) repeated three times each. Gemini 3.5 Flash Lite was added in a later run with the same settings and cases. The test script is `evals/run_onboarding_review_models.py`. Cost below is the sum reported by OpenRouter for all requests, including invalid outputs. OpenRouter's selected provider was not pinned or recorded, so routing variance may affect timing. No user transcript or API key was written to the repository. Results are local temporary artifacts, not committed fixtures.

| Model | Max completion tokens | Valid / total | Median request time | OpenRouter cost / all requests |
| --- | ---: | ---: | ---: | ---: |
| `openai/gpt-4o-mini` | 1200 | 9/9 | 3.60 s | $0.00277 |
| `openai/gpt-5.6-luna` | 1200 | 7/9 | 8.97 s | $0.01013 |
| `google/gemini-2.5-flash` | 1200 | 9/9 | 2.77 s | $0.01208 |
| `google/gemini-3.5-flash-lite` | 1200 | 9/9 | 2.34 s | $0.01208 |
| `google/gemini-3.8-flash` | 1200 | 2/9 | 9.88 s | $0.04896 |

Every invalid response at 1200 tokens ended with `finish_reason=length` and truncated JSON. The two Luna failures and seven Gemini 3.8 Flash failures therefore reflect a completion-token limit problem in this configuration, not a missing `position` in a completed response. At 2400 tokens, a separate two-repeat pass gave Luna 6/6 valid, median 7.86 s, cost $0.00663, and Gemini 3.8 Flash 6/6 valid, median 12.18 s, cost $0.04340. These smaller runs do not establish a production error rate or a reliable p95.

The first-pass text review found content limitations across models: responses sometimes described steady pace or hesitation when the payload had no timing evidence, or treated the learner's self-report about searching for words as observed fluency. Gemini 2.5 Flash sometimes selected a lower grammar band than the supplied overall B1; this needs expert review rather than automatic rejection. Gemini 3.5 Flash Lite likewise asserted an even pace on the mixed case, which had no timing signals, and inferred a limitation from the absence of complex structures. None of the models has yet been evaluated on the original failed DEV input or enough real onboarding cases to justify a rollout. Gemini 3.5 Flash Lite and Gemini 2.5 Flash are the speed/format candidates in this small run; changing the production model remains undecided.

Reproduce locally after securely supplying an OpenRouter key in ignored `ai-service/.env`:

```bash
cd ai-service
PYTHONPATH=. python3 evals/run_onboarding_review_models.py --live --repeats 3 --output /tmp/speaky-onboarding-model-results.json
```

The next evaluation should use anonymized real payloads, including the rejected DEV review input, and a blinded human review of language evidence. Compare valid-completion cost and full user wait before selecting a model. Whichever model is chosen, handle truncated responses and incomplete fields without losing the onboarding attempt.

## Primary and backup preflight (2026-10-02)

Added five synthetic edge cases to the runner: a learner self-repair, garbled recognition, connected simple speech, complex speech, and a case with no verified correction examples or timings. Ran three requests per case and model with the production `json_object` format and 1200 completion-token limit:

| Model | Valid / total | Median request time | OpenRouter cost / 15 requests |
| --- | ---: | ---: | ---: |
| `google/gemini-3.5-flash-lite` | 15/15 | 2.15 s | $0.01847 |
| `openai/gpt-4o-mini` | 12/15 | 3.12 s | $0.00420 |

All three `gpt-4o-mini` failures were the garbled case: syntactically valid JSON had `grammar: null` and `vocabulary: null` instead of the required skill objects. This reproduces the class of failure where JSON mode alone does not enforce the report contract. On that case, a separate probe using OpenRouter `response_format: {type: "json_schema", json_schema: {strict: true, ...}}`, provider `require_parameters: true`, and 1600 completion tokens yielded 3/3 locally valid reports from each model. This was a six-request compatibility probe, not a guarantee of future compliance; production must still check `finish_reason` and `parse_review` before saving a review.

Manual spot review found unsupported claims from both models. In the self-repair case, `gpt-4o-mini` described `I is` as an error despite the learner immediately saying `I am`; Gemini 3.5 Flash Lite implied a grammar shift from that same repair. Both described a steady speaking pace in cases with no timing signals. There is therefore no basis yet to declare either model safe for unreviewed scoring. Add explicit content constraints and a larger anonymized real-case evaluation before rollout. A structural retry or backup model fixes malformed fields, not these semantic mistakes.

## DEV saved-attempt preflight (2026-10-02)

Read two completed onboarding attempts from DEV Redis, including the attempt with 123.98 recorded seconds associated with the earlier invalid review. The extraction omitted chat/session IDs and used the saved transcripts, profile, CEFR/position, and computed fluency metrics. Verified correction examples were unavailable in the saved review input and were omitted, so this is not a byte-for-byte replay of the historical model request. The inputs were kept in a temporary artifact for the run and removed after evaluation; no transcript is committed.

With strict JSON Schema, `provider.require_parameters=true`, 1600 completion tokens, and three runs of each saved input per model:

| Model | Valid / total | Median request time | OpenRouter cost / six requests |
| --- | ---: | ---: | ---: |
| `google/gemini-3.5-flash-lite` | 6/6 | 1.95 s | $0.00780 |
| `openai/gpt-4o-mini` | 6/6 | 3.99 s | $0.00208 |

The real `OnboardingModel.compose_review` path also completed once on each saved input with Gemini 3.5 Flash Lite as primary and `gpt-4o-mini` configured as backup. This validates the structured-output transport and parser on these two attempts, not semantic scoring accuracy or a guaranteed model success rate. Spot review still found overly confident wording, so the model change should remain a DEV experiment until more real cases are reviewed.
