# speaking-coach-application

Git root is this directory. **Gradle root is `cmp/`**. Run `./gradlew` from `cmp/`, not from the repo root.

Desktop UI: MCP `compose-hot-reload`. If the window is down, start `./gradlew :app:desktopApp:hotRun --auto` from `cmp/` in the background, then ask the user to respawn MCP: OpenCode → new session; Cursor → toggle `compose-hot-reload` in Settings → Tools & MCP (a new chat does not restart MCP). Shared UI is `cmp/app/shared/.../App.kt`. Cursor Gradle: `GRADLE_USER_HOME=$HOME/.gradle`. Viewport: `resize_window` **412×915** (Pixel 8) unless the task is explicitly desktop. After click/type, `get_logs` + `[Recomposition` is evidence (not only a screenshot). Plugin/annotation: skill `compose-stability-analyzer`. New widget: skill `compose-widget-sandbox` (`SandboxHost` + theme toggle `Theme: light`/`Theme: dark`; `runSandbox` in desktop `main.kt`; IDE `@Preview` is for the human — the agent cannot see it). Do not add `org.jetbrains.compose.hot-reload` to the catalog as a plugin.

KMP libraries: MCP `klibs`, skill `kmp-libraries-expert` from [JetBrains/klibs-io](https://github.com/JetBrains/klibs-io). Tools: `searchProjects`, `getLatestVersion`. Do not guess Maven versions or target matrices. Shared UI architecture: skill `cmp-mvvm` (MVVM, JetBrains Navigation 3, Koin — not Hilt / Navigation 2 / nowinandroid’s Android stack). Icons: skill `cmp-icons` (Material Symbols XML in `composeResources/drawable`, or ask the user for XML — not `material-icons-extended`).

OpenCode (`opencode.json`) and Codex (`.codex/config.toml`) start `./gradlew :app:desktopApp:hotMcpServer` with `cwd` `cmp`. Cursor (`.cursor/mcp.json`) uses `.agents/skills/compose-hot-reload/scripts/hot-mcp-server.sh` only to force a real `GRADLE_USER_HOME`.
