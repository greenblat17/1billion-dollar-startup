# Telegram practice situations

## MVP interaction

After onboarding and a daily-goal choice, the persistent Telegram reply keyboard has two rows:
`🎙 Start call` and `🎭 Practice a situation`. Each row has one button. The first keeps the
existing free-conversation flow. The second sends an inline menu with `🎯 Job Interview`,
`💬 Talk to Your Manager`, `✍️ Custom Scenario`, and `← Back`.

The two presets immediately begin a practice call. Speaky plays the interviewer or the
manager, starting with a spoken question in character. Custom Scenario asks for a text
description of the situation, counterpart, and desired goal; English and Russian are both
accepted, up to 500 characters. The next text message starts the call. Back closes the
menu. The lower keyboard disappears during a call and returns after End call, as it does
for a free call. Direct voice continues to use the existing call path.

## State and behavior

The selected scenario is stored on the call record, so a failed opening can be retried
without losing the role and every later turn uses the same role. Scenario descriptions and
role-play turns are untrusted fictional content. They do not update the learner's personal
facts or the rolling free-conversation history. The call's own turns supply context during
role-play. Spoken replies stay in character; the existing correction cards and post-call
review remain the MVP feedback. Scenario content expires with other raw call content after
seven days.

Ktor holds the temporary custom-text prompt in process memory. A restart while waiting for
that text loses the prompt; the user can tap Practice a situation again. A live Telegram
acceptance check should cover keyboard widths, menu editing, preset and custom starts,
Back, voice turns, End call, and retry after opening failure.

## Implementation boundary

The internal `/internal/calls/start` request accepts optional `scenarioKind` (`job`,
`manager`, `custom`) and `scenarioDescription` for custom only. Calls with no scenario keep
the existing free-conversation behavior. The implementation does not add a scenario catalog
or a separate situation-performance score.
