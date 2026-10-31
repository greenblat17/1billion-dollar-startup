# Independent full-conversation correction probe

Date: 2026-10-20. Synthetic eight-turn conversation only; no user records or secrets.
The probe ran through the configured PROD OpenRouter connection without changing
server files, app data, or deployment. This is one prompt and one run per model.

The earlier per-turn replay had six saved candidates and two expected missed local
edits: `made a photo` and `borrowed my laptop to`. The independent prompt supplied
all eight turns and the six already found local edits, and asked for only new,
meaning-preserving correction pairs. The script is
`ai-service/evals/probe_full_dialogue_discovery.py`.

- `openai/gpt-4o-mini`: 2.37 seconds. Found both expected missed edits, but also
  proposed a needless rewrite of an already natural phrase, duplicated an existing
  correction, and treated spoken repetitions as an error. The repetition proposal
  also had the wrong turn number. Five proposals total: two useful, three invalid.
- `anthropic/claude-haiku-5.5`: 3.71 seconds. Found the same two expected edits
  with no extra proposal. It labeled the photo collocation as `natural` rather
  than `word`; the pair itself was sound.
- `mistralai/mistral-small-2603`: request failed with `RateLimitError` in 0.75
  seconds. No correction-quality conclusion is possible.

The OpenRouter model catalog had 470 entries when checked. Searching model names
and descriptions for `grammar`, `proofread`, `language learning`, and related terms
found no model explicitly positioned as a spoken-English correction specialist.
These are general-purpose candidate models, not confirmed domain specialists.

This tiny synthetic probe establishes feasibility and shows why output validation
matters. It does not estimate recall, false-positive rate, latency distribution, or
cost on real conversations. Do not select a production model from this result.
The next step is the de-identified, blind real-conversation evaluation in
`ai_docs/plans/2026-10-20-final-correction-discovery-evaluation.md`.
