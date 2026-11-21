# Calendar

The learner sees the saved lesson slot and can download a calendar event.

## Sub-features

- C1: Show the lesson's local time and 30-minute duration.
- C2: Download the `.ics` file.

## How to get to it (user POV)

- Tap the calendar link in the Telegram lesson card.
- For local frontend verification, open `/miniapp?view=calendar&lesson=verify-lesson`.

## Driving it with BB browser-automation

Preconditions: Launch and doctor the isolated instance; use `verify-lesson`.

- C1: Navigate to `RUN_URL/miniapp?view=calendar&lesson=verify-lesson`. Snapshot the page. Require `Ваше занятие`, `Встреча с Сашей`, `30 минут`, and `UTC+03:00`. Check `requests.jsonl` contains `{"action":"calendar","lessonId":"verify-lesson"}`.
- C2: Use a fresh snapshot ref for `Apple / другой календарь · .ics`; click it. Capture the downloaded file and require `BEGIN:VCALENDAR`.

## Gotchas

- The fixture's `.ics` is minimal. This path proves the download control, not the backend's full calendar payload.
- A real Telegram card and stored lesson time need separate live evidence.
