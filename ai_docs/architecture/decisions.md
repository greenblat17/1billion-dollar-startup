# Почему реализация устроена так

Текущее продуктовое направление — [постоянный репетитор Саша](../product.md).
Ниже — причины границ существующего кода, а не план развития прежнего продукта.

| Решение | Причина | Где проверить |
| --- | --- | --- |
| LLM ведёт диалог, native tools изменяют состояние | Гибкость беседы без анкеты; операции должны иметь проверяемый результат | `ai-service/app/tutor.py`, `tutor_tools.py` |
| Отдельный TutorStore | SP-01 заменяет входной путь без переписывания calls/reviews; общий профиль отложен | `ai-service/app/tutor_store.py`, `main.py` |
| `tutor:v1` не синхронизируется с `learner:` | Объединение явно исключено из текущего этапа; одинаковый session ID не создаёт общий контекст | `main.py`, `learner_store.py` |
| Redis lock, CAS, receipts | Повтор Telegram update или сбой после записи не должен повторять операцию | `tutor_store.py`, `TutorTelegramChat.kt` |
| Карточка строится из store | Текст модели не является доказательством записи; при сбое нужен фактический fallback | `tutor.py`, `tutor_tools.py` |
| Trial-запись без запуска урока | SP-01 проверяет отношения и договорённости; lesson runtime и Mini App ещё не реализованы | [Контракт](../integrations/2026-11-10-sp01-tutor-chat.md) |
| Calls, vocabulary, reminders сохранены отдельно | Это существующие системы с данными; их удаление не входило в замену onboarding | [Совместимость](../integrations/legacy-compatibility.md) |
| Learner/review слой отделён от onboarding | Calls, reviews и vocabulary нуждаются в нём после удаления onboarding service | `learner_model.py`, `learner_store.py`, `review_content.py`, `review_score.py` |
| Сырые сообщения ограничены семью днями | История разговора и долговечные факты имеют разный жизненный цикл | `dialogue.py`, `tutor_store.py`, [аудит](2026-10-05-conversation-audit-logging.md) |
| PostgreSQL у Ktor, Redis у AI | Ktor хранит app sessions/аудит, AI — диалог и состояние; сервисы могут жить на разных хостах | [Deploy](../deploy.md) |
| CMP-прототип не используется как Mini App | Его app API/WebRTC обслуживают другой уже написанный клиент; новый lesson flow ещё отсутствует | [Архитектура](../../architecture.md) |

Сохранённые namespace, DTO и миграции объясняют совместимость, но не задают новые продуктовые требования.
Старый CEFR-score, streak и vocabulary lesson не являются пятью показателями прогресса из v0.5.
Новая функция не считается реализованной по наличию похожего старого endpoint.
Проверки кода, состояние DEV и живая приёмка разделены в [статусе](../status.md).
