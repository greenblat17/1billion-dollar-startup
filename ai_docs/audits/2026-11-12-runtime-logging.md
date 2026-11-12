# Аудит логирования активного Speaky runtime

Срез: 2026-11-12, код bbc3c48 и read-only проверка DEV. Только логирование; метрики, dashboards и alerts исключены по поручению пользователя. Результаты аудита ниже описывают исходные пробелы; реализация описана в следующем разделе.

## Реализация и проверка

Добавлены diagnostics.py и RuntimeDiagnostics.kt: общий trace-контекст, безопасные stack frames,
коды провайдеров, очищенные диагностические поля тела ответа и request ID/Retry-After при их наличии.
Подключены STT/LLM, streaming TTS, trial session, S3 cleanup, backend commands, DB/Telegram/jobs.
Mini App отправляет ограниченный отчёт об ошибке через отдельный HTTP route с Telegram auth;
клиент получает errorId при серверном отказе. При отсутствии сети отчёт доставить невозможно.

Локально: Python 555 passed; Kotlin 281 passed, fatJar/detekt; Node syntax check.
Тесты проверяют 402 body, отказ TTS до/после chunks, перенос trace, изоляцию параллельных запросов,
SQLState, исключение payload/секретов и независимость результата от отказа логирования.
Платные вызовы не запускались. Живая проверка новых событий после deploy остаётся открытой.

Произвольный текст внутренних исключений не копируется: сохраняются тип, безопасный код и frames.
Это не полная копия ответа провайдера: пишутся диагностические поля, остальные поля исключаются.
Уведомления и фоновые задания имеют собственный trace, а не trace исходной переписки;
связь с уроком передаётся для trial follow-up. Ограничение частоты серверных повторяющихся логов
и release SHA из CI остаются отдельными улучшениями. Существующие retry/delivery правила не менялись.

## Вывод

Логи собираются, но обработчики часто уничтожают причину ошибки до записи. Нет общего идентификатора, связывающего пользовательский вход, команды backend и вызовы провайдеров. Поэтому больше HTTP access logs проблему не решит.

На DEV проверено: Docker logging driver local, 3 × 10 MB для обоих приложений; Alloy запущен; Loki возвращает свежие записи ai-service и speaking-coach. Два события trial_client_metric текущего инцидента дошли до Loki. В AI также настроен файловый лог с ротацией в deploy script. Это подтверждает доставку проверенных событий, не полноту всех журналов или гарантию хранения.

Инцидент: 10:41:05 UTC STT получил HTTP 402 от OpenRouter. Записаны модель, статус, класс исключения и длительность. Тело отказа не записано. Причина предыдущего обрыва 10:40:48 UTC не установлена. Отсутствие записи TTS не доказывает, что TTS не вызывался.

## Найденные пробелы и исправления

P0 — необходимы для расследования следующего сбоя звонка. P1 — остальные активные пути, также входят в исправление логирования.

| Приоритет | Источник | Что теряется | Требуемое изменение |
| --- | --- | --- | --- |
| P0 | `ai-service/app/provider_call_log.py:logged_provider_call` | В error_code записан класс исключения, а не код провайдера. Нет message, reason, limit_source, request ID и Retry-After | Общий извлекатель ошибок провайдеров: HTTP status, provider code/type, очищенное ограниченное message, reason, limit_source, remedy_hint, request ID, Retry-After. Сохранять неизвестную причину как unknown, не угадывать по 402 |
| P0 | `ai-service/app/trial_providers.py:KoraTrialTTS.stream` | Streaming TTS обходит общий журнал вызовов | Логировать старт, первый chunk и итог потока: completed/error/cancelled, длительность и объём. Ошибка при чтении потока тоже должна попасть в журнал |
| P0 | `ai-service/app/trial_api.py:voice` | Любое исключение становится connection_lost без серверной записи; BackendFailure тоже не логируется | Перед безопасным ответом клиенту записывать этап, тип/код, очищенную причину, frames стека, error_id и идентификаторы звонка |
| P0 | `ai-service/app/trial_session.py:respond` | Только trial_response_failed, без исключения и этапа | Различать context, LLM, validation, TTS, socket send, audio archive; записывать исходную ошибку до fallback |
| P0 | `trial_session.py:run/record`, `trial_audio.py:cleanup` | Ошибки S3, записи метаданных, завершения и cleanup подавляются; gather возвращает исключения без разбора | Сохранять первичную ошибку; вторичные ошибки cleanup логировать отдельно. Для S3 — operation, code/status, request ID. Не заменять первичную ошибку последней |
| P0 | `speaky_main.py`, `foundation_tutor.py`, `trial_session.py`, `foundation_client.py` | Tool request_key не связан с provider calls. В DEV source=unknown, user_id=null; нет lesson/call/turn ID | Общий диагностический контекст для asyncio tasks и межсервисных запросов. Связывать trace_id, lesson_id, call_session_id, turn_id, operation_id. Не использовать execution scope как trace ID |
| P1 | `foundation_client.py:_request` | TransportError заменяется transport_outcome_unknown без исходного типа/стека; ошибки JSON теряют контекст | Логировать method, шаблон path, action, operation ID, duration, status, тип transport failure и frames стека. Сохранить существующую семантику неопределённого исхода записи |
| P1 | `foundation_api.py:compute` | Разные исключения превращаются в provider_unavailable/invalid_job без подробностей | Лог до преобразования; job_id, kind, attempt/fence, stage, error_id |
| P1 | `foundation/FoundationRoutes.kt:foundationResponse` | SQLException превращается в storage_unavailable без диагностической причины; validation errors также не различимы | Запись SQLState, vendor code, операции и безопасного стека; не выводить SQL parameters и полный SQL exception message без очистки |
| P1 | `telegram/SpeakyTelegram.kt`, `foundation/TelegramTextIntake.kt`, `ai/TutorClient.kt` | Общие warn без исключения/ID; ответ AI теряется за check(status) | Сохранять код ответа AI, этап intake/compute/reply/card и общий trace; для Telegram — error_code, очищенное description, retry_after, известный/неизвестный исход доставки |
| P1 | `foundation/FoundationWorker.kt`, `foundation/TrialWorker.kt` | Общий worker_unavailable; failed/unknown notice не объясняет конкретный отказ | Job/notice ID, attempt, fence, этап, status/code, решение retry/stop/unknown. Unknown delivery не ретраить ради диагностики |
| P1 | `miniapp/app.js` | onclose теряет code/reason/wasClean; микрофон, AudioContext, worklet и buffer overflow не видны серверу. Ошибки async onmessage не ловятся внешним try | Ограниченные клиентские события этапов и отказов, attempt_id до соединения, безопасный error name/code, close code, AudioContext state; отдельная ограниченная доставка после потери WS. Ошибка доставки telemetry не должна ломать звонок |
| P1 | `tutor_diagnostics.py` | Уже есть полезные stage/code/validation fields, но unknown ошибки без стека, request_key локален | Сохранить существующие события, добавить общий trace, безопасные frames; не записывать аргументы native tools целиком |

