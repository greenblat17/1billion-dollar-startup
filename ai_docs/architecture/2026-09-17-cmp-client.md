# Устройство CMP-клиента

**Дата:** 2026-09-17  
**Статус:** снимок раскладки; UI ещё шаблон `App()`, speaking-сессии в приложении нет  
**Связанные документы:** `ai_docs/product.md`, `ai_docs/plans/mvp-plan.md`, `ai_docs/researches/2026-09-14-stt-llm-tts-ai-service.md`  
**Правила для агента:** `AGENTS.md`, скиллы `cmp-mvvm` / `cmp-theme` / `cmp-strings` / `cmp-icons` / `compose-widget-sandbox` / `compose-hot-reload`

## Зачем CMP в этом продукте

Speaking Coach учит **говорить** по-английски: разговор — не чат и не Duolingo, а диагностика (grammar / vocabulary / pronunciation / fluency). Цикл из плана:

```text
разговор → анализ → 2–3 слабых места → работа над ними → адаптированный следующий разговор
```

Неделя 1 плана — Telegram как клиент, наш код — `:server` (голос → clip HTTP → `ai-service` STT→LLM→TTS → голосовой ответ). Native UI в тот срез не входил.

CMP — это **неделя 2**: тот же backend и тот же clip/session контракт, но пользовательский интерфейс — мобильное приложение (Android и iOS). Экран разговора как Zoom: пользователь говорит, AI отвечает голосом, приложение снова слушает — без кнопки «отправить» на каждую реплику. Desktop в продукте не цель; JVM-окно нужно, чтобы собирать и смотреть тот же shared UI.

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

`:server` — соседний модуль в `cmp/`, не зависимость shared. Когда появится экран, shared ходит в сервер по HTTP, не вшивает ktgbotapi.

## Модули клиента

| Модуль | Роль |
| --- | --- |
| `:app:shared` | Весь UI разговора (потом — результаты, onboarding). Таргеты: `android`, `jvm()`, iOS framework `Shared`. Код экранов — `commonMain`. Микрофон, плеер, конец реплики — `expect`/`actual` (`androidMain` / `jvmMain` / `iosMain`), сейчас только `getPlatform()`. |
| `:app:androidApp` | `MainActivity.setContent { App() }`. |
| `:app:iosApp` | SwiftUI → `MainViewController()` → `App()`. |
| `:app:desktopApp` | `Window { App() }` или `SandboxHost`. Не второй клиент и не копия виджетов. |
| `:core` | Общий Kotlin без UI. Shared берёт как `api`. |

Хост не рисует speaking-сессию. Activity/Swift — вход в `App()`.

## Compose: shared vs хост

В `commonMain` shared Compose (runtime, foundation, material3, ui, resources) — **`implementation`**. Он на всех **таргетах shared**, поэтому `App()` собирается на android/jvm/ios.

Он не протекает в androidApp / desktopApp / iosApp: хост вызывает уже собранный `App()`. Desktop `SandboxHost` сам пишет Material3 — у `:app:desktopApp` свой `implementation(libs.compose.material3)`. `compose.desktop.currentOs` — окно JVM, не M3. Не делать `api` Compose из shared «на все приложения».

Ресурсы: `cmp/app/shared/src/commonMain/composeResources/` (сейчас `drawable/`). Строки и шрифты — туда же (`values/`, `font/`), не `androidApp/res`. На android-таргете shared: `androidResources { enable = true }`.

## Как смотрим UI до недели 2

Продуктовый layout — телефон. Desktop `hotRun` показывает то же `App()` в **412×915**. Это превью мобильного разговора, не десктоп-продукт.

- `./gradlew :app:desktopApp:hotRun --auto` из `cmp/`
- MCP `compose-hot-reload`; новый чат Cursor сервер не перезапускает
- Один виджет: `runSandbox = true`, виджет в shared, в sandbox только моки
- `@Preview` видит человек, не агент

## Чего в CMP-коде ещё нет

Экрана разговора, записи/плеера, клиента к `:server`, Nav3, Koin, `AppTheme`, каталога строк. Целевой стиль, когда появятся экраны: MVVM + UDF, Navigation 3 (Maven `org.jetbrains.androidx.navigation3`, импорты `androidx.navigation3.*`), Koin — скилл `cmp-mvvm`. Не тащить в commonMain Hilt, Android Maven Compose/Nav, Room, зоопарк `:feature:*`.
