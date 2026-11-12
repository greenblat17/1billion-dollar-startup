# Состояние Speaky

Срез: 2026-11-12. Актуальная сводка стадии проекта: что из продуктовой идеи уже работает и чего ещё нет.

| Часть продукта | Текущее состояние |
| --- | --- |
| Постоянный чат Саши и управление trial | Реализованы; полная живая приёмка диалога и Telegram UX открыта |
| Общий профиль, память и контекст text/voice/learning | Реализованы и локально проверены в SP-00; Ktor/PostgreSQL — владелец состояния |
| Надёжность чата и фоновой работы | Сохранение входа, восстановление хода, receipts, jobs, retention/delete и импорт TutorStore реализованы |
| Изоляция старых функций | Новый runtime не монтирует legacy routes/workers и не требует Redis; исходники и серверные данные сохранены |
| Голосовой урок и Mini App | SP-02 реализован в рабочем дереве, включён в коде без feature flag; локальные тесты прошли, живая проверка поручена пользователю |
| Учебный цикл, расписание, оплата и прогресс | Trial briefing/качественный анализ, предварительное расписание, встречи-заглушки и demo-оплата реализованы; регулярные уроки, настоящая оплата, roadmap и прогресс остаются SP-03+ |

## Проверка и поставка

DEV storage 2026-11-12: SeaweedFS 4.53 развёрнут отдельно на AI-хосте, `/opt/speaky-storage`.
Созданы приватный `speaky-trial-audio-dev` и отдельный ключ AI с Read/Write/List только этого bucket.
Постоянный диск, закрытая Docker-сеть, без публичных портов; credentials сгенерированы на хосте и не выводились.
Живой storage probe через deployed AI image прошёл: put, signed get, list versions, delete,
сохранность после пересоздания, запрет anonymous/cross-bucket/public ACL. Тестовые объекты удалены.
AI env/сеть ещё не подключены, приложение не перезапускалось. Retention и backup пока не настроены.
Локально deploy AI дополнен второй сетью для внутреннего S3 endpoint; helper на обеих сетях проверил DNS/S3 auth/default route.
Изменение deploy-скрипта ещё не поставлено, GitHub env secret пока не обновлён.
Инструкция и границы проверки: [infra/storage](../infra/storage/README.md).

Причина обрыва `0468b04f316b43a69b8e7b12a97d0fff` установлена по обоим DEV-сервисам:
11:09:24 UTC, S3 `put_object` → `NoCredentialsError`; STT перед этим завершился успешно.
Фактический STT: OpenRouter `assemblyai/universal-3-5-pro`, выбран через `GROQ_BASE_URL`/`STT_MODEL`.
Урок сохранился `in_progress`, эпизод завершён как `technical_disconnect`; 2 события текста сохранены,
2 аудио-записи остались `reserved`, сохранённое аудио не подтверждено. Прежний 402 к этому обрыву не относится.
Локально добавлены ранняя проверка credentials до admission и проверка S3 в DEV readiness; не поставлены.
Python 558 passed; 7 TrialIntegrationTest на отдельном PostgreSQL passed, включая reconnect/ownership/fencing.
Это не живая приёмка голоса. Для восстановления звонка требуется подключить созданное хранилище к AI.

Mini App вынесен в `mini-app/src`, отдельный build/контейнер и DEV workflow; AI static routes удалены.
Перенос подготовлен к поставке; DEV-миграция ещё не выполнена. Python 558 passed; frontend build/Node tests и 2 edge migration tests прошли.
Локальный Docker CLI неисправен; проверка nginx-контейнера предусмотрена в CI и пока не подтверждена.

[Логирование активного runtime](audits/2026-11-12-runtime-logging.md): добавлены причины отказов провайдеров,
безопасные stack frames, trace_id между AI/backend, ID урока/эпизода, журнал streaming TTS,
ошибки S3/Telegram/DB/jobs и диагностика Mini App. Локально: Python 555 passed; Kotlin 281 passed,
fatJar/detekt и Node syntax check. Метрики не менялись. Пользователь разрешил push и redeploy обоих DEV-сервисов;
поставка cb3c626 подтверждена run 43205973385 (AI/backend/readiness success); живая проверка новых логов отдельно. Команды поиска — в operations.md.

