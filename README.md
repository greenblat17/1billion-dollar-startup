# Speaking Coach

Speaky — персональный AI-репетитор Саша с характером Kora H для людей с разговорным барьером.
Telegram — постоянная переписка с репетитором. Приложение в целевой концепции — инфраструктура согласованных уроков и прогресса.

Текущий SP-01: русский текстовый диалог, память чата и trial create/read/reschedule/cancel/rebook через native tools.
Голосовые в обычном чате игнорируются. «Открыть урок» — заглушка: Mini App и проведение урока пока не реализованы.
Существующие calls, reviews, Vocabulary Builder и явные reminders сохранены отдельно.
Память нового чата `tutor:v1` и профиль звонков `learner:` не связаны; объединение отложено.

**[Единый вход в документацию](ai_docs/README.md)** · [Продукт](ai_docs/product.md) · [Статус и проверки](ai_docs/status.md).
Последняя зафиксированная DEV-выкладка — `21caf098`; новое поведение модели и Telegram UX вживую ещё не принято.
Сведения о DEV исторические и не подтверждают текущий PROD.

CMP Android/iOS/Desktop остаётся прототипом с частью подключённых API и демонстрационными данными.
Он не является готовым Mini App из новой спецификации.

## Как устроен проект

```mermaid
flowchart LR
  tg[Telegram / Speaky] <-->|webhook и Bot API| ktor[Ktor server]
  ktor -->|внутренний HTTP API| ai[FastAPI ai-service]
  ktor --> pg[(PostgreSQL)]
  ai --> redis[(Redis)]
  ai --> providers[STT / LLM / TTS]
```

Ktor принимает Telegram webhook и направляет текст в TutorRuntime AI-сервиса. LLM ведёт диалог и вызывает проверяемые инструменты памяти и trial. Для явно открытых старых звонков сохраняется отдельный STT/LLM/TTS pipeline. API и WebRTC-путь прототипа приложения показаны отдельно в [архитектуре](architecture.md).

## Технологии

| Часть | Основные технологии |
| --- | --- |
| Ktor-сервер и Telegram | Kotlin, Ktor, tgbotapi, PostgreSQL, Flyway; JWT для API прототипа |
| AI-сервис | Python, FastAPI, Redis, Groq Whisper, OpenRouter, Deepgram (опционально); OpenAI Realtime для прототипа |
| Прототип приложения | Kotlin Multiplatform, Compose Multiplatform, Ktor Client, Koin, Navigation 3, WebRTC |
| Сборка и поставка | Gradle, Docker, GitHub Actions |

Точные версии зависимостей находятся в [`cmp/gradle/libs.versions.toml`](cmp/gradle/libs.versions.toml) и [`ai-service/requirements.txt`](ai-service/requirements.txt).

## Структура репозитория

| Путь | Назначение |
| --- | --- |
| [`cmp/server/`](cmp/server/) | Ktor API, Telegram webhook, очередь голосовых сообщений, аналитика |
| [`cmp/app/`](cmp/app/) | Прототип для Android, iOS и Desktop; общая Compose UI и клиент API |
| [`cmp/core/`](cmp/core/) | Общий Kotlin-код |
| [`ai-service/`](ai-service/) | FastAPI, TutorRuntime/tools/store, отдельные voice pipeline и reviews |
| [`infra/`](infra/) | Docker и скрипты развертывания сервисов и хранилищ |
| [`ai_docs/`](ai_docs/) | Продуктовые решения, архитектура, контракты, планы и инструкции по развертыванию |
| [`.github/workflows/`](.github/workflows/) | CI и ручные workflow развертывания |

## Локальная разработка

**Gradle root — `cmp/`.** Из корня репозитория:

```bash
cd cmp
export GRADLE_USER_HOME="$HOME/.gradle"
./gradlew :server:test
./gradlew :app:shared:jvmTest
```

