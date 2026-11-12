# Speaking Coach

Speaky — персональный AI-репетитор Саша с характером Kora H для людей с разговорным барьером.
Telegram — постоянная переписка с репетитором. Приложение в целевой концепции — инфраструктура согласованных уроков и прогресса.

Telegram поддерживает постоянный диалог, память, договорённости об уроках и тестовую подписку.
Отдельный Mini App проводит trial/regular через Realtime и показывает персональный маршрут.
Реализация, подтверждённая поставка и открытая приёмка разделены в [статусе](ai_docs/status.md).

**[Единый вход в документацию](ai_docs/README.md)** · [Продукт](ai_docs/product/2026-11-08-ai-tutor-experience-v0.5.md) · [Статус и проверки](ai_docs/status.md).

CMP Android/iOS/Desktop остаётся прототипом с частью подключённых API и демонстрационными данными.
Он не является готовым Mini App из новой спецификации.

## Как устроен проект

Реализованное устройство Speaky, потоки данных, причины решений и карта исходников — в
[architecture.md](architecture.md). Ktor владеет состоянием в PostgreSQL; Python выполняет AI-вычисления.
Новый runtime не запускает legacy-маршруты и не требует Redis.

## Технологии

| Часть | Основные технологии |
| --- | --- |
| Ktor-сервер и Telegram | Kotlin, Ktor, tgbotapi, PostgreSQL, Flyway; JWT для API прототипа |
| AI-сервис | Python, FastAPI, OpenAI-compatible LLM API; состояние через Ktor foundation |
| Mini App | HTML/CSS/JavaScript, Web Audio, WebSocket relay, nginx |
| Прототип приложения | Kotlin Multiplatform, Compose Multiplatform, Ktor Client, Koin, Navigation 3, WebRTC |
| Сборка и поставка | Gradle, Docker, GitHub Actions |

Точные версии зависимостей находятся в [`cmp/gradle/libs.versions.toml`](cmp/gradle/libs.versions.toml) и [`ai-service/requirements.txt`](ai-service/requirements.txt).

## Структура репозитория

| Путь | Назначение |
| --- | --- |
| [`cmp/server/`](cmp/server/) | Ktor foundation, PostgreSQL, durable Telegram intake и jobs; изолированный legacy |
| [`mini-app/`](mini-app/) | Самостоятельный Telegram frontend: голосовые уроки и My Progress |
| [`cmp/app/`](cmp/app/) | Прототип для Android, iOS и Desktop; общая Compose UI и клиент API |
| [`cmp/core/`](cmp/core/) | Общий Kotlin-код |
| [`ai-service/`](ai-service/) | FastAPI, FoundationTutor, ContextBuilder, LearningEngine; изолированный legacy |
| [`infra/`](infra/) | Docker и скрипты развертывания сервисов и хранилищ |
| [`ai_docs/`](ai_docs/) | Продуктовые решения, архитектура, контракты, планы и инструкции по развертыванию |
| [`.github/workflows/`](.github/workflows/) | CI и ручные workflow развертывания |

## Разработка и эксплуатация

Команды запуска, проверки, переменные окружения, CI, deploy и диагностика собраны в
[едином эксплуатационном справочнике](ai_docs/integrations/operations.md).
Текущий объём и границы проверки — в [статусе](ai_docs/status.md).
