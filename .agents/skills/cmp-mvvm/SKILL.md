---
name: cmp-mvvm
description: >
  Structure Compose Multiplatform UI as MVVM + UDF in :app:shared (dumb widgets,
  Screen + ViewModel + UiState, Koin, Navigation 3). Use when adding a screen,
  ViewModel, UiState, Koin module, Nav3 back stack, or copying nowinandroid /
  Hilt / Android Navigation Maven. Skip a widget-only sandbox (no screen) — that is
  compose-widget-sandbox. Skip Ktor action/service splits — that is code-structure.
  Do not put Android-only nowinandroid stack in commonMain.
---

# CMP MVVM

Target architecture for shared Compose UI. Debug loop: skills `compose-widget-sandbox` and `compose-hot-reload`. Library versions: MCP `klibs` / skill `kmp-libraries-expert` — do not guess Maven coordinates.

Do not confuse with `code-structure` (Ktor actions vs services).

Ideas (UDF, layers, ViewModel as state holder): [nowinandroid Architecture Learning Journey](https://github.com/android/nowinandroid/blob/main/docs/ArchitectureLearningJourney.md). Copy the **ideas**, not the Android stack. Current NIA UI is already Navigation **3** + Hilt — still do not copy its Maven coords or DI.

Nav3 APIs in CMP: [compose-multiplatform](https://github.com/JetBrains/compose-multiplatform) `examples/imageviewer` (`NavKey`, `rememberNavBackStack`, `NavDisplay`, `entryProvider`). That sample is Material **2** and has **no** ViewModel — copy navigation only. ViewModel + `StateFlow` is `examples/nav_cupcake` (`OrderViewModel`) but that app is still Navigation **2**. Koin is this project's DI, not a JetBrains sample.

**Maven ≠ import.** Gradle: `org.jetbrains.androidx.lifecycle`, `org.jetbrains.androidx.navigation3`, `org.jetbrains.compose`. Kotlin: `androidx.lifecycle.ViewModel`, `androidx.navigation3.*`, `androidx.compose.*`. Do not rewrite imports to `org.jetbrains.androidx.lifecycle.ViewModel`.

## Layers

```text
Widget (dumb UI)  <-  Screen (collect StateFlow, lambdas)
                         ^
                    ViewModel : androidx.lifecycle.ViewModel
                         ^
              Repository / optional UseCase
```

- **Widget** — no ViewModel, no repository. Colors / type from `MaterialTheme` (skill `cmp-theme`). Copy: skill `cmp-strings`. Skill `compose-widget-sandbox`. Icons: skill `cmp-icons`.
- **Screen** — Nav3 `entry` + `koinViewModel()` (or `koin-compose-navigation3`). `collectAsStateWithLifecycle()` (`androidx.lifecycle.compose`; this module already has `lifecycle-runtime-compose`). Official samples often use `collectAsState()` — prefer WithLifecycle here. User events are lambdas into the ViewModel. No business logic.
- **ViewModel** — subclass `androidx.lifecycle.ViewModel` (JetBrains Maven artifact). One hot `StateFlow<UiState>` (`stateIn` / `MutableStateFlow`). Intents are methods, not a sealed event bus unless needed. Work in `viewModelScope`.
- **UiState** — immutable `data class` or sealed `Loading` / `Success` / `Error`. App data below the UI is the source of truth.
- **Repository** — public API of the data layer: read `Flow`, write `suspend`. UseCase (`operator fun invoke`) only when two or more repositories would be duplicated across ViewModels.

Packages live in `:app:shared` `commonMain` (`ui/`, ViewModel/UiState next to the screen). Do not clone nowinandroid’s `:feature:*:api/impl` module zoo (`:app:shared` + `:core` + `:server` is enough).

## DI: Koin (not Hilt)

`io.insert-koin`. Compile-time graph: `koin-annotations` / `koin-core-annotations` on the **same major line** as the compiler (query klibs). Do not pair annotations 4.x with leftover `koin-ksp-compiler` 2.3.x.

Compose: `koin-compose`, `koin-compose-viewmodel`. Nav3 entries: `koin-compose-navigation3`.

## Navigation 3

JetBrains artifacts: `org.jetbrains.androidx.navigation3:navigation3-*` (not Android `androidx.navigation3:*` on the classpath). Imports stay `androidx.navigation3.runtime` / `.ui`. ViewModel per entry: `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-navigation3` (keep in line with existing `lifecycle-viewmodel-compose`). No official sample uses that last artifact.

API: `NavKey`, `rememberNavBackStack`, `NavDisplay`, `entryProvider`. Not Navigation 2 / `navigation-compose` (`nav_cupcake`). imageviewer also uses `SavedStateConfiguration` + serializable `NavKey` subclasses to restore the back stack — copy that if the graph must survive process death; skip it for a first screen.

Pin versions with klibs at add time. JetBrains `navigation3-ui` may be beta while CMP’s compatibility table lists it — prefer that over mixing Android nav3 Maven in commonMain.

## Do not copy from nowinandroid

| nowinandroid | This project |
| --- | --- |
| Hilt, `@HiltViewModel` | Koin + annotations / compile-time |
| Nav3 Maven `androidx.navigation3:*` | JetBrains `org.jetbrains.androidx.navigation3:*` (same `androidx.navigation3` imports) |
| Lifecycle / Compose Maven `androidx.lifecycle` / `androidx.compose` in commonMain | `org.jetbrains.androidx.lifecycle:*`, `org.jetbrains.compose` (imports still `androidx.*`) |
| WorkManager, Room, Proto DataStore, Retrofit, OkHttp as defaults | No; HTTP client is Ktor |
| `:feature:*:api/impl` module zoo, `demo`/`prod` flavors, Roborazzi, FCM | No |

Lifecycle in this repo is already `org.jetbrains.androidx.lifecycle:*` on `commonMain`. Keep it that way.

## Skip

- Widget-only work in `SandboxHost` with no screen — `compose-widget-sandbox`.
- Duplicated Ktor operational logic — `code-structure`.
