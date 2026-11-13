# Speaky: ориентир для агента

## Продукт и источники

- Speaky — постоянные отношения с AI-репетитором Сашей с характером Kora H.
- ЦА: люди с разговорным барьером, примерно A2–B2; фокус — разговорная практика.
- Telegram — переписка с репетитором; приложение в целевой модели обслуживает уроки и прогресс.
- Начни с [карты документации](ai_docs/README.md) и [status.md](ai_docs/status.md): он показывает текущую стадию проекта.
- [Продукт](ai_docs/product/2026-11-08-ai-tutor-experience-v0.5.md), [архитектура](architecture.md), [состояние реализации](ai_docs/status.md).
- Исходную v0.5 сохраняй как источник; новые решения записывай в актуальные документы `ai_docs/`.
- Перед работой проверь git status; сохраняй чужие изменения и согласованный объём задачи.

## Поведение репетитора

- Постоянный русский текстовый чат заменил voice onboarding; LLM ведёт диалог через native tools.
- Не вводи анкету, жёсткий funnel или маршрутизацию намерений по ключевым словам.
- Trial: создать, прочитать, перенести, отменить, записать заново; память и история сохраняются.
- Ясное поручение выполняется без повторного согласия и требования заполнить профиль.
- В режиме знакомства выясняются цель/нужный результат, трудности и самооценка уровня; опыт — только если полезен.
- Kora задаёт общий характер; стратегия знакомства ведёт к пробному, стратегия обычного диалога поддерживает разговор.
- Уже известное не переспрашивается; неизвестный уровень допустим; для «сейчас» пояс не обязателен.
- Голос в обычном чате игнорируется; legacy routes/workers изолированы от нового runtime.
- Не подменяй реальные pipeline фиктивными результатами; состояние урока, Mini App и оплаты проверяй в status.md.
- После каждой крупной задачи обновляй `ai_docs/status.md`: дату среза, что сделано, что осталось, проверки и поставку; заменяй устаревшее, не веди хронику.
- Внешнюю идентичность бота меняют через BotFather/config; не меняй её без поручения.

## Карта кода

- `cmp/server/` — Ktor foundation, PostgreSQL, Telegram и jobs; карта исходников — в architecture.md.
- `ai-service/app/` — FoundationTutor, ContextBuilder и LearningEngine; `tutor_store.py` сохранён для legacy/import.
- `mini-app/` — самостоятельный Telegram frontend: уроки и My Progress.
- Shipping roots: `SpeakyApplication.kt` (через `Application.kt`) и `ai-service/app/speaky_main.py`; `app/main.py` сохранён как legacy.
- `cmp/app/` — CMP-прототип Android/iOS/Desktop, не готовый Mini App; `cmp/core/` — общий Kotlin.
- `infra/` и `.github/workflows/` — поставка; команды и конфигурация — в [operations](ai_docs/integrations/operations.md).

## Разработка и проверка

- Git root — этот каталог; Gradle root — `cmp/`; запускай `./gradlew` только оттуда.
- Для Gradle используй `GRADLE_USER_HOME=$HOME/.gradle`; Python-команды запускай из `ai-service/`.
- Backend-проверки: `pytest`; Kotlin: `./gradlew :server:test :server:buildFatJar detekt`.
- Для docs-only проверяй ссылки, пути и `git diff --check`; runtime-тесты не нужны.
- Разделяй локальные тесты, DEV readiness и живую приёмку; CI не доказывает качество диалога.
- Границы живой приёмки смотри в status.md; платные прогоны после HTTP 402 не повторяй без поручения.
- Не раскрывай секреты, не переписывай Flyway и не удаляй данные при deploy; импорт/cutover проверяй отдельно.
- Commit/push, merge, деплой и сообщения пользователям выполняй только в разрешённом объёме.

## CMP и skills
- Используй применимые skills из `.agents/skills/`: `cmp-*`, `compose-*`, `kmp-libraries-expert`; версии библиотек сверяй через klibs.
- Работай с исходниками, не `bin/` и `build/`; UI: MVVM/UDF, Navigation 3, Koin; ресурсы — в `composeResources`.
- Compose/Koin/Ktor в shared остаются `implementation`; нужные host-зависимости добавляй в host, не через `api`.
- UI: `compose-hot-reload`, 412×915, обе темы и логи recomposition; новый виджет — `compose-widget-sandbox`.
