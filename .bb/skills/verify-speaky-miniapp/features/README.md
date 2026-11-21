# Speaky Mini App verification map

This map covers the user-facing Mini App frontend. Local runs use a fixture at the AI API boundary. They prove browser rendering and the outbound action, not Telegram authentication or persisted learning state. Read the matching feature before driving. Keep every feature's result separate: pass, fail, or blocked with evidence and next step.

## Baseline

- Start a fresh instance with `../SKILL.md`; run doctor before opening a browser.
- Use the printed URL and a new local headless browser session on the same BB host.
- Use the fixture lesson ID `verify-lesson`. It has a future 30-minute slot.
- Store screenshots and snapshots in the run directory before closing the browser.
- Do not claim a Telegram or voice pass from the local fixture.

## Features

- [My Progress](progress.md): route entry, refresh, stage list, and read-only API action.
- [Meetings](meetings.md): scheduled Trial card and user entry to a lesson.
- [Calendar](calendar.md): saved lesson time and `.ics` action.
- [Voice lesson](voice-lesson.md): signed Telegram entry and real audio path; live prerequisite is explicit.
