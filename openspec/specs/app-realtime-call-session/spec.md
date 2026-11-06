# app-realtime-call-session Specification

## Purpose
Describes the path of the Compose Multiplatform prototype: login by JWT, creation of the application
session, SDP negotiation and the media channel with OpenAI Realtime. Sources: `architecture.md`,
`ai_docs/integrations/2026-09-18-mobile-api.md`, `ai_docs/architecture/2026-09-20-webrtc-client.md`.

## Requirements

### Requirement: Session creation requires an authenticated user
The Ktor app API SHALL issue a JWT after registration or login and SHALL create the application session
only from a Bearer token. The session is stored in PostgreSQL with an id of the form `app-...`.

#### Scenario: Login then session
- **WHEN** the CMP client logs in and then posts a new session with the Bearer token
- **THEN** the user and the session are stored in PostgreSQL
- **AND** the client receives the sessionId

#### Scenario: Session without a token
- **WHEN** a session is requested without a valid JWT
- **THEN** the session is not created

### Requirement: SDP is exchanged through Ktor and the AI service
The SDP offer and answer SHALL pass through Ktor and the AI service, while the media stream after
negotiation goes directly between the CMP client and OpenAI Realtime.

#### Scenario: Offer and answer
- **WHEN** the client posts an SDP offer for a session
- **THEN** Ktor calls the AI service, the AI service creates the Realtime session and returns the SDP
  answer
- **AND** the answer reaches the client through Ktor

#### Scenario: Media flows after negotiation
- **WHEN** the SDP exchange is complete
- **THEN** audio goes directly between the client and OpenAI
- **AND** transcript events come back to the client

### Requirement: The two paths stay separate
The CMP client SHALL NOT call the clip API of the AI service, the Telegram bot SHALL NOT use the WebRTC
path of the application, and no public proxy for the clip API SHALL exist on Ktor.

#### Scenario: Telegram voice keeps its own path
- **WHEN** a Telegram voice message arrives
- **THEN** it is handled as an asynchronous clip job through the queue
- **AND** no Realtime session is created for it

#### Scenario: App path does not use the clip API
- **WHEN** the CMP client sends audio for a call
- **THEN** it does not call `/v1/clips`
- **AND** no public clip API route exists on Ktor
