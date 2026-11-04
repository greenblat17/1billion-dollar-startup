# Production correction and review audit

Date: 2026-10-20. Read-only investigation of the PROD AI service. No user transcript,
identifier, provider key, or raw response is stored in this repository. Telegram call and
onboarding states were read within their seven-day raw-content window. Provider probes used
synthetic text. The inspected states and logs can include agent-initiated diagnostic calls;
they are not a statistically sampled user cohort.

## Review routing

The deployed primary review model is `google/gemini-3.5-flash-lite`, with
`openai/gpt-4o-mini` as fallback. Between October 14 and October 20, the available AI logs
contain 35 first-attempt review warnings with `PermissionDeniedError`. Provider-call records
show 35 HTTP 403 responses for Gemini and 37 successful `gpt-4o-mini` onboarding-purpose
requests over that interval. These counts include diagnostic activity and are not a
per-user failure rate. In the isolated synthetic replay, both onboarding and call review
needed the fallback.

An isolated synthetic request using the PROD OpenRouter key returned `Blocked by Google AI
Studio` with default routing. Pinning the Google Vertex route and omitting `temperature`
returned `Blocked by Google`; retaining `temperature` yielded no eligible endpoint. The
current [model endpoint listing](https://openrouter.ai/api/v1/models/google/gemini-3.5-flash-lite/endpoints)
advertises structured output on Google Vertex but does not list `temperature` for that
route. OpenRouter documents `provider.only`, `provider.ignore`, and `require_parameters` in
[provider selection](https://openrouter.ai/docs/guides/routing/provider-selection). The
observed PROD key cannot use either Google route for this model, so merely changing the
Google provider preference is not a demonstrated fix.

The working fallback produced completed reviews in the synthetic replay and the available
PROD logs. The local code now defaults the review model to `openai/gpt-4o-mini`; however,
PROD's explicit `ONBOARDING_REVIEW_MODEL=google/gemini-3.5-flash-lite` overrides that
default. The PROD environment blob must be changed to make `gpt-4o-mini` primary, then
redeployed and checked live. The fallback may remain the same model as a second attempt
with a larger token allowance. Update the `PROD_AI_SERVER_ENV` Actions secret as well as
the server; a subsequent deployment writes that secret back to the host.

## Actual correction stages

Within retained Telegram states, 67 reviewed closed calls held 314 turns, 116 saved
candidates (87 Grammar, 29 Vocabulary), and 39 final examples (29 Grammar, 10
Vocabulary). Twenty-one reviewed onboarding attempts held 138 turns, 56 saved candidates
(44 Grammar, 12 Vocabulary), and 42 final examples (34 Grammar, 8 Vocabulary). The final
can collapse repeated candidates and selects at most five per skill, so the difference
between candidate and example totals is not by itself a rejection rate.

Five reviewed calls with saved candidates had zero final examples. In three of them,
the verifier exceeded its 10-second deadline; logs say correction verification failed
and examples were omitted. Those three calls had 7, 20, and 37 turns, respectively,
with 8, 11, and 9 candidates. The 20-turn call had four Vocabulary candidates and zero
Vocabulary examples in its completed report. Two other zero-example calls had one turn
and one candidate each; their exact verifier decisions are not established here.

Using the same saved candidate batches in isolated, read-only probes,
`openai/gpt-4o-mini` returned verifier decisions in 1.98, 2.03, and 0.96 seconds. It
accepted 7, 7, and 7 candidates, including 1, 2, and 0 Vocabulary candidates. This
demonstrates that the timeout cases can complete within the current deadline with that
model. It does not establish the semantic quality of all accepted pairs or guarantee
the same latency under load.

## Prepared changes and release boundary

The repository now gives correction verification its own
`CORRECTION_VERIFICATION_MODEL`, defaulting to `openai/gpt-4o-mini`, shared by onboarding
and ordinary call review. It also makes the previously hardcoded Google model for
background personal fact extraction configurable as `PERSON_MEMORY_MODEL`, with the same
default; one isolated synthetic extraction succeeded on that model. This memory change
addresses another path that would select the blocked Google model, without claiming it
was the source of the observed review failures. Unit tests cover model routing and
configuration defaults.

No PROD deployment or environment-secret update was performed during this audit. Local
code, tests, and docs are prepared for review. After deployment, verify the primary
review calls no longer return 403, correction verification completes for long calls,
and actual examples remain linguistically sound. The earlier three-candidate removal
is also still a local, undeployed change.

The local instrumentation now counts final correction-check outcomes and records their
duration separately for onboarding and practice calls. Grafana plots 24-hour timeout
share and successful-check p50/p95. This is the evidence needed to revisit the 10-second
deadline after a real deployment; existing PROD timeouts are not backfilled.
