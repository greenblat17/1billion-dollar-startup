# Voice lesson

The learner enters an owned Trial, grants microphone access, and speaks with Sasha.

## Sub-features

- V1: Open an owned lesson from its Telegram button.
- V2: Grant microphone access and observe the call timer and audio exchange.
- V3: End the lesson and observe persisted result.

## How to get to it (user POV)

- In Telegram, ask Sasha to book a Trial, then tap `Начать урок` at the agreed time.

## Driving it with BB browser-automation

Preconditions: A test Telegram account, an owned active booking, signed launch data, DEV readiness, and authorization for paid model use. The local fixture supplies none of these.

- V1–V3: Mark blocked in the local run. For an authorized DEV run, follow `ai_docs/acceptance/remaining-ac-execution.md` and `ai_docs/integrations/agent-acceptance.md`; record Telegram action, Mini App state, server-side lesson ID/status, audio evidence, and final Foundation state.

## Gotchas

- Synthetic `agent_test` audio proves transport only; it does not prove human listening or Telegram delivery.
- Stop after HTTP 402. Do not retry paid turns without a new instruction.
- A browser fixture cannot stand in for signed admission or Realtime voice.
