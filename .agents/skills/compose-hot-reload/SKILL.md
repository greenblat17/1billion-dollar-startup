---
name: compose-hot-reload
description: "Drive the running Compose Multiplatform desktop JVM UI via compose-hot-reload MCP (screenshot, semantic tree, click/type/scroll, reload, logs). Use when editing or verifying Compose UI on desktop. Skip Android emulator, iOS, web-only, or projects with no JVM/desktop window."
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

`status` first. `buildContinuous` true (`--auto`) → `await_reload` after edits; otherwise `reload`. If the tool returns `"reloading"`, poll `status` until `reloadState` is no longer `reloading`. Then `take_screenshot` + `get_semantic_tree`. Act by `nodeId`. Failures: `get_ui_error`, `get_logs`. Several windows: `list_windows` + `window_id`.

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
| `resize_window` | Width × height in pixels |
| `restart` | Restart the app; poll `status` if `reconnected` is false |
| `reset_ui` | Drop `remember` state and recompose |

Window-targeting tools take optional `window_id`. Omit it → first registered window.

Recomposition: skill `compose-stability-analyzer`. After a click, `get_logs` and look for `[Recomposition`. Annotate with `@TraceRecomposition`; `hotRun` must be restarted after adding the Gradle plugin or a new annotation (compiler plugin), not only hot-reloaded.
