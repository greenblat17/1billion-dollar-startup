# API существующего CMP-приложения

Контракты базовой поставки SP-01 и сохранённого legacy-кода. Новый фундамент SP-00 использует PostgreSQL;
legacy сохраняется отдельно, без переноса его Redis-хранилищ и без обязательного подключения старых функций к новому runtime.
Готовность изоляции и переключения текущего чата проверяется по [статусу](../status.md).
Описание legacy API ниже не означает, что эти маршруты доступны в новом runtime.

- [Mobile app HTTP contract](#mobile-app-http-contract)
- [Устройство CMP-клиента](#устройство-cmp-клиента)
- [WebRTC клиента](#webrtc-клиента)

## Mobile app HTTP contract

> Контракт существующего CMP-прототипа. Он не реализует Mini App, trial или общий профиль нового репетитора. Упоминания DEV ниже относятся к исходному срезу; [текущий статус](../status.md).

**Дата:** 2026-09-19  
**Статус:** бэкенд среза на DEV; CMP клиент ходит в Auth/Home/Profile/sessions/Call/Review. Карточка «Последний разговор» — мок.  
**Клиент сейчас:** Welcome → Auth (почта) → Home с `GET /v1/home`; «Начать» → `POST /v1/sessions` → Call (WebRTC + `POST .../rtc`). Hangup → `POST .../complete` → Review поллит `GET .../review`. `SpeakingCoachClient` живой.  
**Не этот контракт:** клипы бота — [voice-and-reviews.md](voice-and-reviews.md#clip--session-http-contract). CMP их не вызывает.

База: Ktor на DEV (`AI_SERVICE_BASE_URL` туда не светить с клиента). JSON camelCase. Auth-ручки без заголовка; остальное `Authorization: Bearer <jwt>`. JWT в лог не пишем. Клиент **не** ходит в ai-service.

### Поток экранов → API

Сейчас в `AppNav` Welcome сразу открывает Home при JWT; без токена — Welcome. Call несёт `sessionId`. Review с hangup — живой полл; «Последний разговор» открывает мок (`ReviewRoute()` без id).

```text
Welcome
  «Начать»                    → Auth (регистрация) → POST /v1/auth/register → Home
  «У меня уже есть аккаунт»   → Auth (вход)        → POST /v1/auth/login    → Home

Home  (вход на экран: GET /v1/home)
  чипы темы                   → локально, без HTTP
  «Начать» на hero-карточке   → POST /v1/sessions → Call
  «Последний разговор»        → нет в MVP (мок на экране, без HTTP)
  аватар                      → Profile, без HTTP

Call
  появление (mic + SDP)       → POST /v1/sessions/{id}/rtc
  mute / CC / таймер          → локально; медиа с OpenAI, не с Ktor
  «Завершить»                 → POST /v1/sessions/{id}/complete → Review

Review
  появление                   → GET /v1/sessions/{id}/review  (полл, если 202)
  «Далее» / «Завершить»       → только навигация, без HTTP

Profile
  имя / почта                 → из ответа login/register (кэш), без GET /me
  язык, CC, цель, микрофон    → локально, без HTTP
  голос репетитора            → локально; уйдёт в следующем POST /v1/sessions
  «Выйти из аккаунта»         → POST /v1/auth/logout → Welcome
```

```mermaid
flowchart LR
  welcome[Welcome]
  auth[Auth]
  home[Home]
  call[Call]
  review[Review]
  profile[Profile]

  welcome -->|"register_or_login"| auth
  auth -->|"GET_home"| home
  home -->|"POST_sessions"| call
  call -->|"POST_rtc_then_WebRTC"| call
  call -->|"POST_complete"| review
  review -->|"no_HTTP"| home
  home --> profile
  profile -->|"POST_logout"| welcome
```

Холодный старт с JWT на диске: Welcome не показываем, сразу `GET /v1/home`. 401 → стереть токен, Welcome.

---

### Welcome — `WelcomeScreen` / `WelcomeWidget`

Кнопки: `welcome_start` («Начать»), `welcome_have_account` («У меня уже есть аккаунт»). Ссылки на условия/политику — статичные strings, без API.

**Сейчас:** CTA открывают Auth (регистрация / вход).

**Когда подключат бэк:** CTA не ходят в API сами. Они открывают Auth.

| UI | API |
| --- | --- |
| «Начать» | нет; Auth в режиме регистрации |
| «У меня уже есть аккаунт» | нет; Auth в режиме входа |

---

### Auth — `AuthScreen` / `AuthWidget`

Визуал как у Welcome (не Material-шаблон). Два режима с одними полями: почта, пароль, в регистрации ещё имя (`displayName`). Google/Apple в этом срезе нет.

| UI | API | Дальше |
| --- | --- | --- |
| Сабмит регистрации | `POST /v1/auth/register` | сохранить JWT → Home |
| Сабмит входа | `POST /v1/auth/login` | сохранить JWT → Home. `AuthViewModel` переживает logout, поэтому режим login/register берётся с маршрута (`setMode`), а `busy` сбрасывается после успеха и при `session == null` |
| Ошибка 409 email занят / 401 неверный пароль | нет второго запроса | остаться на Auth, показать ошибку |

`user` из ответа — кэш на диск: имя на Home (`home_greeting` «Привет, %s!»), имя и почта на Profile.

#### `POST /v1/auth/register` → 201

```json
{ "email": "alex@example.com", "password": "••••", "displayName": "Алекс" }
```

```json
{
  "token": "<jwt>",
  "user": { "id": "…", "email": "alex@example.com", "displayName": "Алекс" }
}
```

409 — email уже есть. 400 — валидация.

#### `POST /v1/auth/login` → 200

```json
{ "email": "alex@example.com", "password": "••••" }
```

Тело ответа как у register. 401 — неверная пара.

#### `POST /v1/auth/logout` → 204

С Profile, не отсюда. Тело пустое. Сервер JWT не ревокает (TTL ~30 дней). Клиент обязан стереть токен.

---

### Home — `HomeScreen` / `HomeViewModel` / `HomeWidget`

| UI | Когда | API |
| --- | --- | --- |
| Заголовок «Привет, Алекс!» | вход на экран, смена `userId` в `SessionStore` (logout → login/register) | `GET /v1/home` → `userName`. Пока ответ не пришёл — `displayName` из сессии. `HomeViewModel` переживает logout, поэтому имя нельзя кэшировать в `init` |
| Карточка «Последний разговор» | — | **нет в MVP.** Мок может остаться на экране; тап не зовёт API и не открывает Review |
| Полоска «Цель на день», streak, огонь | — | **нет.** `DailyGoalStore` |
| Чипы Everyday / Work / Travel / Random | тап | **нет.** `HomeViewModel.onTopicSelected` |
| Аватар справа в шапке | тап | **нет.** `ProfileRoute` |
| Hero «Начать разговор» → CTA «Начать» | тап | `POST /v1/sessions`, затем `CallRoute(sessionId)` |

**Random:** в API нет. Перед `POST /v1/sessions` клиент (или, если забыл, Ktor) выбирает Everyday / Work / Travel. Тема пишется в сессию; на Home её не показываем.

`tutorVoice` в этом запросе берётся из локальной настройки Profile (`marin` \| `cedar`, не мок «Emma»). Если пользователь голос не трогал — `marin`.

Пока `POST /sessions` идёт — CTA disabled / лоадер на карточке, Call ещё не открыт. Ошибка — остаёмся на Home.

#### `GET /v1/home` → 200

Bearer. 401 → Welcome.

```json
{ "userName": "Алекс" }
```

Нет `lastConversation`, `spokenSeconds`, `streakDays`, `goalMinutes`. Review с Home не открываем.

#### `POST /v1/sessions` → 201

Bearer.

```json
{ "topic": "Work", "tutorVoice": "marin" }
```

`topic`: `Everyday` \| `Work` \| `Travel`. `tutorVoice`: `marin` \| `cedar`. 400 — иное.

```json
{ "sessionId": "app-{userId}-{uuid}" }
```

Этот `sessionId` — аргумент Call и всех `/v1/sessions/{id}/*`. Не пересекается с `tg-*`.

---

### Call — `CallScreen` / `CallViewModel` / `CallWidget`

Таймер, субтитр AI, mute, hangup, CC.

| UI | Когда | API |
| --- | --- | --- |
| Кольца / connecting | после `CallRoute`: mic permission → `createOffer` | `POST /v1/sessions/{id}/rtc` с SDP offer |
| Субтитры | события OpenAI `oai-events` | **нет Ktor.** Копим `turns` в памяти |
| Кнопка микрофона | mute локального трека | **нет** |
| Кнопка CC | `CallViewModel.onCaptionsToggled` | **нет** |
| Таймер | локальные секунды | **нет** (уходят в `durationSec` на hangup) |
| «Завершить» | hangup | `POST /v1/sessions/{id}/complete`, затем `ReviewRoute(sessionId)` |

Порядок важен: **mic → offer → один POST rtc**. `ek_` живёт ~1 мин. Trickle ICE через Ktor нет. Клиенту не отдаём `ek_`. Медиа — WebRTC на OpenAI, не на наши серверы.

Второй `rtc` по той же сессии или параллельный звонок того же user — 409. UI: ошибка на Call, назад на Home.

429 Realtime — ошибка Call, новая сессия с Home, не retry того же id.

#### `POST /v1/sessions/{id}/rtc` → 201

Bearer. Владелец сессии. Тело: `Content-Type: application/sdp` (raw offer) **или** `application/json` `{ "sdp": "v=0…" }`. Ответ: `Content-Type: application/sdp` (answer). Не JSON с ключом.

404 — чужая/неизвестная сессия. 409 — уже есть активный rtc.

#### `POST /v1/sessions/{id}/complete` → 202

Bearer. Сразу после hangup, **до** того как Review что-то рисует. Ktor ставит job разбора и отвечает 202; Review поллит GET.

```json
{
  "turns": [
    { "role": "assistant", "text": "Hey! What are you working on?" },
    { "role": "user", "text": "I work here since 2023." }
  ],
  "durationSec": 204
}
```

`turns` — user + assistant из `oai-events` за звонок, по порядку.  
Нет ни одной user-реплики → **422**, не писать фейковые баллы. UI: не открывать карусель Grammar/Vocabulary; сообщение «мало речи» и назад на Home.

`onDispose` / swipe-kill: тот же complete с буфером, best-effort.

---

### Review — `ReviewScreen` / `ReviewViewModel` / `ReviewWidget`

Карусель `ReviewMetric.Grammar` → `Vocabulary`. Pronunciation / Fluency / Speed, История и карточка «Последний разговор» на Home — не вызывают API.

Единственный вход — hangup с Call:

| Откуда | UI | API |
| --- | --- | --- |
| Call «Завершить» | лоадер «Разбор разговора», пока 202 | полл `GET /v1/sessions/{id}/review` ~300 ms до ~30 с |

| UI | API |
| --- | --- |
| Балл, лид, буллеты, «Ты сказал» / «Лучше», совет | поля `steps[]` |
| «Далее» (`review_next`) | **нет.** `ReviewRoute(stepIndex + 1)` |
| «Завершить» (`review_finish`) на Vocabulary | **нет.** `popToMain` → Home, который снова сделает `GET /v1/home` |
| Назад в шапке | **нет.** pop. С Call-входа можно уйти, пока 202 — разбор на сервере всё равно досчитается |

#### `GET /v1/sessions/{id}/review`

Bearer. Только владелец.

| Статус | UI |
| --- | --- |
| 200 | `ReviewUiState.steps` (ровно Grammar и Vocabulary) |
| 202 | лоадер, ещё полл |
| 404 | чужая/нет сессии → назад |
| 422 | complete с мало речи или разбор отказался — не рисовать 78/100 |
| 5xx | кнопка «повторить»: снова `POST .../complete` с тем же телом **нельзя** (complete уже принят); retry — повторный GET, либо явная ошибка «разбор не вышел» |

200:

```json
{
  "sessionId": "app-…",
  "steps": [
    {
      "metric": "Grammar",
      "score": 78,
      "lead": "Главная зона роста — времена и предлоги.",
      "bullets": ["Past Simple", "Present Perfect", "Предлоги места и направления"],
      "examples": [
        { "original": "I was in Turkey last summer.", "improved": "I went to Turkey last summer." },
        { "original": "I work here since 2023.", "improved": "I have worked here since 2023." }
      ],
      "tip": "Когда рассказываешь о прошлом опыте, следи за временем глагола."
    },
    {
      "metric": "Vocabulary",
      "score": 84,
      "lead": "Мысль понятная, но словарь можно сделать богаче.",
      "bullets": ["Меньше повторов simple words", "Больше сильных глаголов и прилагательных"],
      "examples": [
        { "original": "It was very very good.", "improved": "It was really enjoyable." }
      ],
      "tip": "Пробуй заменять very + adjective одним более точным словом."
    }
  ]
}
```

Это те же поля, что `ReviewStep` / `ExamplePair` в `ui/review/ReviewUiState.kt`. Не notes `wrong|||better` из Telegram. Не pronunciation/fluency.

---

### Profile — `ProfileScreen` / `ProfileWidget`

| UI | API |
| --- | --- |
| Имя, буква аватара, email | **нет отдельного GET.** Кэш `user` с login/register |
| «Язык интерфейса» | **нет** (RU, strings) |
| «Голос репетитора» | **нет сейчас.** Локально `marin`/`cedar`; уходит только в следующем `POST /v1/sessions` |
| «Субтитры по умолчанию» | **нет.** `ProfileViewModel` / локальный стейт, как сейчас |
| «Цель на день» | **нет.** `DailyGoalStore` |
| «Микрофон» | **нет.** системные настройки |
| Юр.ссылки | **нет.** strings |
| «Выйти из аккаунта» | `POST /v1/auth/logout` → стереть JWT → `WelcomeRoute` |

Нет `GET/PATCH /v1/me` в этом срезе.

---

### Не UI (Ktor → ai-service)

Приложение эти пути **не** знает. Заголовок `X-Internal-Token`. Без него 401.

| Кто зовёт | Когда | Путь |
| --- | --- | --- |
| Ktor, обрабатывая rtc | пользователь уже на Call | `POST /internal/realtime/call` — mint+SDP, spoken prompt, voice, topic |
| Ktor, обрабатывая complete | пользователь уже на Review-лоадере | `POST /internal/review` — turns → JSON шагов Grammar/Vocabulary |

Клипы `POST /v1/clips` — Telegram in-process, не CMP. Публичный прокси на `:443` убран.

---

### Что экраны сознательно не дергают

- Карточка «Последний разговор» на Home (мок ок, живого API нет)
- History
- Pronunciation / Fluency / Speed
- Упражнения
- `/v1/clips`, greeting audio
- Google / Apple / verify / forgot / reset
- `PATCH /v1/me`
- `/internal/*`

### Ошибки, общие для UI

| Код | Где видно |
| --- | --- |
| 401 | любая Bearer-ручка → как logout: Welcome |
| 400 | Auth / sessions: подсветка полей |
| 403 | не используем для JWT; клиповый webhook — не app |
| 409 register | «email занят» на Auth |
| 409 rtc | «уже идёт звонок» на Call |
| 422 complete/review | «мало речи», не карусель с мок-баллами |

## Устройство CMP-клиента

Сохранённый прототип Android/iOS/Desktop, не Mini App SP-00. Его серверные маршруты изолированы от нового runtime.
Общий UI находится в `cmp/app/shared/`; host-модули запускают один `App()`.
Shared использует `HttpSpeakingCoachClient`, не зависимость на `:server`.
Base URL задаётся при сборке через `generateApiConfig`, `cmp/client.local.properties` или env.
Desktop дополнительно читает `SPEAKING_COACH_API_BASE_URL` при запуске.
В CI адрес определяется DEV/PROD host; это конфигурация сборки, не доказательство доступности API.
Kermit в desktop hotRun передаёт логи в CHR через `ChrKermitLogWriter`.

### Модули клиента

| Модуль | Роль |
| --- | --- |
| `:app:shared` | Весь UI разговора. Таргеты: `android`, `jvm()`, iOS framework `Shared`. Код экранов — `commonMain`. WebRTC — `webrtcMain` (Android/iOS, shepeliev) и `jvmMain` (webrtc-java). |
| `:app:androidApp` | `MainActivity.setContent { App() }`. `configChanges=uiMode` — смена темы без recreate; window theme DayNight с `windowBackground` как у `AppTheme` (cream / ink). |
| `:app:iosApp` | SwiftUI → `MainViewController()` → `App()`. |
| `:app:desktopApp` | `Window { App() }` или `SandboxHost`. Не второй клиент и не копия виджетов. |
| `:core` | Общий Kotlin без UI. Shared берёт как `api`. |

Хост не рисует speaking-сессию. Activity/Swift — вход в `App()`.

### Compose: shared vs хост

В `commonMain` shared Compose (runtime, foundation, material3, ui, resources) — **`implementation`**. Он на всех **таргетах shared**, поэтому `App()` собирается на android/jvm/ios.

Он не протекает в androidApp / desktopApp / iosApp: хост вызывает уже собранный `App()`. Desktop `SandboxHost` сам пишет Material3 — у `:app:desktopApp` свой `implementation(libs.compose.material3)`. `compose.desktop.currentOs` — окно JVM, не M3. Не делать `api` Compose из shared «на все приложения».

Ресурсы: `cmp/app/shared/src/commonMain/composeResources/` (`drawable/`, `values/strings.xml`). Шрифты — туда же (`font/`), не `androidApp/res`. На android-таргете shared: `androidResources { enable = true }`.

### Как смотрим UI прототипа

Продуктовый layout — телефон. Desktop `hotRun` показывает то же `App()` в **412×915**. Это превью мобильного разговора, не десктоп-продукт.

- `./gradlew :app:desktopApp:hotRun --auto` из `cmp/`
- MCP `compose-hot-reload`; новый чат Cursor сервер не перезапускает
- Один виджет: `runSandbox = true`, виджет в shared, в sandbox только моки
- `@Preview` видит человек, не агент

### Чего в CMP-коде ещё нет

Записи/плеера клипов, истории, pronunciation/fluency. WebRTC Call и Review poll есть; карточка «Последний разговор» мок. Не тащить в commonMain Hilt, Android Maven Compose/Nav, Room, зоопарк `:feature:*`.

## WebRTC клиента

Ниже устройство сохранённого клиента и его legacy-интеграции; оно не подтверждает голосовой урок SP-00.


Один `expect fun createRealtimeCall()`. Android+iOS: `com.shepeliev:webrtc-kmp:0.125.11` (общий код в `src/webrtcMain`, это `srcDir` android/ios — JVM у библиотеки нет). Desktop: `dev.onvoid.webrtc:webrtc-java:0.14.0` + native classifier хоста.

Порядок как в HTTP-контракте: mic → SDP offer → один `POST /v1/sessions/{id}/rtc` (`application/sdp`). Trickle ICE нет. Субтитры и `turns` — data channel `oai-events`, не Ktor. Hangup → `POST .../complete` → Review поллит `GET .../review`. Карточка «Последний разговор» остаётся моком (`ReviewRoute()` без sessionId).

iOS: `WebRTC` из [webrtc-sdk/Specs](https://github.com/webrtc-sdk/Specs) `125.6422.07`. Gradle качает `WebRTC.xcframework` в `~/.gradle/caches/webrtc-sdk/` (готово = есть `WebRTC.framework`, не пустая папка от `outputs.dir`) и даёт `-F` по slice `ios-arm64_x86_64-simulator` / `ios-arm64-simulator`. Иначе `iosSimulatorArm64Test` падает: cinterop уже пишет `-framework WebRTC`. `iosApp` дополнительно линкует тот же SDK через SPM. Android: `RECORD_AUDIO` + запрос в `MainActivity`. Desktop: Pulse/ALSA через native webrtc-java.

Клиент `ek_` не видит. coturn в этот срез не входит. SDP offer до OpenAI идёт с хвостовым `\r\n` (без `trim`/`strip` — иначе `invalid_offer` / unmarshal EOF). Отказ OpenAI `POST /v1/realtime/calls` пишется в лог ai-service как `openai realtime HTTP … body=` (без ключа и без SDP).

Пустой `iceServers` на телефоне не проходит NAT (mDNS host-only). Клиент ставит Google STUN (`stun.l.google.com` / `stun1.l.google.com:19302`), ждёт ICE gathering Complete до POST rtc, после answer — ICE `Connected`/`Completed` (Failed/timeout → `CallError.Network`). Offer: `offerToReceiveAudio`. Remote audio: `onTrack` + enable. Android: `MODE_IN_COMMUNICATION`, выход BT/провод если есть, иначе speaker (не earpiece); data-channel payload копируется в native observer (Buffer живёт только в колбэке). iOS: `RTCAudioSession` PlayAndRecord + DefaultToSpeaker + AllowBluetooth, без `overrideOutputAudioPort(Speaker)`.
