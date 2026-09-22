---
name: cmp-ktor-client
description: >
  Add a Ktor HTTP client in :app:shared commonMain (ktor-client-core without
  a -jvm suffix, engines per source set, ContentNegotiation json). Use when
  calling :server from the CMP app, adding HttpClient, submitFormWithBinaryData,
  or copying Retrofit / OkHttp / catalog ktor-*-jvm into shared. Skip :server
  Ktor routes and action/service splits (code-structure). Do not depend on
  project(":server"). Do not put Apache/Jetty engines in commonMain.
---

# CMP Ktor client

Shared talks to **`:server` over HTTP**, same clip/session contract as Telegram. It does **not** Gradle-depend on `:server` and does not call `ai-service` directly. Server already has a JVM `HttpClient(CIO)` for that hop (`HttpClipClient`). Docs: [Ktor engines](https://ktor.io/docs/client-engines.html), [KMP client tutorial](https://ktor.io/docs/client-create-multiplatform-application.html). Source: [ktor](https://github.com/ktorio/ktor). Versions: one catalog `ktor` (today 3.5.2 on `:server`) + MCP `klibs` — do not bump the client to a newer Ktor than the server.

`:server` catalog aliases (`ktor-clientCore`, `ktor-clientCio`, …) are **`io.ktor:…-jvm`**. They are correct for the JVM `:server` module. They are **wrong** in `:app:shared` `commonMain` (iOS cannot resolve `-jvm`). Add **new** aliases without a platform suffix, same `version.ref = "ktor"`:

```toml
ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-client-content-negotiation = { module = "io.ktor:ktor-client-content-negotiation", version.ref = "ktor" }
ktor-serialization-kotlinx-json = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }
ktor-client-okhttp = { module = "io.ktor:ktor-client-okhttp", version.ref = "ktor" }
ktor-client-darwin = { module = "io.ktor:ktor-client-darwin", version.ref = "ktor" }
ktor-client-cio = { module = "io.ktor:ktor-client-cio", version.ref = "ktor" }
ktor-client-mock = { module = "io.ktor:ktor-client-mock", version.ref = "ktor" }
```

Do not rename the existing `-jvm` aliases; `:server` keeps using them.

## Source sets

Official Ktor KMP layout, plus `jvmMain` because this module has `jvm()` (desktop):

| Source set | Artifact |
| --- | --- |
| `commonMain` | `ktor-client-core`, `ktor-client-content-negotiation`, `ktor-serialization-kotlinx-json` |
| `androidMain` | `ktor-client-okhttp` |
| `iosMain` | `ktor-client-darwin` (default hierarchy from `iosArm64` / `iosSimulatorArm64`) |
| `jvmMain` | `ktor-client-cio` (same engine as `:server`) |
| `commonTest` | `ktor-client-mock` |

All of these are `implementation`, not `api` (hosts do not compile `HttpClient`).

Then construct **without** an engine class so the classpath picks one ([default engine](https://ktor.io/docs/client-engines.html)):

```kotlin
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

fun createHttpClient(): HttpClient = HttpClient {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}
```

`json()` is `io.ktor.serialization.kotlinx.json.json` (not the old `ktor-client-json` plugin). Serialization plugin is already on `:app:shared`. `@Serializable` DTOs for the clip API live in `:app:shared` or move to `:core` when server and app share them — still not `project(":server")`.

Koin: `single { createHttpClient() }` (skill `cmp-koin`). `HttpClient` is closeable (`client.close()`). One long-lived client, not a new client per request.

Repository owns HTTP. ViewModel does not construct `HttpClient`. Multipart clips: `submitFormWithBinaryData` + `formData { append(...) }` as in server `HttpClipClient` — copy the **request shape**, not the server class.

## Tests

Same pattern as `:server` `HttpClipClientTest`: `HttpClient(MockEngine) { install(ContentNegotiation) { json(...) } }`, then `http.close()`. Assertions: skill `cmp-test`. Do not hit a real server from `commonTest`.

## Android

When the app actually calls the network, the **host** `androidApp` manifest needs `android.permission.INTERNET`. Shared has no manifest of its own for that.

## Do not

| Wrong | This project |
| --- | --- |
| `libs.ktor.clientCore` (`…-jvm`) in `commonMain` | new aliases without `-jvm` |
| Retrofit, standalone OkHttp, `HttpURLConnection` | Ktor client |
| `implementation(project(":server"))` | HTTP to the running server |
| `ktor-server-*` in shared | server module only |
| Apache5 / Jetty / Java / Curl engines in shared | OkHttp + Darwin + CIO as above |
| `ktor-client-logging` dumping multipart bodies | skill `cmp-kermit` at the repository; never log clip bytes or tokens |
| New `HttpClient` in every ViewModel | one Koin `single` |
