---
name: compose-stability-analyzer
description: >
  Instrument Compose Multiplatform UI with compose-stability-analyzer so
  @TraceRecomposition logs parameter and state changes. Use with desktop Hot Reload
  MCP get_logs to see why a composable recomposed. Use when debugging extra
  recompositions, skippable/unstable params, or adding the analyzer plugin.
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

Annotate the screens you are debugging. Do not sprinkle every child. `@IgnoreStabilityReport` is for previews/debug-only composables you do not want in `stabilityDump`.

## Desktop MCP bridge

Default JVM logger is `println` and does **not** reach Hot Reload MCP `get_logs` (`main.chr.log`). This project installs `ChrRecompositionLogger` in desktop `main` so each `[Recomposition` line goes through CHR `createLogger` (`AgentLoggerDispatch`) and shows up in `get_logs`. Dispatch is loaded from the **system** classloader; if none (not `hotRun`), it falls back to `println`.

Do not append to `main.chr.log` with a second file writer.

## Read logs (agent loop)

Line shape:

```
[Recomposition #2] App (tag: sandbox) (0.15ms)
  ├─ [param] title: String stable (...)
  ├─ [state] showContent: Boolean changed (false → true) ← onClick (App.kt:34)
  └─ State changes: [showContent]
```

`changed` = this is why it recomposed. `unstable` = skip is unlikely. `stable` = not the cause.

With Hot Reload MCP (`compose-hot-reload`):

1. `status` / screenshot / semantic tree.
2. Interact (`click` / `type_text`).
3. `get_logs` (raise `limit` if needed) and grep `[Recomposition`.
4. If those lines are missing, they may still be on `hotRun` stdout.

Compiler-plugin edits (new annotation, plugin apply) need a **process restart** of `hotRun`, not only `await_reload`.

## Stability files (optional)

After compile: `./gradlew :<uiModule>:stabilityDump` then `stabilityCheck`. Commit `*.stability` only if the project already uses that CI contract. Not required for the Hot Reload loop.
