# Устройство CMP-клиента

**Дата:** 2026-09-17  
**Статус:** UI в `:app:shared` (тема Inter, Nav3). HTTP к DEV Ktor: `HttpSpeakingCoachClient` (auth/home/sessions/rtc/complete/review). AuthScreen на почте. Call — WebRTC (`webrtc-kmp` / `webrtc-java`), Review — полл. Карточка «Последний разговор» — мок.  
**Связанные документы:** `ai_docs/product.md`, `ai_docs/plans/mvp-plan.md`, `ai_docs/design/2026-09-17-mobile-ui.md`, [`2026-09-20-webrtc-client.md`](2026-09-20-webrtc-client.md)  
**Правила для агента:** `AGENTS.md`, скиллы `cmp-mvvm` / `cmp-theme` / `cmp-strings` / `cmp-icons` / `compose-widget-sandbox` / `compose-hot-reload`

## Зачем CMP в этом продукте

Speaking Coach учит **говорить** по-английски: разговор — не чат и не Duolingo, а диагностика (grammar / vocabulary / pronunciation / fluency). Цикл из плана:

```text
разговор → анализ → 2–3 слабых места → работа над ними → адаптированный следующий разговор
```

Неделя 1 плана — Telegram как клиент, наш код — `:server` (голос → clip HTTP → `ai-service` STT→LLM→TTS → голосовой ответ). Native UI в тот срез не входил.

CMP — это **неделя 2**: тот же backend и тот же clip/session контракт, но пользовательский интерфейс — мобильное приложение (Android и iOS). Макеты экранов: `ai_docs/design/2026-09-17-mobile-ui.md` (Welcome / Home / Call / разбор). Экран разговора как Zoom: пользователь говорит, AI отвечает голосом, приложение снова слушает — без кнопки «отправить» на каждую реплику. Desktop в продукте не цель; JVM-окно нужно, чтобы собирать и смотреть тот же shared UI.

Не дублировать Telegram-бота в Compose. Не делать ядром экраны упражнений (неделя 4, по желанию после разбора).

## Где это в репо

Git root — корень speaking-coach. **Gradle root — `cmp/`**. Каталог: `cmp/gradle/libs.versions.toml` (CMP 1.12, Kotlin 2.4.20).

```text
Telegram / CMP-приложение
        │  clip + session HTTP
     :server          Ktor (бот уже есть; приложение к нему же)
        │
    ai-service        Python STT → LLM → TTS

androidApp / iosApp / desktopApp     тонкие хосты окна
              │
         :app:shared                 единственный Compose UI (commonMain)
              │
            :core                    Kotlin без Compose
```

Shared ходит в сервер по HTTP (`HttpSpeakingCoachClient`). Base URL запекается на compile (`generateApiConfig`): локально `cmp/client.local.properties` / env, в CI — `https://$DEV_CMP_SERVER_HOST` (`CMP_SERVER_HOST` на `main`). Desktop в runtime ещё читает `SPEAKING_COACH_API_BASE_URL`. Desktop `hotRun`: Kermit дублируется в CHR `get_logs` (`ChrKermitLogWriter`). Не `project(":server")`.

Навигация: Welcome → Auth → Home → Call → Review `0…1` (Grammar, Vocabulary); Home (аватар) → Profile. Нижнего таббара нет. Экран Истории отложен (макет `09-history.png` не удалять). Pronunciation / Fluency / Speed of speech — только PNG 06–08, в карусели нет. Живые: Auth, Home (`GET /v1/home`), Profile (кэш user), `POST /v1/sessions`, Call rtc/WebRTC, Review poll. Карточка «Последний разговор» — `MockSpeakingData`. HTTP — [`../integrations/2026-09-18-mobile-api.md`](../integrations/2026-09-18-mobile-api.md). WebRTC — [`2026-09-20-webrtc-client.md`](2026-09-20-webrtc-client.md).

## Модули клиента

| Модуль | Роль |
| --- | --- |
| `:app:shared` | Весь UI разговора. Таргеты: `android`, `jvm()`, iOS framework `Shared`. Код экранов — `commonMain`. WebRTC — `webrtcMain` (Android/iOS, shepeliev) и `jvmMain` (webrtc-java). |
| `:app:androidApp` | `MainActivity.setContent { App() }`. `configChanges=uiMode` — смена темы без recreate; window theme DayNight с `windowBackground` как у `AppTheme` (cream / ink). |
| `:app:iosApp` | SwiftUI → `MainViewController()` → `App()`. |
| `:app:desktopApp` | `Window { App() }` или `SandboxHost`. Не второй клиент и не копия виджетов. |
| `:core` | Общий Kotlin без UI. Shared берёт как `api`. |

Хост не рисует speaking-сессию. Activity/Swift — вход в `App()`.

## Compose: shared vs хост

В `commonMain` shared Compose (runtime, foundation, material3, ui, resources) — **`implementation`**. Он на всех **таргетах shared**, поэтому `App()` собирается на android/jvm/ios.

Он не протекает в androidApp / desktopApp / iosApp: хост вызывает уже собранный `App()`. Desktop `SandboxHost` сам пишет Material3 — у `:app:desktopApp` свой `implementation(libs.compose.material3)`. `compose.desktop.currentOs` — окно JVM, не M3. Не делать `api` Compose из shared «на все приложения».

Ресурсы: `cmp/app/shared/src/commonMain/composeResources/` (`drawable/`, `values/strings.xml`). Шрифты — туда же (`font/`), не `androidApp/res`. На android-таргете shared: `androidResources { enable = true }`.

## Как смотрим UI до недели 2

Продуктовый layout — телефон. Desktop `hotRun` показывает то же `App()` в **412×915**. Это превью мобильного разговора, не десктоп-продукт.

- `./gradlew :app:desktopApp:hotRun --auto` из `cmp/`
- MCP `compose-hot-reload`; новый чат Cursor сервер не перезапускает
- Один виджет: `runSandbox = true`, виджет в shared, в sandbox только моки
- `@Preview` видит человек, не агент

## Чего в CMP-коде ещё нет

Записи/плеера клипов, истории, pronunciation/fluency. WebRTC Call и Review poll есть; карточка «Последний разговор» мок. Не тащить в commonMain Hilt, Android Maven Compose/Nav, Room, зоопарк `:feature:*`.
