# telegram-voice-clip-pipeline Specification

## Purpose
Describes the behavior of the Telegram voice path: webhook intake, the per-chat clip queue, processing
through the AI service, and what the user receives in reply. Sources: `architecture.md`,
`ai_docs/summaries/2026-09-18-agent-start-here.md`, `ai_docs/integrations/2026-09-18-clip-session-api.md`.

## Requirements

### Requirement: Webhook acknowledgement
Ktor SHALL verify `X-Telegram-Bot-Api-Secret-Token` and answer Telegram after accepting an update,
processing that update in a separate scope.

#### Scenario: Accepted update is acknowledged before processing
- **WHEN** Telegram sends an update with the correct secret header
- **THEN** Ktor returns 200 immediately after accepting the update
- **AND** processing continues in a separate scope
- **AND** Telegram does not resend an already acknowledged message

### Requirement: Per-session clip queue
The queue SHALL limit concurrent processing to three clips per session and SHALL use the session id
`tg-<chatId>`.

#### Scenario: Three clips in flight
- **WHEN** three clips for the same chat are in flight or queued
- **THEN** the queue processes them without exceeding three concurrent
- **AND** each clip is handled by the queue of that session only

#### Scenario: Client polls the job until completion
- **WHEN** a clip job is accepted with 202 and a jobId
- **THEN** the client polls the job every 300 ms
- **AND** polling stops at completion or at the 90-second limit

### Requirement: Reply and corrections are generated independently
The AI service SHALL run the reply and the corrections in parallel, and speech synthesis SHALL start
after the reply text is ready without waiting for the correction.

#### Scenario: Corrections miss their deadline
- **WHEN** the correction request exceeds its 10-second deadline from request start
- **THEN** the request is cancelled
- **AND** the ready spoken reply is delivered without correction cards

#### Scenario: Corrections fail without losing the reply
- **WHEN** the correction result is malformed or generation fails
- **THEN** correction cards are omitted
- **AND** the voice reply is still delivered

#### Scenario: Speech is not recognized
- **WHEN** STT returns no recognized speech
- **THEN** the service produces a TTS request asking the user to repeat

### Requirement: Dialogue state persistence
The transcript and the reply text SHALL be saved to the dialogue history, while the clip audio stays in
the clip job memory.

#### Scenario: Redis configured
- **WHEN** `REDIS_URL` is set
- **THEN** dialogue history and user state are stored under the key `session:{id}`

#### Scenario: Redis not configured
- **WHEN** `REDIS_URL` is unset
- **THEN** the service keeps the dialogue state in memory instead of failing

### Requirement: Correction outcome metrics
Every live correction request SHALL be counted as one outcome per day in Moscow time and returned under
the key `corrections` in `/internal/metrics`.

#### Scenario: Outcome recorded per day
- **WHEN** a live correction request completes
- **THEN** the outcome is counted in `metrics:corrections:YYYY-MM-DD` with count, summed elapsedMs and
  secondAttempts
- **AND** the outcome label is one of the defined values such as `shown`, `empty`, `filtered`,
  `deadline`, `rate_limit`
- **AND** transcript text and provider response bodies are not logged
