---
name: cmp-kermit
description: >
  Log from Kotlin Multiplatform shared code with Touchlab Kermit (injected
  Logger + tag, platformLogWriter, kermit-koin). Use when adding Logger,
  log.i, println, android.util.Log, SLF4J in commonMain, or Koin’s
  printLogger. Skip :server — that is already SLF4J + Logback. Do not log
  tokens, clip bytes, or other secrets. Do not use the global Logger as
  the default in new types (harder to test).
---

# CMP Kermit

Shared (`:app:shared` / `:core`) logging is **Kermit**. Catalog already has `kermit`, `kermit-koin`, `kermit-test`. Docs: [Logger setup](https://kermit.touchlab.co/docs/configuration/LOGGER_SETUP), [Koin](https://kermit.touchlab.co/docs/extensions/KOIN), [Testing](https://kermit.touchlab.co/docs/TESTING). Source: [touchlab/Kermit](https://github.com/touchlab/Kermit). Versions: MCP `klibs`.

`:server` keeps Logback / SLF4J (and Telegram redaction). Do not replace that with Kermit unless asked.

## Call site

Message lambdas are skipped when the severity is below the minimum — prefer them over eager strings:

```kotlin
logger.i { "session started" }
logger.e(throwable) { "clip upload failed" }
```

API is `v` / `d` / `i` / `w` / `e` / `a` on `co.touchlab.kermit.Logger` ([`Logger.kt`](https://github.com/touchlab/Kermit/blob/main/kermit/src/commonMain/kotlin/co/touchlab/kermit/Logger.kt)). Severity enum: `Verbose`, `Debug`, `Info`, `Warn`, `Error`, `Assert`.

Tag-first overloads (`logger.i("MyTag") { }`) are **deprecated** — use `tag =` or `withTag`.

Do not call `println`, `android.util.Log`, `java.util.logging`, or SLF4J from `commonMain`.

## Inject, do not default to global

Kermit’s own setup doc: inject a `Logger` with the tag applied at the injection point, rather than `Logger.i { }` everywhere. Global `Logger` companion exists (`setMinSeverity`, `setLogWriters`, `setTag`, `withTag`) but unit tests then fight shared static config.

Create a base logger once, then tag per type:

```kotlin
val baseLogger = Logger(
    loggerConfigInit(platformLogWriter()),
    "SpeakingCoach",
)
```

`platformLogWriter()` is `expect`/`actual` (Logcat / system / Xcode / console). Call it from common code.

**Koin** (`co.touchlab.kermit.koin`):

```kotlin
startKoin {
    logger(KermitKoinLogger(Logger.withTag("koin")))
    modules(
        kermitLoggerModule(baseLogger),
        module {
            single { ClipRepository(getLoggerWithTag("ClipRepository")) }
        },
    )
}
```

`getLoggerWithTag` is a `Scope` extension (`factory` / `single` lambda). `kermitLoggerModule` is a `factory { (tag: String?) -> baseLogger.withTag(tag) }`. `KermitKoinLogger` implements Koin’s `org.koin.core.logger.Logger` and maps DEBUG/INFO/WARNING/ERROR onto Kermit (`NONE` is a no-op). There is no `getWith("tag")` in Kermit — that name is from other samples.

## Tests

`kermit-test` is `@ExperimentalKermitApi` (unstable). Opt in. Inject the test logger; do not rely on Logcat (Android host unit tests cannot use the default Logcat writer).

```kotlin
@OptIn(ExperimentalKermitApi::class)
class ClipRepositoryTest {
    private val writer = TestLogWriter(loggable = Severity.Verbose)
    private val logger = Logger(
        TestConfig(minSeverity = Severity.Debug, logWriterList = listOf(writer)),
    )

    @Test
    fun logsFailure() {
        writer.assertCount(1)
        writer.assertLast { message == "clip upload failed" && severity == Severity.Error }
    }
}
```

`assertCount` / `assertLast` / `logs` / `reset` live on `TestLogWriter`. Assertions inside `assertLast` are `kotlin.test.assertTrue` on the lambda (skill `cmp-test`).

## Do not

- Log bot tokens, auth headers, raw clip/audio bytes, or full PII. Server already redacts Telegram tokens — same rule in shared logs.
- `kermit-io` rolling files, Crashlytics/Bugsnag writers, or the Kermit IR strip plugin unless asked.
- `printLogger()` as the long-term Koin logger (use `KermitKoinLogger`).
- Timber, Napier, or `kotlin-logging` in commonMain.
