---
name: cmp-test
description: >
  Write Kotlin Multiplatform tests with kotlin.test (class + @Test member,
  assertEquals expected-then-actual) and kotlinx.coroutines.test.runTest
  for suspend code. Use when adding a test, commonTest, ViewModel test,
  or copying JUnit 5 / Kotest / MockK / Robolectric. Skip widget visual
  checks that belong in SandboxHost + compose-hot-reload. Do not use
  org.junit or io.kotest in commonTest.
---

# CMP kotlin.test

Default test format for this repo is **`kotlin.test`**, same as `:app:shared` `commonTest` and `:server` tests already do. Library source: [kotlin.test annotations](https://github.com/JetBrains/kotlin/blob/master/libraries/kotlin.test/annotations-common/src/main/kotlin/kotlin.test/Annotations.kt), [assertions](https://github.com/JetBrains/kotlin/blob/master/libraries/kotlin.test/common/src/main/kotlin/kotlin/test/Assertions.kt). CMP samples use the same annotations ([graphics-2d `GameControllerTest`](https://github.com/JetBrains/compose-multiplatform/blob/master/examples/graphics-2d/shared/src/commonTest/kotlin/minesweeper/GameControllerTest.kt)). Coroutines: [runTest](https://github.com/Kotlin/kotlinx.coroutines/blob/master/kotlinx-coroutines-test/common/src/TestBuilders.kt).

Catalog: `kotlin-test` on `commonTest`; add `kotlinx-coroutines-test` when the test is suspend or uses `Dispatchers.setMain`. Kermit assertions: skill `cmp-kermit`. Koin in tests: skill `cmp-koin`. Versions: MCP `klibs`. Gradle root `cmp/`, `GRADLE_USER_HOME=$HOME/.gradle`. Shared JVM slice: `./gradlew :app:shared:jvmTest`. Server: `./gradlew :server:test`.

## Shape

`@Test` is **only for class member functions**, no parameters, documented return `Unit`. Do not put `@Test` on top-level functions, object members, or extensions — kotlin.test says that is not portable.

```kotlin
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionClipQueueTest {
    @Test
    fun processesClipsOneByOneForTheSameUser() {
        assertEquals(expected, actual)
    }
}
```

Copy this repo: `class FooTest { @Test fun doesX() { … } }`. Names describe behavior (`processesClipsOneByOne…`), not `test1`.

Lifecycle: `@BeforeTest` / `@AfterTest` (class members, no args). Skip a case with `@Ignore` on the class or function. There is no common `@Nested` / parameterized runner.

On JVM, `kotlin.test.Test` is a typealias to `org.junit.Test` — still **import `kotlin.test.Test`**, not `org.junit.*`, so iOS/Android host keep compiling.

## Assertions

`assertEquals(expected, actual, message?)` — expected **first** ([`Assertions.kt`](https://github.com/JetBrains/kotlin/blob/master/libraries/kotlin.test/common/src/main/kotlin/kotlin/test/Assertions.kt), this repo’s `SharedCommonTest`). Do not copy Kermit’s own tests that swap the arguments.

Use what kotlin.test actually has: `assertTrue` / `assertFalse`, `assertNotEquals`, `assertSame` / `assertNotSame`, `assertNull` / `assertNotNull`, `assertIs` / `assertIsNot`, `assertContains`, `assertContentEquals`, `assertFails` / `assertFailsWith<T>`, `expect`, `fail`. Lazy-message overloads are `@ExperimentalKotlinTestApi` (Kotlin 2.4) — prefer the `String?` message used in this repo.

No Hamcrest, AssertJ, Google Truth, or Kotest matchers in `commonTest`.

## Suspend and ViewModel

Immediately **return** `runTest { }` from the `@Test` function. Do not run code after `runTest`. Do not nest `runTest`. Do not use `runBlocking` in commonTest.

```kotlin
@Test
fun loadsHome() = runTest {
    val vm = HomeViewModel(FakeRepo())
    assertIs<HomeUiState.Success>(vm.uiState.value)
}
```

`viewModelScope` uses `Dispatchers.Main`. kotlin.test’s own kdoc for `@BeforeTest` sets Main. Use the **function** `StandardTestDispatcher()` from `kotlinx.coroutines.test` (the kdoc snippet omits `()`):

```kotlin
@BeforeTest
fun setUp() {
    Dispatchers.setMain(StandardTestDispatcher())
}

@AfterTest
fun tearDown() {
    Dispatchers.resetMain()
}
```

Prefer constructing the ViewModel / repository with fakes. Do not pull Hilt, Robolectric, or InstantTaskExecutorRule into commonTest.

Need Koin? `startKoin { modules(...) }` in `@BeforeTest`, `stopKoin()` in `@AfterTest` (`org.koin.test.KoinTest` is optional). `KoinTestRule` is JUnit 4 JVM — not commonTest. Graph correctness is the compiler plugin (`compileSafety`); do not add deprecated `checkModules()` / `verify()` as the default.

## Where tests live

| Code | Source set |
| --- | --- |
| Shared logic, ViewModel, repository | `:app:shared` `commonTest` |
| JVM-only / desktop host | `jvmTest` (already exists) |
| Android host (no device) | `androidHostTest` (already exists) |
| iOS | `iosTest` (already exists) |
| Ktor server | `:server` `src/test` — still `kotlin.test` |

Do not add JS/Wasm test source sets. Do not put shared tests only in `androidApp`.

## Compose UI tests (optional)

Widget look-and-feel: skills `compose-widget-sandbox` + `compose-hot-reload`, not a screenshot test. Automated Compose tests only when the user asked.

CMP’s own tests use `kotlin.test.Test` + `runComposeUiTest` + `setContent` + `onNodeWithTag` ([`ComposeAppTest`](https://github.com/JetBrains/compose-multiplatform/blob/master/gradle-plugins/compose/src/test/test-projects/misc/kmpResourcePublication/appModule/src/commonTest/kotlin/ComposeAppTest.kt)). Import `androidx.compose.ui.test.runComposeUiTest` (opt in `ExperimentalTestApi` if the compiler requires it). Immediately return `runComposeUiTest { }` like `runTest`. Maven: query klibs / Compose `ui-test` — do not copy Android `androidx.compose.ui:ui-test-junit4` into commonTest. imageviewer’s `createComposeRule()` is JUnit4 **desktopTest** — not the common pattern.

## Do not

| Wrong | This project |
| --- | --- |
| Kotest (`io.kotest`), Spek | `kotlin.test` |
| `org.junit.jupiter.api.Test`, `org.junit.Test` imports in common | `kotlin.test.Test` |
| MockK / Mockito in commonTest | Fakes / in-memory doubles |
| Robolectric, Espresso, Roborazzi as the shared default | `commonTest` + optional `runComposeUiTest` |
| Top-level `@Test fun` | Class member |
| `assertEquals(actual, expected)` | expected first |
| `runBlocking` | `runTest` |
