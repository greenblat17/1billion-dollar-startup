# Vocabulary alternatives: synthetic model probes

Date: 2026-10-02. Model on DEV: `openai/gpt-5.6-luna`, temperature 0. The prompts came from the local working tree and were sent through the DEV AI host; this was an isolated evaluation, not a deployment or a Telegram run. All utterances in this suite are synthetic. Cases: [`ai-service/evals/vocabulary_alternatives.json`](../../ai-service/evals/vocabulary_alternatives.json).

## What was tested

The model generated the complete onboarding or call review JSON. We inspected `vocabularySuggestions`, then applied `select_vocabulary_suggestions` to check exact transcript grounding and correction overlap. Full per-case outputs are in [`vocabulary_alternatives_results_2026-10-02.json`](../../ai-service/evals/vocabulary_alternatives_results_2026-10-02.json). `optional` means a genuinely useful alternative would be welcome, not that the model must produce one. An empty result is acceptable. The runs used three concurrent requests; individual provider latency is not end-to-end bot latency.

| Case group | Strict prompt | Softer prompt | Observation |
| --- | --- | --- | --- |
| Plain correct speech; already natural idioms | `[]` | `[]` on the repeated case | No forced rewrite. |
| Photography projects in addition to a full-time job | `[]` | `in addition to my full-time job` → `alongside my full-time job` | Grounded but a fairly small change; the main quality risk of the softer wording. |
| Awkward `perfect case scenario` | `[]` | `[]` | Did not relabel an awkward original as a correct optional alternative. |
| Building a startup, explicitly not launched | `[]` | `[]` | No `build` → `launch` meaning shift. |
| Grammar error, ASR false start, style-only phrase | `[]` | `[]` for repeated cases | No filler suggestion. |
| Existing Vocabulary correction | `[]` | `[]` | Corrections kept priority. |
| Photography, startup, and correction cases in call review | `[]` | `[]` on the repeated cases | Same conservative behavior in the shorter call prompt. |
| Five extra idiom opportunities under the strict prompt | One result: `finish the job` → `get the job done`; four `[]` | Not run | The result is a reusable conversational expression, though modest in value. |
| Two extra positive probes under the softer prompt | Not run | Both `[]` | The softer wording did not fill the quota. |

Strict prompt: **1 of 18** synthetic cases produced a suggestion that passed the application filter. Softer prompt: **1 of 10** selected cases. All requests completed without JSON or provider errors. Median provider latency was about 10.7 seconds for the strict set and 12.5 seconds for the softer comparison; these small, concurrent samples do not establish a latency difference.

## Decision and limit

The user chose the softer wording after seeing the comparison. It frames suggestions as optional learning opportunities even when the original is already correct, while retaining the instruction to return `[]` for weak synonyms, errors, or meaning shifts. The tested `alongside` suggestion shows that the model can still make a change whose teaching value is debatable. The deterministic filter establishes grounding and excludes correction overlap; it cannot judge whether a fluent speaker would actually prefer the alternative. Review live Telegram outputs after deployment before treating the feature as consistently high quality.
