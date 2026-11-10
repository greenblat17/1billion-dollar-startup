# Карта проекта

Единый вход — [ai_docs/README](../README.md). Продукт — [Саша, AI-репетитор](../product.md).
Текущий основной путь — постоянный русский текстовый чат SP-01; старый voice onboarding удалён.
[Статус](../status.md) разделяет код, исторический DEV и открытую живую приёмку.

| Задача | Источник |
| --- | --- |
| Поведение, память чата, trial tools | `ai-service/app/tutor.py`, `tutor_tools.py`, `tutor_store.py` |
| Telegram routing и delivery | `cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/telegram/` |
| Ktor → AI DTO/HTTP | `cmp/server/src/main/kotlin/com/eliteteam/speakingcoach/ai/` |
| Voice pipeline и отдельная память звонков | `ai-service/app/pipeline.py`, `learner_store.py`, `personalization.py` |
| API и wiring | `ai-service/app/main.py`, Ktor `Application.kt`, `AppConfig.kt` |
| CMP-прототип, не Mini App | `cmp/app/` |
| VPS и CI | `infra/`, `.github/workflows/` |

[Архитектура](../../architecture.md) · [SP-01 API](../integrations/2026-11-10-sp01-tutor-chat.md) ·
[Запуск](../integrations/2026-09-18-run-and-ci.md) · [Deploy](../deploy.md).
Gradle root — `cmp/`. Общего контекста между `tutor:v1` и `learner:` пока нет.
