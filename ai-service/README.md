# ai-service

Docker запускает `app.speaky_main:create_app`.
FoundationTutor, Realtime relay, ContextBuilder и LearningEngine обращаются к PostgreSQL foundation через Ktor backend;
Python не владеет второй актуальной копией профиля. Новый runtime не требует Redis и не запускает legacy workers/routes.

Действующий голосовой код находится в [`app.realtime_voice`](app/realtime_voice/).
Старый FastAPI runtime, Clip pipeline и голосовой STT/TTS находятся в [`app.legacy`](app/legacy/).
Путь `app.main:create_app` сохранён для совместимости.
Старый runtime использует `requirements-legacy.txt`.

- [Карта документации](../ai_docs/README.md)
- [Статус реализации и приёмки](../ai_docs/status.md)
- [SP-00: запуск, TutorStore import, диагностика и восстановление](../ai_docs/integrations/operations.md#sp-00-новый-runtime-и-перенос-текущего-чата)
- [Текущий Telegram-контракт](../ai_docs/integrations/tutor-chat.md)

Import marker требуется до включения чата. Локальные integration checks не подтверждают DEV/PROD cutover или живое качество модели.
