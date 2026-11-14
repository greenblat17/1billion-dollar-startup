# ai-service

Новый shipping entrypoint: `app.speaky_main:create_app` из `ai-service/Dockerfile`.
FoundationTutor, Realtime relay, ContextBuilder и LearningEngine обращаются к PostgreSQL foundation через Ktor backend;
Python не владеет второй актуальной копией профиля. Новый runtime не требует Redis и не запускает legacy workers/routes.

`app.main` и прежние clip/call/vocabulary реализации сохранены отдельно.
Их API описаны как legacy-контракты; они не монтируются новым composition root.

- [Карта документации](../ai_docs/README.md)
- [Статус реализации и приёмки](../ai_docs/status.md)
- [SP-00: запуск, TutorStore import, диагностика и восстановление](../ai_docs/integrations/operations.md#sp-00-новый-runtime-и-перенос-текущего-чата)
- [Текущий Telegram-контракт](../ai_docs/integrations/tutor-chat.md)

Import marker требуется до включения чата. Локальные integration checks не подтверждают DEV/PROD cutover или живое качество модели.