## Контракт записи

Каждая аварийная ветка активного runtime должна оставлять серверное событие до преобразования в пользовательский fallback. Для сетевого исчезновения клиента не обещаем известную причину: записываем наблюдаемый close/timeout и unknown origin.

Общие поля: UTC timestamp, level, service, environment, release SHA, event, stage, outcome, trace_id, error_id. По наличию: lesson_id, call_session_id, turn_id, operation_id, job_id/notice_id, provider, model, attempt, elapsed_ms. События начала и конца позволяют обнаружить незавершённую операцию после crash; отсутствие terminal event не выдавать за успех.

Локальная неожиданная ошибка: exception type, очищенное bounded message, stack frames (файл/функция/строка, без locals). Ошибка API: status, код и безопасные поля тела, request ID провайдера. Бизнес-отказ и нормальное прерывание речи — отдельные outcome, не server error.

Клиент получает нейтральный текст и error_id для обращения; внутренние сведения остаются в журнале. Идентификаторы контекста не являются auth и не расширяют доступ. Клиентские IDs проверяются и ограничиваются по длине; нельзя доверять произвольным полям телеметрии.

Не писать Authorization, API keys, Telegram bot token/полный Bot API URL, initData, execution scope, signed S3 URL, аудио, полный prompt, transcript и аргументы tools. Не заменять это запретом логировать все ошибки: извлекать диагностические поля и очищать строки. Исключения HTTP SDK могут включать тело запроса; простой logger.exception без проверки не подходит. Повторяющиеся ошибки ограничивать по частоте, сохраняя первую причину и число повторов.

## Порядок реализации

1. Общий Python formatter ошибок и контекст; покрыть STT/LLM и streaming TTS, все границы trial и S3. Без нового провайдера и без возврата legacy runtime.
2. Передача trace между Ktor/AI; логирование backend commands, DB, Telegram и jobs. Не менять бизнес-семантику ретраев/идемпотентности.
3. Клиентские ошибки Mini App и error_id. Отдельно проверить отказ микрофона до открытия WS и обрыв транспорта.
4. Read-only проверка доставки записей в Loki после согласованного deploy. Сохранить команды поиска одного trace в operations.md. Метрики не входят в задачу.

## Проверки при реализации

| Сбой | Что должно остаться в логах |
| --- | --- |
| STT 402 с несколькими вариантами тела | Exact safe provider code/reason/message + model + request ID + trace; без повтора платного запроса |
| TTS отказ до headers / обрыв после chunks | Различимые этапы и итог одного вызова; число отправленных bytes |
| Barge-in / shutdown | cancelled с причиной; не фальшивый provider failure |
| LLM timeout / неверный JSON / неверная schema | Различимые причины, stage и безопасный стек без model response |
| S3 отказ + ошибка удаления при cleanup | Первичная и вторичная ошибки связаны, ни одна не затирается |
| Backend timeout / malformed response / SQL failure | Исходная категория и operation ID доступны с обеих сторон |
| Telegram HTTP отказ / неизвестная доставка | Код и решение о доставке; нет повторного сообщения из-за логирования |
| WS network loss / heartbeat timeout / client overflow | Close или timeout + этап; не выдуманная причина сети |
| Микрофон запрещён / AudioWorklet failure | Клиентское событие доступно даже без установленного голосового WS, если HTTP доступен |
| Два параллельных ученика / reconnect | Trace-контексты не смешиваются; новый episode связан с тем же lesson |
| Секреты и персональные данные внутри error message | Sentinel values отсутствуют во всех логах; нужные error fields сохранены |
| Поломка logging/export | Бизнес-операция не меняет результат; отказ экспорта наблюдаем и ограничен |

Mock-тесты доказывают заполнение полей, защиту данных и поведение обработчиков. Живая проверка доказывает доставку событий в Loki и связь реального звонка. Для первого не нужны платные вызовы; второе выполняется отдельно. Старое несохранённое тело 402 нельзя восстановить этими изменениями.

## Границы аудита

Просмотрены shipping roots, text tutor/tools, голосовой trial, frontend, фоновые jobs, Telegram adapter, HTTP/DB boundaries, logging helpers и конфигурация доставки. Legacy исходники рассматривались только для сравнения wiring, не как активный runtime. PROD не проверялся. В ходе аудита ключи и конфигурация DEV не менялись; платных запросов и Telegram-сообщений агент не делал. Таблица фиксирует исходные пробелы; локальный результат и границы проверки указаны в разделе реализации.
