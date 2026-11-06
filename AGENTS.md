# speaking-coach-application

Git root is this directory. **Gradle root is `cmp/`**. Run `./gradlew` from `cmp/`, not from the repo root.

Product context is `ai_docs/`. Before implementing or changing product behavior, UI, navigation, or backend contracts, read `ai_docs/README.md` and the linked docs for that task (`design/`, `architecture/`, `plans/`, `integrations/`, …). Copy recorded mockups and decisions; do not invent screens from the Compose template or from chat memory. Write new decisions back into `ai_docs`, not only the conversation.

KMP `implementation` vs `api`: `commonMain.implementation` is on all **targets of that module** (shared android/jvm/ios), not on host apps. Hosts (`androidApp`, `desktopApp`) compile against shared's **`api`** surface only — today `api(project(":core"))`. Compose / Koin / Ktor in shared stay `implementation`. If a host source file needs a library (desktop `SandboxHost` + Material3), add `implementation` on **that host**. Do not `api` the UI stack from shared to silence a host compile error.

Desktop UI: MCP `compose-hot-reload`. If the window is down, start `./gradlew :app:desktopApp:hotRun --auto` from `cmp/` in the background, then ask the user to respawn MCP: OpenCode → new session; Cursor → toggle `compose-hot-reload` in Settings → Tools & MCP (a new chat does not restart MCP). Shared UI is `cmp/app/shared/.../App.kt`. Cursor Gradle: `GRADLE_USER_HOME=$HOME/.gradle`. Viewport: `resize_window` **412×915** (Pixel 8) unless the task is explicitly desktop. After click/type, `get_logs` + `[Recomposition` is evidence (not only a screenshot). Plugin/annotation: skill `compose-stability-analyzer`. New widget: skill `compose-widget-sandbox` (`SandboxHost` + theme toggle `Theme: light`/`Theme: dark`; `runSandbox` in desktop `main.kt`; IDE `@Preview` is for the human — the agent cannot see it). Do not add `org.jetbrains.compose.hot-reload` to the catalog as a plugin.

KMP libraries: MCP `klibs`, skill `kmp-libraries-expert` from [JetBrains/klibs-io](https://github.com/JetBrains/klibs-io). Tools: `searchProjects`, `getLatestVersion`. Do not guess Maven versions or target matrices. Shared UI architecture: skill `cmp-mvvm` (MVVM, JetBrains Navigation 3, Koin — not Hilt / Navigation 3 / nowinandroid's Android stack). DI: skill `cmp-koin` (Koin compiler plugin + `KoinApplication` / `koinViewModel` — not Hilt or `koin-ksp-compiler`). Logging: skill `cmp-kermit` (Kermit in shared; `:server` stays SLF4J/Logback). Tests: skill `cmp-test` (`kotlin.test` class + `@Test` member — not Kotest / JUnit 5 in commonTest). HTTP from the CMP app: skill `cmp-ktor-client` (`ktor-client-core` without `-jvm`; catalog `ktor-*-jvm` is `:server` only — not Retrofit and not `project(":server")`). Theme and fonts: skill `cmp-theme` (nowinandroid `Color`/`Type`/`Theme` tokens in `:app:shared`, fonts in `composeResources/font` — not `res/font` or Android dynamic color). Strings: skill `cmp-strings` (`composeResources/values`, `stringResource` — not Kotlin literals or `androidApp/res/values`). Icons: skill `cmp-icons` (Material Symbols XML in `composeResources/drawable`, or ask the user for XML — not `material-icons-extended`).

OpenCode (`opencode.json`) and Codex (`.codex/config.toml`) start `./gradlew :app:desktopApp:hotMcpServer` with `cwd` `cmp`. Cursor (`.cursor/mcp.json`) uses `.agents/skills/compose-hot-reload/scripts/hot-mcp-server.sh` only to force a real `GRADLE_USER_HOME`.

<!-- skills-map -->
## Spec layer and skills

Two layers, no duplication of authority:

| Question | Who answers it | Where it lives |
| --- | --- | --- |
| What the system must do, and what is true today | OpenSpec specs | `openspec/specs/<capability>/spec.md`; a change folder holds the delta |
| Dated product and architecture decisions | `ai_docs/` | `plans/`, `integrations/`, `product/`, `researches/` — decisions are written here, not only in chat |
| How a specific area must be written | Project skills | `.agents/skills/cmp-*`, `code-structure`, `code-simplifier`, `thermo-nuclear-code-quality-review` |
| How much thinking a change needs | `openspec/config.yaml` rules + `operations` guidance | same file |

Reading order for any change: `ai_docs/README.md` → the dated doc for the area → the matching capability spec → the project skill for the area. The spec describes behavior; the skill prescribes the way to write it; `ai_docs` records why.

Which skill governs which area:

- Screen, ViewModel, UiState, navigation → `cmp-mvvm`, then `cmp-koin` for DI.
- HTTP client from the app → `cmp-ktor-client`.
- Tests → `cmp-test` (`kotlin.test`, run from `cmp/`).
- Logging in shared → `cmp-kermit`; `:server` keeps SLF4J/Logback.
- Theme, fonts, strings, icons → `cmp-theme`, `cmp-strings`, `cmp-icons`.
- Shared operational logic repeated across flows → `code-structure`.
- Readability pass on the diff → `code-simplifier`; strict maintainability review of the diff → `thermo-nuclear-code-quality-review`.
- Live UI verification → `compose-hot-reload` MCP with `[Recomposition]` logs, plus `compose-stability-analyzer` when traces are missing.

A `tasks.md` item must name the skill that governs it. If a task contradicts a project skill, the skill wins and the task is rewritten, not the skill.

<!-- /skills-map -->
