# Состояние Speaky

Срез: 2026-11-12. Локальная реализация, поставка и живая приёмка разделены ниже.

| Часть продукта | Состояние |
| --- | --- |
| Telegram-знакомство и trial booking tools | Реализованы; полная живая приёмка диалога открыта |
| PostgreSQL foundation, общая память и ContextBuilder | Реализованы; Ktor владеет Student/Lesson/Call Session, scopes, epoch, версиями и receipts |
| Mini App | Отдельный frontend `mini-app/`, контейнер и DEV workflow; AI содержит API/голосовой relay |
| SP-02 Realtime | STT → LLM → TTS заменён на OpenAI `gpt-realtime-2.1`, согласованный Marin; поставлен на DEV, живая приёмка открыта |
| Trial и продолжение | Diagnose + Demonstrate → Prescribe, устный feedback, вопрос о комфорте и Telegram follow-up; педагогика требует живой проверки |
| Регулярные уроки/оплата | Предварительное расписание, встречи-заглушки и demo receipt; настоящая оплата и регулярный цикл остаются SP-03+ |

## Локальная готовность Realtime

`RealtimeTrialSession` подключён к shipping root. Telegram/native tools, ownership/версии/idempotency,
общий ContextBuilder и foundation lifecycle сохранены. Старые trial STT/TTS не создаются в новом runtime.
WebRTC рассмотрен первым; выбран серверный WebSocket relay ради серверного архива обоих потоков.
PCM16 mono 24 kHz сохраняется в S3 с PostgreSQL receipt до пересылки. Это добавляет RTT и небольшие объекты;
влияние на задержку и нагрузку пока не измерено. Архив доступен постранично, без прежнего обрезания на 2000 objects.

Provider semantic VAD, interruption/truncation, дедупликация событий и server-owned finish tool реализованы.
Playback отчёты ограничены сервером; сгенерированный текст не считается услышанным автоматически.
Reconnect сохраняет Lesson и контекст; mute/end/disconnect разделены. Микрофон включён при подключении.
Постоянный provider key остаётся на сервере. Смешанные версии frontend/AI отклоняются по протоколу.
Сохранены безопасные provider errors/correlation IDs, usage и аудиодиагностика; 402/429 не ретраятся.
Исправлен найденный тестами FrozenInstanceError, который скрывал исходный отказ foundation.

Проверки текущего дерева:
- Python: **585 passed**, одно dependency deprecation warning.
- Kotlin: **281 unit + 27 PostgreSQL integration**, без failed/skipped; fatJar/detekt прошли.
- Python→Ktor→PostgreSQL HTTP: **6 passed**; исправлена устаревшая подмена TutorModel в тесте.
- Frontend: **4 tests passed**, syntax/build прошли; проверены микрофон, reconnect, PCM и playback при сетевых паузах.
- PostgreSQL: отдельная локальная 14.10; пагинация проверена на 2105 synthetic segments с ownership.

Модели и S3 в новых runtime-тестах подставные. Эти результаты не доказывают live transport,
качество речи, реальные S3 timings, эхо, Telegram WebView или стоимость trial.

## Поставка и эксплуатация

По поручению пользователя ревизия `175f5f5` запушена и поставлена на DEV: backend, AI и Mini App.
[Run 43230527834](https://github.com/greenblat17/1billion-dollar-startup/actions/runs/43230527834) завершился успешно.
Readiness: оба контейнера работают без рестартов/OOM и startup errors; health 200,
внутренние auth/scopes и AI→backend TLS проверены, S3 credentials/list versions доступны.
Хеш Realtime-модуля внутри AI и frontend JS через публичный HTTPS совпадают с поставленной ревизией.
В AI подтверждены модель `gpt-realtime-2.1`, голос `marin` и наличие `OPENAI_REALTIME_API_KEY` без раскрытия значения.
Доступ аккаунта к модели, запись/удаление новых аудиообъектов и живой звонок этим не проверены.
Платные smoke/benchmark не запускались; пользователь проводит живую проверку самостоятельно.
PROD/merge не выполнялись.

DEV Mini App: `https://speaky-miniapp-dev.109.71.241.224.sslip.io/miniapp`.
По последним проверенным отчётам SeaweedFS работает: приватный `speaky-trial-audio-dev`, сеть `speaky-storage`,
endpoint `http://speaky-storage:8333`; AI S3 операции подтверждены прежними звонками. Инфраструктура и ключи не менялись.
Backup и S3 lifecycle ещё требуют проверки/настройки; 30-дневная очистка приложения сохранена.

Последний доказанный дефект прежнего тракта: безусловная отмена TTS по speech_start спустя 0.7–1.2 s.
Эхо предполагалось, но не доказано. Realtime также может ложно реагировать на динамик; исправление эха не заявлено.
Живая матрица: несколько RU/EN обменов, паузы, barge-in, динамик/наушники, mute/reconnect/end,
сохранность контекста и полного аудио, Telegram follow-up, первый слышимый звук p50/p95 и стоимость trial.
Heartbeat или один проигранный ответ не считаются приёмкой. Платных вызовов и сообщений пользователям не было.

[План SP-02](plans/2026-11-11-sp02-trial-lesson.md), [архитектура](../architecture.md),
[конфигурация и DEV-переход](integrations/operations.md#sp-02-mini-app-и-пробный-голосовой-урок),
[аудит диагностики](audits/2026-11-12-runtime-logging.md).

## Сохранённая заметка исходного SP-00

Следующая заметка относится к прежней проверке SP-00/ревизии 42f492b, а не к текущему локальному прогону:

Проверка AC/DoD: семь архитектурных критериев покрыты реализацией и локальными тестами. Полная приёмка остаётся открытой: [foundation CI 42894967776](https://github.com/greenblat17/1billion-dollar-startup/actions/runs/42894967776) завершился до тестов — runner не нашёл `postgresql-client-16`. Удалённый integration pass для `42f492b` не подтверждён; остальные PR checks прошли.
