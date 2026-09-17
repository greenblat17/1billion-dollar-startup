---
name: cmp-koin
description: >
  Wire Koin 4.x in :app:shared commonMain (compiler plugin, annotations or
  plugin DSL, KoinApplication + koinViewModel). Use when adding a Koin
  module, @Single / @KoinViewModel, startKoin, koinInject, or copying Hilt /
  Dagger / koin-ksp-compiler. Skip widget-only SandboxHost (pass mocks).
  Skip :server Ktor DI unless asked (that is not koin-ktor by default).
  Do not put Hilt or Android-only koin-androidx-* in commonMain.
---

# CMP Koin

DI for shared Compose UI. Architecture: skill `cmp-mvvm`. Logging into Koin: skill `cmp-kermit`. Tests: skill `cmp-test`. Versions: MCP `klibs` — catalog already has Koin + `io.insert-koin.compiler.plugin`. Docs: [Compiler Plugin Setup](https://insert-koin.io/docs/setup/compiler-plugin). Sources: [InsertKoinIO/koin](https://github.com/InsertKoinIO/koin), [InsertKoinIO/koin-compiler-plugin](https://github.com/InsertKoinIO/koin-compiler-plugin).

This module already applies the compiler plugin on `:app:shared`. Do **not** add `koin-ksp-compiler` (deprecated; do not pair annotations 4.x with KSP 2.3.x).

**Two different `KoinApplication` names.** Annotation `org.koin.core.annotation.KoinApplication` marks a bootstrap type for `startKoin<T>()` / `koinConfiguration<T>()`. Composable `org.koin.compose.KoinApplication` starts Koin in UI. Do not mix the imports.

## Start (CMP)

Hosts (`androidApp` / `desktopApp` / iOS) keep calling `App()`. Start Koin once around the shared tree:

```kotlin
KoinApplication(
    configuration = koinConfiguration {
        logger(KermitKoinLogger(Logger.withTag("koin")))
        modules(appModule)
        // or, with @KoinApplication on MyApp: module<AppModule>()
    },
) {
    App()
}
```

`KoinApplication(configuration: KoinConfiguration, logLevel, content)` is the current Compose entry ([`KoinApplication.kt`](https://github.com/InsertKoinIO/koin/blob/main/projects/compose/koin-compose/src/commonMain/kotlin/org/koin/compose/KoinApplication.kt)). It injects Android context / logger on Android and `printLogger` elsewhere — still set `logger(KermitKoinLogger(...))` so Koin goes through Kermit.

Compiler-plugin typed start (stubs in `org.koin.plugin.module.dsl`; IR fills modules):

```kotlin
@KoinApplication
@ComponentScan("com.eliteteam.speakingcoach")
class MyApp

startKoin<MyApp> { logger(KermitKoinLogger(Logger.withTag("koin"))) }
// Compose: KoinApplication(configuration = koinConfiguration<MyApp> { ... }) { App() }
```

`KoinContext` is deprecated — Compose context comes from `startKoin` / `KoinApplication`. Do not wrap `App()` in `KoinContext`. Previews / isolated sandbox that need a graph: `KoinApplicationPreview`. Dumb widgets in `SandboxHost` stay constructor/lambda mocks (skill `compose-widget-sandbox`).

## Definitions

Prefer the compiler plugin (already on shared).

**Annotations** (`org.koin.core.annotation` from `koin-annotations`):

```kotlin
@Single
class ClipRepository(private val api: ClipApi)

@KoinViewModel
class HomeViewModel(private val repository: ClipRepository) : ViewModel()

@Module
@ComponentScan("com.eliteteam.speakingcoach")
class AppModule
```

`@Single` and `@Singleton` are aliases. `@KoinViewModel` needs `koin-core-viewmodel` on the classpath (already catalogued). `@KoinWorker` is Android WorkManager — do not add it in commonMain. `koin-core-annotations` is `@InjectedParam` / `@Provided`, not a second `@Single`.

**Plugin DSL** — package **`org.koin.plugin.module.dsl`**, not classic `org.koin.dsl` constructors:

```kotlin
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single
import org.koin.plugin.module.dsl.factory
import org.koin.plugin.module.dsl.viewModel

val appModule = module {
    single<ClipRepository>()
    viewModel<HomeViewModel>()
}
```

Classic `single { Foo(get()) }` / `singleOf(::Foo)` / `viewModelOf(::HomeViewModel)` (`org.koin.core.module.dsl`) still work. Do not import deprecated `org.koin.compose.viewmodel.dsl.viewModel`.

Load a `@Module` class without `@KoinApplication`: `startKoin { module<AppModule>() }` or `modules(AppModule::class, DataModule::class)`.

## Compose

- Screen: `koinViewModel()` from `org.koin.compose.viewmodel` (needs `LocalViewModelStoreOwner` — Nav3 `entry` / `lifecycle-viewmodel-navigation3` provides it).
- Other types: `koinInject()`.
- Not `org.koin.androidx.compose.koinViewModel` (Android artifact).

Nav3: first screens can keep `entry<Home> { HomeScreen(viewModel = koinViewModel()) }`. `koinEntryProvider()` / `Module.navigation<Route>` in `koin-compose-navigation3` are `@KoinExperimentalAPI` — use only if the user asked to register destinations in Koin.

## Do not

| Wrong | This project |
| --- | --- |
| Hilt, `@HiltViewModel`, `@Inject` constructor as DI | Koin |
| `koin-ksp-compiler` / KSP processor | Compiler plugin `io.insert-koin.compiler.plugin` |
| `org.koin.androidx.*` in commonMain | `org.koin.compose.*`, `org.koin.compose.viewmodel` |
| `KoinContext { }` around `App()` | `KoinApplication(configuration = …)` or `startKoin` |
| `koin-ktor` on `:server` by default | Server stays as-is unless asked |
| Starting Koin in every host *and* in `App()` | One start |
| Widget sandbox resolving real graph | Pass mocks into the widget |
