# My Progress

The learner opens a roadmap from Telegram and can refresh it without starting a voice lesson.

## Sub-features

- P1: Open My Progress and show the goal and completed/current/upcoming stages.
- P2: Refresh the view and request current progress again.
- P3: Signed Telegram entry and persisted roadmap are live acceptance, not a local fixture pass.

## How to get to it (user POV)

- In Telegram, tap «Мой прогресс».
- For local frontend verification, open `/miniapp?view=progress` on the run URL.

## Driving it with Chrome CDP

Preconditions: Launch and doctor the isolated instance. Run `node .bb/skills/verify-speaky-miniapp/scripts/drive-progress.mjs "$RUN_DIR"` with Node 24+. Chrome must be installed. It uses its own disposable profile and saves evidence under `$RUN_DIR/evidence/`.

- P1: The helper navigates to `RUN_URL/miniapp?view=progress`, saves `before.json` and `before.png`, and requires the goal `Уверенно говорить на рабочих встречах` with three stages. Check `requests.jsonl` has `{"action":"progress","initDataPresent":false}`.
- P2: The helper clicks `Обновить`, saves `after.json` and `after.png`, and requires the same goal and stages with a second `progress` line in `requests.jsonl`.
- P3: Mark blocked locally. A real Telegram button, signed initData, AI projection, and Foundation state are required to prove this path.

## Gotchas

- Browser `?view=progress` exercises the actual frontend route but bypasses Telegram's button and signed identity.
- A fixture `ready` state does not prove the student's roadmap was stored or delivered.
- Progress must not start a microphone or WebSocket. An unexpected request or permission prompt is a failure.
