# Per-chat Telegram voice speed

Status: implemented locally; AI tests and `:server:test` pass. DEV Telegram acceptance pending.

## Decision

`/speed` shows the chat's current Deepgram Aura-2 speaking rate and three buttons: slower `0.8`, comfortable `0.9`, normal `1.0`. The default is `TTS_SPEED` (`0.9`). A choice applies to future audio for that Telegram chat, including ordinary replies, clarification, greeting, and onboarding. It does not alter already generated Telegram voice messages. No LLM call is added.

Persist the override under `speech-speed:{sessionId}` in the AI Redis; use an in-memory fallback locally. Keep the preference independent of onboarding resets and dialogue TTL. The authenticated internal API reads and changes the preference; Kotlin only handles the Telegram command and buttons. A shared synthesis service resolves the session's rate and calls TTS, so the different dialogue paths use one rule. Cached greeting and onboarding intro audio are keyed by speed. The OpenRouter fallback ignores the rate because this feature controls direct Deepgram Aura-2.

Only the default-speed onboarding intro is prewarmed. The first greeting or intro requested at another speed can incur one synthesis delay; later users at that speed reuse the cached audio. Deploy AI before Ktor so the `/speed` command can reach the new internal endpoints.

## Validation

Test authenticated read/write, invalid values, per-session isolation and Redis persistence; assert the chosen speed reaches the Deepgram request on ordinary and onboarding paths, and that cached audio does not cross speeds. Test Telegram callback parsing and internal client requests. Run the AI test suite and `:server:test` from `cmp/`. Roll out AI before Ktor because the command depends on the new internal API.