DEV диагностика 2026-11-12: на приветствие модель вызвала create_trial_booking, проверка отклонила
invalid_evidence; бронь не создавалась. Диагностика поставлена: run 43199872157 success.
Исправлена сборка text-контекста: текущий event исключается из истории по ID и передаётся отдельно,
без подмены /start. Инструкция требует evidence из текущего сообщения; отказ возвращает модели
объяснение ошибки аргументов, а не сбоя сервера. Intent по-прежнему выбирает LLM, без keyword routing.
Локально основной Python-набор: 544 passed; отдельный набор новых/диагностических проверок: 9 passed
(4 новых тестовых случая). Живое поведение после изменения ещё не проверено.
Пользователь разрешил commit/push и AI-only redeploy DEV; поставка этого исправления ожидается.

Исправлен CI run 43196289189: TrialIntegrationTest перенесён из обычного `:server:test` в
`:server:foundationIntegrationTest`. Локально проверены оба набора: unit без TEST_POSTGRES_URL, integration с отдельной базой.

SP-02: **код присутствует в DEV cb3c626; голосовая приёмка не пройдена, S3 ещё не подключён к AI**.
[HTTPS edge Mini App](integrations/operations.md#dev-https-edge-sp-02--проверено-2026-11-11) работает;
пользовательский WebSocket дошёл до STT и записи текста, затем упал на архиве аудио. PROD не поставлен этой задачей.
Ниже — исторические локальные проверки SP-02 до текущего исправления:
Python **544 passed** (1 dependency deprecation warning); Kotlin **277 unit + 26 PostgreSQL integration**, без failed/skipped;
`:server:buildFatJar` и `detekt` прошли. Межсервисные Python→Ktor→PostgreSQL проверки: **6 passed**.
Проверено на отдельной локальной PostgreSQL 14.10 с 39 миграциями; Node syntax checks прошли.
Провайдеры, S3 и Telegram в соответствующих тестах подставные. Эти результаты не доказывают качество
живого разговора, barge-in/эхо, задержку, доступность Kora TTS или работу Telegram WebView.
После исправления STT переиспользуется GroqSpeechToText с существующими настройками; Deepgram для trial не нужен.
Пользователь поручил выполнить только локальные тесты и проверяет реальную функциональность сам.
[План и границы приёмки SP-02](plans/2026-11-11-sp02-trial-lesson.md),
[настройка включения](integrations/operations.md#sp-02-mini-app-и-пробный-голосовой-урок).
Платные вызовы, Telegram-сообщения и deploy приложений в SP-02 не выполнялись.
Commit/push SP-02 разрешены пользователем 2026-11-12.
Настройка HTTPS proxy выполнена отдельным поручением пользователя.

Ниже сохранены исторические сведения SP-00. Последующая проверка 2026-11-12: AI health 200,
HTTPS Mini App 401 от прежней версии; это не подтверждает поставку SP-02.
Пользователь сообщил об обновлении DEV env; значения GitHub secrets не читались.

SP-00 локально завершён. **DEV-импорт завершён; совместный запуск новой версии AI/backend ещё требуется. PROD и живая приёмка не выполнены.**
Зафиксированы: Python **525** (1 warning), Kotlin **277**, PostgreSQL **19**, HTTP **5**, real Redis import **1** passed.
Kotlin/PG без skipped; fatJar и detekt прошли. Чистый AI runtime без Redis и реальные pg_dump/pg_restore проверены.
Это результаты реализации поверх `60d4085`, а не новые прогоны документационной задачи.
[Критерии и тестовые доказательства](plans/2026-11-10-sp00-foundation.md#локальные-доказательства-реализации).

Последний подтверждённый DEV: SP-01 `21caf098`, `@vlama_bot`, [run 42818030607](https://github.com/greenblat17/1billion-dollar-startup/actions/runs/42818030607).
Он не подтверждает поставку SP-00 или состояние PROD. Платные модельные прогоны остановлены после HTTP 402.

[Архитектура](../architecture.md) объясняет устройство; [эксплуатация](integrations/operations.md) — запуск, импорт и восстановление.

DEV cutover: резервные копии Redis и PostgreSQL созданы и проверены; импортирован 1 настоящий Telegram TutorStore, повтор импорта идемпотентен (Student/import/marker: 1/1/1). Четыре прежних synthetic DEV probe исключены явно, исходники сохранены в Redis. HTTPS AI→backend:443 проверен с доверенным сертификатом (health 200; сервисная авторизация проходит, без scope — 403). Старый AI остановлен для сохранения frozen source; до redeploy новый бот недоступен. До согласованного redeploy оба приложения остановлены; PostgreSQL/Redis работают. В deploy добавлено доверие отдельному backend CA, readiness переведён с legacy endpoints на foundation; ключи и приватные данные не включены в git.
