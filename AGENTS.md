# Speaky: ориентир для агента

## Продукт и источники

- Speaky — постоянные отношения с AI-репетитором Сашей с характером Kora H.
- ЦА: люди с разговорным барьером, примерно A2–B2; фокус — разговорная практика.
- Telegram — переписка с репетитором; приложение в целевой модели обслуживает уроки и прогресс.
- Начни с [карты документации](ai_docs/README.md), затем прочитай документы своей задачи.
- [Продукт](ai_docs/product.md), [архитектура](architecture.md), [состояние реализации](ai_docs/status.md).
- Исходную v0.5 сохраняй как источник; новые решения записывай в актуальные документы `ai_docs/`.
- Перед работой проверь git status; сохраняй чужие изменения и согласованный объём задачи.

## Текущий этап SP-01

- Постоянный русский текстовый чат заменил voice onboarding; LLM ведёт диалог через native tools.
- Не вводи анкету, жёсткий funnel или маршрутизацию намерений по ключевым словам.
- Trial: создать, прочитать, перенести, отменить, записать заново; память и история сохраняются.
- Ясное поручение выполняется без повторного согласия и требования заполнить профиль.
- Перед инициативным приглашением выясняются цель, результат, трудности, опыт и самооценка уровня.
- Уже известное не переспрашивается; неизвестный уровень допустим; для «сейчас» пояс не обязателен.
- Голос в обычном чате игнорируется; старые calls и Vocabulary Builder имеют отдельные обработчики.
- «Открыть урок» — заглушка; Mini App, проведение trial, оплата и roadmap ещё не реализованы.
- `tutor:v1` и `learner:` не связаны; общий профиль чата и звонков явно отложен.
- Контракт и карта файлов: [SP-01](ai_docs/integrations/2026-11-10-sp01-tutor-chat.md).

## Карта кода

- `cmp/server/` — Ktor, Telegram webhook, TutorClient, app API, PostgreSQL и аналитика.
- `ai-service/app/tutor.py` — prompt и tool loop; `tutor_tools.py` — операции; `tutor_store.py` — Redis.
- `ai-service/app/main.py` — API и wiring; `learner_store.py` — отдельный профиль прежних звонков.
- `cmp/app/` — CMP-прототип Android/iOS/Desktop, не готовый Mini App; `cmp/core/` — общий Kotlin.
- `infra/` и `.github/workflows/` — инфраструктура, CI и ручные DEV/PROD workflow.

## Разработка и проверка

- Git root — этот каталог; Gradle root — `cmp/`; запускай `./gradlew` только оттуда.
- Для Gradle используй `GRADLE_USER_HOME=$HOME/.gradle`; Python-команды запускай из `ai-service/`.
- Backend-проверки: `pytest`; Kotlin: `./gradlew :server:test :server:buildFatJar detekt`.
- Для docs-only проверяй ссылки, пути и `git diff --check`; runtime-тесты не нужны.
- Разделяй локальные тесты, DEV readiness и живую приёмку; CI не доказывает качество диалога.
- Новое поведение модели и Telegram UX ещё не принято; платные прогоны после HTTP 402 не повторяй без поручения.
- Не раскрывай секреты; не переписывай применённые Flyway-миграции и не удаляй Redis/Postgres при deploy.
- Commit/push, merge, деплой и сообщения пользователям выполняй только в разрешённом объёме.

## CMP и skills

- Используй применимые skills из `.agents/skills/`: `cmp-*`, `compose-*`, `kmp-libraries-expert`; версии библиотек сверяй через klibs.
- Shared UI: MVVM/UDF, Navigation 3, Koin; строки, иконки и шрифты — в `composeResources`.
- Compose/Koin/Ktor в shared остаются `implementation`; нужные host-зависимости добавляй в host, не через `api`.
- UI: `compose-hot-reload`, 412×915, обе темы и логи recomposition; новый виджет — `compose-widget-sandbox`.
