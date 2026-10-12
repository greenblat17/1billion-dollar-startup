# Onboarding assessment completion limit

Status: implemented locally; 297 AI-service tests passed. DEV and live Telegram
validation are pending. No deployment is included in this change.

An onboarding turn failed on 2026-10-08 when the next-question assessment on
`openai/gpt-5.6-luna` returned `finish_reason=length`. This operation inherited the
shared 500 completion-token limit. The accepted transcript was saved before the
failure and an explicit Telegram Retry completed the same turn. The 2400/3200
limits for the final review are a separate operation.

Keep the current effective model reasoning setting (`medium` by default): the
assessment extracts profile facts, estimates CEFR and writes a grounded next
question, so changing reasoning effort requires a comparison on identical turns.

Implementation:

1. Give only the next-question assessment a 1200 completion-token limit.
2. On `finish_reason=length`, repeat that assessment once with a 2000-token
   limit using the same saved turns. Reject truncated output on both attempts;
   do not retry malformed JSON or other failures through this path. No STT,
   notes, or TTS work is repeated by the assessment retry.
3. Log the assessment operation, session, model, limit, finish reason, prompt
   and completion usage, and reasoning-token usage when returned by the provider.
   Do not log prompts, transcripts, generated text, credentials, or raw provider
   responses. Keep aggregate metrics unchanged.

Verify that the first-attempt success makes one assessment request, a length
result makes exactly one higher-limit request, and two length results still
preserve the existing saved-turn Retry path. Check that omitted reasoning effort
remains omitted on both requests. Validate latency and retry rate on DEV before
any production deployment.
