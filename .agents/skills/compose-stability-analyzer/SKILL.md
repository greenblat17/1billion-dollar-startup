---
name: compose-stability-analyzer
description: >
  Instrument Compose Multiplatform UI with compose-stability-analyzer
  (@TraceRecomposition, plugin 0.14.0, desktop ChrRecompositionLogger). Use when
  adding the analyzer, annotating a screen, or traces are missing from Hot Reload
  MCP get_logs. For the click → get_logs verify loop, also read compose-hot-reload.
  Skip without Compose UI. Do not install the IntelliJ gutter plugin as a substitute.
  Do not enable trace-all by default.
---

# Compose Stability Analyzer (CMP)

Runtime compiler plugin, not the IDE marketplace plugin. Pair with `compose-hot-reload` on desktop.

Skip: no Compose UI module; user asked only for Android Studio gutter icons / heatmap.

## Kit gates (CMP)

- Apply the Gradle plugin on the **Compose UI** module (`shared` / `composeApp`), next to `org.jetbrains.kotlin.plugin.compose`. Not `:core`, not the Ktor server, not a thin Android Activity module.
- Shared UI stays `org.jetbrains.compose` + CMP Material3. This plugin does not justify `androidx.compose.*` in `commonMain`.
- Plugin Kotlin must match the project Kotlin (compiler internals). Catalog version, not a README guess. This repo: **0.14.0** with Kotlin **2.4.20**.
- Do not add `org.jetbrains.compose.hot-reload` to the catalog as a plugin. Do not turn on `traceAll` unless the user asked for module-wide noise.

## Install

Catalog `[plugins]`:

```toml
stabilityAnalyzer = { id = "com.github.skydoves.compose.stability.analyzer", version.ref = "stabilityAnalyzer" }
```

Root `build.gradle.kts`: `alias(libs.plugins.stabilityAnalyzer) apply false`.

Compose UI module: `alias(libs.plugins.stabilityAnalyzer)`. The plugin puts `compose-stability-runtime` on `commonMain`. Do not declare that artifact by hand unless resolution fails.

## Enable + annotate

Logging is a no-op until `ComposeStabilityAnalyzer.setEnabled(true)` (call once at UI start, e.g. the root composable or desktop `main`). Release/production: `setEnabled(false)`.

```kotlin
@TraceRecomposition(tag = "sandbox", traceStates = true)
@Composable
fun Screen(state: UiState) { /* ... */ }
```

- `tag` — filter in logs.
- `threshold` — skip the first N compositions (default 1). Use 2–3 to hide initial setup.
- `traceStates = true` — log `mutableStateOf` / `derivedStateOf` (`[state]`), not only params. JVM/Android also append `← method (File.kt:line)` on state writes.

Annotate the screens you are debugging, not every child. The annotation only logs when **that** function re-executes. State must be read in the annotated body (not only inside `MaterialTheme { }` / another child lambda), or the parent skips and `get_logs` stays silent.

`@IgnoreStabilityReport` is for previews/debug-only composables you do not want in `stabilityDump`.

## Desktop MCP bridge

Default JVM logger is `println` and does **not** reach Hot Reload MCP `get_logs` (`main.chr.log`). This project installs `ChrRecompositionLogger` in desktop `main` so each `[Recomposition` line goes through CHR `createLogger` (`AgentLoggerDispatch`) and shows up in `get_logs`. Dispatch is loaded from the **system** classloader; if none (not `hotRun`), it falls back to `println`.

Do not append to `main.chr.log` with a second file writer.

## Read logs

Line shape:

```
[Recomposition #2] App (tag: sandbox) (0.15ms)
  ├─ [param] title: String stable (...)
  ├─ [state] showContent: Boolean changed (false → true) ← onClick (App.kt:34)
  └─ State changes: [showContent]
```

`changed` = why it ran. `unstable` = skip is unlikely. `stable` = not the cause.

MCP interaction loop (click → `get_logs` → decide if the tree was quiet): skill `compose-hot-reload`.

If `[Recomposition` is missing from `get_logs` after a real UI change: not `hotRun` (fallback `println` / stdout), `setEnabled(false)`, annotation on a skipped parent, or plugin/annotation added without **restarting** `hotRun`.

## Stability files (optional)

After compile: `./gradlew :<uiModule>:stabilityDump` then `stabilityCheck`. Commit `*.stability` only if the project already uses that CI contract. Not required for the Hot Reload loop.

This repo unhooks `:app:shared:stabilityCheck` from `check` and disables dump/check: plugin 0.14.0 + AGP KMP library (`compileAndroidMain`) fails Gradle 9 implicit-dependency validation. Do not re-attach it. Keep the plugin for `@TraceRecomposition`.
