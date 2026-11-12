# Состояние Speaky

Срез: 2026-11-12. Локальная реализация, DEV и живая приёмка — разные стадии.

## Реализовано локально

- SP-00: PostgreSQL foundation, общий профиль/память/ContextBuilder, Lesson/Call Session,
  scopes, epoch, версии, receipts и фоновые задачи. Legacy изолирован.
- SP-01: постоянный Telegram-диалог и native booking/memory tools.
- SP-02: отдельный Mini App, OpenAI Realtime/Marin через серверный WebSocket relay,
  trial, reconnect, playback/truncation, S3-архив и Telegram followup.
- SP-03: подготовка regular-плана моделью (цель, тема, стратегия), admission 15–60 минут
  по актуальному плану и завершённым прошлым анализам, Guided Conversation и устный feedback.
  Reconnect сохраняет план и исходный конец; следующая встреча читается из backend.
  Для длинного урока есть смена provider-сессии после 50 минут в безопасной паузе.
- SP-04: согласование расписания через LLM tools, реальные встречи на 14-дневном горизонте,
  разовые встречи, переносы/отмены, напоминания и неявки. DST требует уточнения без потери вхождения.
  V40 добавляет DST issues и версии уведомлений. Старые placeholders не активируются автоматически.
- SP-03 и SP-04 объединены в родительской ветке из отдельных worktree без текстовых конфликтов.
  Совместная локальная проверка PostgreSQL/lifecycle прошла; живая приёмка ещё не выполнена.

## Проверки объединённой версии

- Python: **615 passed**, одно dependency deprecation warning.
- Frontend: **5 passed**, syntax/build прошли.
- Kotlin: **281 unit + 45 PostgreSQL integration**, failures/errors/skipped = 0; fatJar и detekt прошли.
- Проверены schedule.agree → job claim/apply → regular admission → voice-scoped lesson.next,
  гонка admission/cancel (4 повтора) и подавление пустого regular followup без Telegram-отправки.
- Проверены 205 локальных ссылок; git diff --check прошёл.
- Модели, Realtime, S3 и Telegram в локальных тестах подставные. Это не доказательство живого качества урока.

## DEV и ограничения

SP-03/SP-04 не отправлены и не развёрнуты. Последняя подтверждённая здесь поставка SP-02:
backend/Mini App `175f5f5`, AI `214b44d` с semantic VAD medium и latency timings
([run](https://github.com/greenblat17/1billion-dollar-startup/actions/runs/43234153691)).
Пользователь оценил разговор как достаточно хороший для проверки полного trial.
Полная педагогическая приёмка SP-02, SP-03 и естественного scheduling-диалога SP-04 остаётся открытой.
Замер 1057 ms (7 ответов) относился к commit→планированию playback до изменения VAD;
он не включает всё ожидание конца речи и не доказывает фактическую слышимость.

SeaweedFS на DEV: приватный `speaky-trial-audio-dev`, сеть `speaky-storage`.
Запись аудио подтверждалась прежними звонками. Backup и S3 lifecycle ещё требуют проверки;
30-дневная очистка приложения сохранена. Архив-before-forward добавляет задержку в обе стороны.
Открыты живые проверки: полный trial/regular, RU/EN, паузы и перебивания, speaker/headphones,
reconnect, завершение, сохранность контекста/аудио, reminders/followup и 60-минутная смена транспорта.
Настоящая оплата/подписка, полный roadmap не реализованы; demo receipt не даёт entitlement.
PROD и merge main не выполнялись. Платные прогоны и реальные сообщения в интеграции SP-03/SP-04 не выполнялись.

[Архитектура](../architecture.md) · [Эксплуатация](integrations/operations.md) ·
[SP-03](plans/2026-11-12-sp03-regular-lessons.md) · [SP-04](plans/2026-11-12-sp04-scheduling.md)
