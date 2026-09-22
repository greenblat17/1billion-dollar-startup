---
name: compose-hot-reload
description: "Drive the running Compose Multiplatform desktop JVM UI via compose-hot-reload MCP (screenshot, semantic tree, click/type/scroll, reload, logs). Default window is Pixel 8 412×915 via resize_window; use a desktop size only when the task is explicitly desktop. After UI interaction or a skip/stability edit, read get_logs for [Recomposition] to see why a composable ran. New widget / SandboxHost / TableWidget: skill compose-widget-sandbox. Use when editing or verifying Compose UI on desktop, debugging extra recompositions, or checking that a parent skipped. Skip Android emulator, iOS, web-only, or projects with no JVM/desktop window."
---

# Compose Hot Reload MCP

Desktop/JVM only. Tools are on MCP server `compose-hot-reload`.

Skip: no desktop/JVM app window; user asked for emulator/iOS/web. Do not invent a desktop module.

CMP 1.12 already applies Hot Reload 1.2 (`hotMcpServer`). Do not add `org.jetbrains.compose.hot-reload` to the catalog.

Gradle root = directory with `settings.gradle.kts` (this repo: `cmp/`). Pick the desktop app module from `include(...)`.

| Module | App | MCP |
| --- | --- | --- |
| Kotlin/JVM | `hotRun` | `hotMcpServer` |
| KMP `jvm()` | `hotRunJvm` | `hotMcpServerJvm` |
| KMP `jvm("desktop")` | `hotRunDesktop` | `hotMcpServerDesktop` |

If `hotMcpServer` is ambiguous, point MCP at `:module:hotMcpServer` / `hotMcpServerJvm`.

OpenCode and Codex start `./gradlew --no-daemon --quiet --console=plain :<desktopModule>:hotMcpServer` from the Gradle root. Do not wrap those clients in `scripts/hot-mcp-server.sh`. That script is Cursor-only: the agent sandbox can inject an empty `GRADLE_USER_HOME`.

Tool list below is from JetBrains Compose Hot Reload README (`hot-reload-mcp`). There is no upstream `SKILL.md`.

## Session

MCP attaches at session start. Starting `hotRun` mid-chat does not connect it.

If the desktop app is not running: start `./gradlew :<desktopModule>:hotRun --auto` **in the background** from the Gradle root, then tell the user how to respawn MCP (do not keep calling MCP tools in this chat):

- **OpenCode:** start a new session (MCP respawns with the chat).
- **Cursor:** a new Agent chat is **not** enough. Toggle `compose-hot-reload` off/on in **Settings → Tools & MCP** (or Customize). If it stays dead: Command Palette → **Developer: Reload Window**, or quit Cursor.

The agent starts hotRun; the user respawns MCP.

Cursor agent `./gradlew` may use an empty sandbox `GRADLE_USER_HOME`. Export `GRADLE_USER_HOME=$HOME/.gradle` first.

## Loop

`status` first. `list_windows`, then `resize_window` to the viewport in Viewport below. `buildContinuous` true (`--auto`) → `await_reload` after edits; otherwise `reload`. If the tool returns `"reloading"`, poll `status` until `reloadState` is no longer `reloading`. Then `take_screenshot` + `get_semantic_tree`. After `click` / `type_text` (or a skip fix), also `get_logs` and grep `[Recomposition` — see Recomposition below. Act by `nodeId`. Failures: `get_ui_error`, `get_logs`. Several windows: `list_windows` + `window_id`.

| Tool | Use |
| --- | --- |
| `status` | Connected?, `buildContinuous`, reload state, last error, windows |
| `reload` | Recompile and hot-reload (no `--auto`) |
| `await_reload` | Wait for autonomous reload (`--auto`) |
| `list_windows` | `id`, title, bounds |
| `get_ui_error` | Exception while a window renders |
| `get_logs` | Recent app log lines (`limit`, default 200) |
| `take_screenshot` | Window screenshot |
| `get_semantic_tree` | Roles, names, states, bounds, actions |
| `click` | Node must expose `onClick` |
| `long_click` | Node must expose `onLongClick` |
| `type_text` | Node must expose `editableText` |
| `scroll` | Node must support `ScrollBy` (`deltaX` / `deltaY`) |
| `scroll_to_index` | Lazy lists; node must support `ScrollToIndex` |
| `resize_window` | Width × height in pixels. Default: Pixel 8 `412×915`. Desktop size only if the task is explicitly desktop. |
| `restart` | Restart the app; poll `status` if `reconnected` is false |
| `reset_ui` | Drop `remember` state and recompose |

Window-targeting tools take optional `window_id`. Omit it → first registered window.

## Viewport

`hotRun` is a JVM window hosting **shared** UI. This product's UI is mobile. Treat the window as a phone unless the user explicitly asked for desktop (windowed layout, wide pane, "desktop", "десктоп").

After `status` / `list_windows`, if size is not already the target, `resize_window` **before** screenshot or clicks.

| Case | Size (px) |
| --- | --- |
| Default, mobile UI, unspecified target | **412×915** (Pixel 8 viewport; also Pixel 8a / Pixel 9 class) |
| User named a device / size | That size |
| Task is explicitly desktop | **1280×800** |

Do not use 360×800 as the default: that is the statistical budget-Android bucket, not a Pixel. Compose `@Preview` phone / Chrome "Pixel 8" is 412×915.

Do not leave the default maximized/wide `hotRun` window when verifying mobile UI. Compact-width bugs (wrap, overflow, tap targets) are invisible at 960+.

New widget from scratch (file in shared, `SandboxHost`, both themes, then IDE `@Preview`): skill `compose-widget-sandbox`. This skill only drives the window.

## Recomposition

A screenshot proves pixels. `[Recomposition` in `get_logs` proves *why* Compose ran. After a state-changing `click` / `type_text`, or after an edit meant to skip work, do both. A correct-looking frame can still mean the whole tree ran.

This repo already annotates `App` (`@TraceRecomposition`, `traceStates = true`) and pipes events through desktop `ChrRecompositionLogger` into CHR, so MCP `get_logs` sees them. Grep `[Recomposition`. Raise `limit` if the tail is only build noise.

Install, annotation API, CHR bridge: skill `compose-stability-analyzer`.

**When traces are the evidence**

- Interaction: the tagged composable should re-run with the intended `[state] … changed` (or `[param] … changed`).
- Skip/stability fix: the parent must *not* appear in new traces; remaining params stay `stable`.
- "Nothing happened": no new `[Recomposition` → the click did not write state, or the annotated function skipped because it does not *read* that state.
- Jank / extra work: a parent or sibling fired, or a line says `unstable`.

**Read a line**

- `changed` — this is why it ran.
- `unstable` — skip is unlikely; fix the type or the lambda, do not ignore it.
- `stable` — not the cause.
- Missing after a click that *did* change UI — annotation is on a composable that did not re-execute. State nested in `MaterialTheme { }` (or another child lambda) is invisible to `@TraceRecomposition` on the parent. Put the state, or the annotation, on the function that reads it.

**Do not** treat a good screenshot as proof the tree was quiet. Do not annotate every child or enable `traceAll`. A new `@TraceRecomposition` or plugin apply is a compiler-plugin change: **restart** `hotRun`, `await_reload` is not enough.
