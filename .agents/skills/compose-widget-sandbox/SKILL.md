---
name: compose-widget-sandbox
description: >
  Decompose a Compose screen into shared widgets, debug one widget in desktop
  SandboxHost with mocks (Pixel 8 MCP, both themes, recomposition traces), then
  add IDE @Preview and integrate into App(). Use when building a new widget,
  TableWidget, text field, or any isolated Compose control; when using
  SandboxHost or runSandbox; or when adding widget Previews. Skip when only
  editing an already-integrated screen — then compose-hot-reload on App().
  Do not copy the widget into a second desktop module. The agent cannot see
  Android Studio Preview or Interactive Preview.
---

# Compose widget sandbox

Widget lives once in `:app:shared`. Debug it in the same `:app:desktopApp` window (`SandboxHost`), not a second Gradle target and not IDE Preview.

MCP / viewport / `get_logs`: skill `compose-hot-reload`. Annotation install: skill `compose-stability-analyzer`. Screen + ViewModel + Nav3 + Koin: skill `cmp-mvvm`. Theme / fonts: skill `cmp-theme`. Strings: skill `cmp-strings`. Icons: skill `cmp-icons`.

Skip: tweak an already-wired screen with no new widget → `App()` + hot-reload MCP only.

## Pipeline

1. Decompose the screen. A tiny control (text field, chip) gets its own file if it owns state or slots.
2. New file in `cmp/app/shared/...`: pure Compose. State hoisted or `remember` for UI only. No repositories, no network. Colors and type from `MaterialTheme` only (skill `cmp-theme`) — otherwise dark theme lies. User-visible copy: skill `cmp-strings`. Icons: skill `cmp-icons`.
3. `runSandbox = true` in desktop `main.kt`. Put **only** that widget + mocks in `SandboxContent` (`SandboxHost.kt`). Do not copy the widget into desktop.
4. Same `hotRun` / MCP. Pixel **412×915**. Click, type, fill states. `@TraceRecomposition` on **the widget under test**, not every child. `get_logs` + `[Recomposition`.
5. Click sandbox chrome `Theme: light` / `Theme: dark`. Screenshot and interact again. Do not finish on light only.
6. `TableWidgetPreview.kt` (name = widget + `Preview`) next to the widget: `@Preview` light and dark, mocks **inside** the Preview so IDE Interactive works for the human. Do not treat Preview as agent verification. Do not ask the user to "check Interactive Preview".
7. Restore `SandboxContent` to the empty stub (leave the widget in shared). Wire it into the screen (`cmp-mvvm`: ViewModel + Nav3 + Koin). `runSandbox = false`. Final check in `App()`.

## Sandbox chrome

Theme toggle is **not** part of the widget. MCP clicks the button labeled `Theme: light` or `Theme: dark` — keep those chrome labels as literals (skill `cmp-strings`). Background is `colorScheme.background` so a dark screenshot is actually dark.

After widget work, `SandboxHost` must be the stub again so the next widget starts clean.

## Preview vs MCP

`@Preview` and Interactive Preview are IDE-only. The agent has no screenshot, click, or recomposition from that panel. Interactive for the agent is `SandboxHost` / `App()` + MCP.

## Do not

- Copy/delete the widget in a second desktop module
- Annotate every `@Composable` or enable `traceAll`
- Hardcode colors that ignore `ColorScheme`
- Leave `runSandbox = true` after integration
