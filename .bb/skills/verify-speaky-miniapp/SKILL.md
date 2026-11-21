---
name: verify-speaky-miniapp
description: Drive Speaky Mini App in an isolated browser to verify progress, meetings, and calendar UI after frontend changes; keep Telegram and live tutor acceptance separate.
---

# Verify Speaky Mini App

This verifies the shipped `mini-app/src` frontend in a real browser. The local harness substitutes responses at the existing `/miniapp/api` boundary. It does not prove Telegram signature validation, Foundation state, voice transport, model quality, or DEV delivery. Read [features/README.md](features/README.md) and the relevant feature before a run. Use the production paths in `ai_docs/acceptance/` for live acceptance when authorized.

## Launch

Use Node 22+ and Python 3. Run from the repository root. Keep the `serve` command in a live shell session. Pick a fresh run directory under `/tmp` and retain the printed URL, PID, and shell session ID. The helper builds the current Mini App into that directory, binds an OS-selected port on `127.0.0.1`, and creates only local fixtures. No seed database, secret, or Telegram account is needed.

```sh
RUN_DIR=$(mktemp -d /tmp/speaky-verify-XXXXXX)
rmdir "$RUN_DIR"
python3 .bb/skills/verify-speaky-miniapp/scripts/miniapp_harness.py serve --run-dir "$RUN_DIR"
```

Ready means the JSON line with `url`, `pid`, and `revision` appears. Open only that URL; never attach to a pre-existing instance. Do not reuse a run directory. The harness owns the exact build and API fixture for that run.

## Doctor

Before driving, and after any unexpected state, run this read-only check. It confirms the server PID and Git revision, then loads the Mini App HTML. A failure means stop; do not drive that instance.

```sh
python3 .bb/skills/verify-speaky-miniapp/scripts/miniapp_harness.py doctor --run-dir "$RUN_DIR"
```

## Drive

For My Progress, use the shipped Chrome CDP helper. It opens installed Chrome with a disposable profile, renders the real Mini App at 412×915, clicks `Обновить`, saves before/after screenshots and state, checks two API requests, then closes that exact Chrome process. Node 24+ is required. On this macOS host, Chrome needed `exec_command` with `sandbox_permissions: "require_escalated"`; do not bypass a rejected escalation.

```sh
node .bb/skills/verify-speaky-miniapp/scripts/drive-progress.mjs "$RUN_DIR"
```

For other mapped UI paths, use the BB `browser-automation` skill with `--backend local --headless` on the **same host** as the server. Get the host ID from `bb status --json`. Open one isolated browser session, retain its session ID, and navigate to the printed URL plus the route in the feature file. Take a fresh snapshot after each navigation or click. Use accessible names, not positions. If the BB plugin cannot install its runtime, record the exact failure and leave that feature blocked. In this run, its `dev-browser@1.0.0-rc.4` attestation verification failed, so the Chrome CDP helper proved My Progress instead.

```sh
bb browser-automation open --backend local --headless --machine HOST_ID --json
bb browser-automation run SESSION_ID --script 'const p=await browser.getPage("main"); await p.goto("RUN_URL/miniapp"); await p.snapshot()' --json
```

The local browser has no signed Telegram `initData`; the fixture demonstrates UI and request shape only. A real join must be driven through Telegram with owned lesson and account state.

## Evidence

Keep `instance.json`, `requests.jsonl`, browser snapshots, and screenshot copies in `$RUN_DIR`; copy screenshot files out of the browser session **before closing it**. Record the current Git revision and the feature ID. The API log redacts `initData` and records `initDataPresent`; never save signed Telegram data. A pass requires the user action, the visible result, and the corresponding API action in `requests.jsonl`. A static HTML response or a unit test alone is insufficient. Mark local fixture results separately from DEV and live Telegram acceptance. If a mapped journey needs signed auth or a paid provider, record it as blocked with the exact prerequisite; do not invent a pass or retry after HTTP 402.

## Cleanup

Close the exact BB browser session if one was opened. `drive-progress.mjs` closes its own Chrome process. Then stop only the PID recorded for this instance. On this host, stopping it also needed `exec_command` escalation.

```sh
python3 .bb/skills/verify-speaky-miniapp/scripts/miniapp_harness.py stop --run-dir "$RUN_DIR"
```

The `stop` command checks the instance before signalling its PID. Do not kill by process name. Keep `$RUN_DIR` as the proof artifact; remove only its `dist/` build after the server exits. Confirm the snapshots, screenshot, and `requests.jsonl` still exist. On a failed drive, run doctor again, capture the failure, and clean up this run before retrying in a new directory.

## Helpers

`scripts/miniapp_harness.py serve|doctor|stop --run-dir PATH` builds the frontend, serves the isolated API fixture, checks ownership, and stops the owned process. `scripts/drive-progress.mjs RUN_DIR` drives P1–P2 in Chrome and writes evidence. Their invocations are shown above.