Из `cmp/` можно отдельно запустить Ktor командой `./gradlew :server:run` или Desktop-приложение командой `./gradlew :app:desktopApp:run` — каждый процесс занимает терминал. Без настроенного webhook Ktor работает локально в режиме HTTP для проверки `/health`; Telegram-боту нужен публичный HTTPS webhook. Для клиента адрес Ktor задаётся через `cmp/client.local.properties` по образцу [`cmp/client.local.properties.example`](cmp/client.local.properties.example). Сборка Android: `./gradlew :app:androidApp:assembleDebug`; iOS-приложение открывается из `cmp/app/iosApp` в Xcode.

AI-сервис запускается отдельно, также из корня репозитория. Перед `uvicorn` задайте `AI_INTERNAL_TOKEN`: без него сервис не стартует. Текущая фабрика также требует `OPENAI_API_KEY` и `GROQ_API_KEY`, даже если проверяется только текстовый чат. Имена переменных приведены в [`.env.example`](.env.example), детали локального запуска — в [инструкции](ai_docs/integrations/2026-09-18-run-and-ci.md).

```bash
cd ai-service
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements-dev.txt
pytest
uvicorn app.main:create_app --factory --host 127.0.0.1 --port 8090
```

Не добавляйте значения секретов в репозиторий.

## Проверка и развертывание

CI проверяет Kotlin и Python код в [CMP](.github/workflows/cmp.yml) и [AI service](.github/workflows/ai-service.yml). Push и pull request сами по себе не выкатывают сервисы. DEV и PROD развертываются вручную через workflow **Redeploy DEV** и **Redeploy PROD**; Ktor и AI-сервис могут находиться на разных хостах. Порядок, сеть, хранилища и переменные описаны в [deploy.md](ai_docs/deploy.md).

Начальная точка для остальных документов — [ai_docs/README.md](ai_docs/README.md). HTTP-контракты Telegram-клипов и CMP-приложения описаны отдельно: [clip API](ai_docs/integrations/2026-09-18-clip-session-api.md) и [mobile API](ai_docs/integrations/2026-09-18-mobile-api.md).

## Где смотреть сбой

- Мониторинг живых людей — агрегаты в Redis (`metrics:*`, `metrics:v2:*`) и панели Prometheus / Grafana на машине Ktor.
- Журнал реплик — закрытая страница истории на Ktor. Это не общий доступ к аудио.
- Логи — технический след падения в Loki и в файле сервера: стадия и код ошибки, без текста человека.

Карта того, что уже работает, потолок провайдеров и размер машин: `ai_docs/architecture/2026-10-11-telegram-mvp-acceptance.md`.

## Метрики существующего голосового пути

Доска Grafana Speaky, файл `infra/monitoring/grafana/dashboards/speaky.json`. Только Telegram, московский день. Историческая метрика `speaking_dau` считает пользователей с завершённым ходом AI, а не всех отправителей голосовых. Активные пользователи по голосовым показаны на `/admin/metrics` с начала журнала PostgreSQL. Стоимость в Grafana включает только возвращённые цены моделей; STT без цены не входит.

| Метрика | Панель | Ряд |
| --- | --- | --- |
| Завершили ход AI | Завершили голосовой ход AI по дням | `speaking_dau{client="telegram"}` |
| Частотность взаимодействия | Частотность: ходов на завершившего ход AI | `speaking_turns{client="telegram"} / speaking_dau{client="telegram"}`; ноль, если в этот день никого не было |
| Ошибки сервисов | Ошибки сервисов по дням | `speaking_errors{client="telegram"}` |
| Известная стоимость на завершившего ход AI | Учтённые расходы / завершившего ход AI по дням, $ | `(speaking_cost_micro{client="telegram"} / 1000000) / speaking_dau{client="telegram"}`; ноль, если в этот день никого не было |

Инженерные решения лежат в `ai_docs/`, не в этом файле.
