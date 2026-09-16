# speaking-coach-application

Git root is this directory. **Gradle root is `cmp/`**. Run `./gradlew` from `cmp/`, not from the repo root.

Desktop UI: MCP `compose-hot-reload`. If the window is down, start `./gradlew :app:desktopApp:hotRun --auto` from `cmp/` in the background, then ask the user to respawn MCP: OpenCode → new session; Cursor → toggle `compose-hot-reload` in Settings → Tools & MCP (a new chat does not restart MCP). Shared UI is `cmp/app/shared/.../App.kt`. Cursor Gradle: `GRADLE_USER_HOME=$HOME/.gradle`. Recomposition traces: skill `compose-stability-analyzer` (`@TraceRecomposition` on `App`, desktop `ChrRecompositionLogger`, then MCP `get_logs`). Do not add `org.jetbrains.compose.hot-reload` to the catalog as a plugin.

KMP libraries: MCP `klibs`, skill `kmp-libraries-expert` from [JetBrains/klibs-io](https://github.com/JetBrains/klibs-io). Tools: `searchProjects`, `getLatestVersion`. Do not guess Maven versions or target matrices.

OpenCode (`opencode.json`) and Codex (`.codex/config.toml`) start `./gradlew :app:desktopApp:hotMcpServer` with `cwd` `cmp`. Cursor (`.cursor/mcp.json`) uses `.agents/skills/compose-hot-reload/scripts/hot-mcp-server.sh` only to force a real `GRADLE_USER_HOME`.
