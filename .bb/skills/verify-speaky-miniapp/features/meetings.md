# Meetings

The learner sees planned lessons and can choose an available lesson.

## Sub-features

- M1: Show a scheduled Trial with time and an `Открыть урок` action.
- M2: Open a lesson through signed Telegram identity.

## How to get to it (user POV)

- Open the Mini App from the Telegram chat without a specific view parameter.
- For local frontend verification, open `/miniapp` on the run URL.

## Driving it with BB browser-automation

Preconditions: Launch and doctor the isolated instance; the fixture contains `verify-lesson`.

- M1: Navigate to `RUN_URL/miniapp`. Snapshot the page. Require `Ваши встречи`, `Пробный урок`, and `Открыть урок`. Check `requests.jsonl` contains action `list`.
- M2: Use a fresh snapshot ref to click `Открыть урок`. Without signed initData, require the visible `Откройте урок из Telegram` recovery. Record this as blocked for real joining; the fixture cannot establish a call.

## Gotchas

- Do not call a visible `Открыть урок` button a successful join.
- Real lesson admission, microphone, and voice WebSocket require a signed Telegram launch and owned booking.
