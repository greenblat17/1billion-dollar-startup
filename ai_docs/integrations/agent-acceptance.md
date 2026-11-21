# Агентская приёмка текстового Speaky

Срез: 2026-11-17. Текстовый адаптер уже использовался на DEV; голосовой клиент находится в отдельной ветке от PR #58 и ещё не поставлен.

## Проверенный текущий DEV и безопасный путь

- Последний найденный `Redeploy DEV`: [43442380355](https://github.com/greenblat17/1billion-dollar-startup/actions/runs/43442380355), 13 ноября, SHA `44f7e03`. Workflow завершился успешно. Это не доказательство текущих SHA контейнеров.
- CI ветки `8620ed9` 17 ноября: Foundation integration, CMP, AI service и Mini App checks завершились успешно. CI не поставляет код на DEV.
- Публичная страница `https://speaky-miniapp-dev.109.71.241.224.sslip.io/miniapp` отвечает HTTP 200. `/health` на том же edge отвечает 404; это не health Ktor или AI service. Адрес Ktor и отдельную readiness нужно проверить оператору в DEV.
- Ktor `/telegram/webhook` принимает только Telegram secret и передаёт update в durable inbox. Подмена чужого Telegram chat ID опасна: Bot API отправил бы ответ человеку. Внутренние Foundation API требуют service token и scope, а AI `/internal/foundation/tutor/turn` требует scope. Они не являются готовым входом для тестового ученика.

## Подготовленный адаптер

Маршрут Ktor `/internal/agent-test/v1/{uuid}` включается только при `SPEAKY_ENV=dev` и `AGENT_TEST_ENABLED=true`. После перехода на готовые образы настройку включает **DEV test config**. Маршрут принимает запросы только с loopback DEV-хоста и с `X-Internal-Token`; внешний reverse proxy не используется. UUID относится к `student_identity.provider=agent_test`, не к Telegram. У ученика нет Telegram chat ID, поэтому TrialWorker и Roadmap delivery не получают адресата.

`POST /turn` принимает `{messageId, text, receivedAt}`. `messageId` и ID ученика — UUID. `receivedAt` — Unix seconds в пределах суток до текущего времени или минуты после. Повторяйте тот же payload с тем же `messageId`; это ключ идемпотентности. Маршрут вызывает действующий `TutorClient` → AI service → FoundationTutor/native tools → PostgreSQL. Он возвращает ответ и сохранённый snapshot. `GET /state` читает snapshot того же ученика. Модель не заменяется fixture.

CLI `infra/dev/agent-dialogue.py` поддерживает два режима:

- **Фиксированные реплики**: JSON `{"name":"...","turns":["..."]}` для воспроизводимой проверки функций. Пример: `infra/dev/scenarios/dialogue-intent.json`.
- **BB агент как ученик**: Codex читает [сценарий](../../infra/dev/scenarios/bb-student-work-anxiety.md), сам выбирает очередную реплику и вызывает CLI с `--message`. После ответа он читает transcript и решает, что сказать дальше. Скрипт не вызывает вторую модель; интеллект ученика — сама подсессия BB.

Оба режима пишут JSON transcript с ответом и PostgreSQL snapshot. Рядом создаётся читаемый `.md` с репликами ученика и Саши. Файлы создаются с правами `0600`; ключи в них не пишутся. `completed` означает, что ход завершился и пришёл ответ или booking. Это не оценка качества: Codex сверяет живой диалог и сохранённое состояние с критериями сценария. Во время игры в ученика Codex должен смотреть только на ответы Саши; snapshot нужен для проверки после диалога.

Локальная проверка: `:server:compileKotlin` прошла; Python syntax, CLI help и `git diff --check` прошли. Два последовательных хода одного BB-ученика, сохранение transcript и права `0600` проверены с локальным HTTP stub. Stub не проверяет качество Саши. Живой DEV-сценарий не запускался: новая версия не поставлена и платный вызов модели Саши отдельно не разрешён.

```sh
AI_INTERNAL_TOKEN=... python3 infra/dev/agent-dialogue.py \
  infra/dev/scenarios/dialogue-intent.json \
  --base-url https://127.0.0.1:DEV_SERVER_PORT --loopback-tls \
  --output /secure/agent-dialogue-intent.json
```

Для BB агента первый ход через локальный доступ выглядит так:

```sh
python3 infra/dev/agent-dialogue.py --message 'Я боюсь говорить на рабочих встречах' \
  --base-url https://127.0.0.1:DEV_SERVER_PORT --loopback-tls \
  --output /secure/agent-work-anxiety.json
```

После активации новой схемы запустите **DEV acceptance** с `mode=text`, `learner_message` и ожидаемыми SHA Ktor и AI. Для продолжения передайте тот же `student_id`. Платный вызов требует явного разрешения. Транскрипт остаётся на закрытом DEV-хосте. В Actions доступна техническая сводка. После HTTP 402 автоматического повтора нет.

Команда локального CLI возвращает ID тестового ученика и ответ Саши. Следующий ход агент отправляет с тем же `--output` и `--student-id UUID`. В окружении команды нужен `AI_INTERNAL_TOKEN`; в саму командную строку и transcript его не записывайте.

Перед запуском нужен проверенный DEV Ktor origin, service token, изолированный доступ к внутреннему маршруту и отдельно разрешённый платный вызов модели Саши. Эти данные не следует класть в git или transcript. Один новый UUID создаёт одного отдельного ученика; `--student-id UUID` продолжает его диалог. Не используйте ID реального Telegram пользователя. Не запускайте повтор после HTTP 402 без отдельного поручения.

## Границы приёмки

- Адаптер проверяет текстовый AI/Foundation pipeline и PostgreSQL в режиме обычного диалога. Он не проверяет Telegram webhook, durable intake, Bot API, typing, паузы, карточки или видимый Telegram UI. `/start` намеренно не поддержан: его Telegram reset и четыре приветствия требуют отдельного теста через настоящий UI.
- Голосовой клиент ниже проверяет реальный Mini App WebSocket, Realtime и сохранение аудио после DEV-поставки. Реальный микрофон, восприятие речи человеком и качество живого разговора требуют отдельной приёмки.
- Результат CLI не подтверждает DEV readiness до проверки применённых миграций, SHA Ktor/AI/Mini App, health обоих сервисов и настроек маршрута.

## DEV-only голосовой клиент

`infra/dev/agent-voice.py` работает через закрытые SSH-туннели к DEV-хостам. Ktor `POST /internal/agent-test/v1/{student}/voice-grant` требует service token и loopback, выдаёт одноразовый 60-секундный допуск для принадлежащего этому `agent_test` ученику исполняемого Trial или regular lesson. V65 добавляет отдельную роль допуска в PostgreSQL. AI service принимает допуск в первом кадре существующего `/miniapp/voice` только при `SPEAKY_ENV=dev`, `AGENT_TEST_ENABLED=true`, service token в WebSocket header и peer из loopback либо точного default gateway Docker bridge. Последний адрес возникает при SSH-туннеле через опубликованный порт контейнера; произвольный private peer не подходит. Foundation поглощает допуск при `open`, проверяет роль, срок, epoch, владельца урока и отсутствие Telegram identity. Обычный Telegram `initData` маршрут сохраняется. Никакой публичный reverse proxy для внутренних маршрутов не настраивается.

**DEV test config** включает `agent_test` на обоих сервисах на ограниченное время. При выкладке готового образа сохраняется действующая конфигурация хоста. Для проверки требуются новые версии Ktor и AI service, применённая V65 и оба health endpoint. Выключенный флаг или PROD отклоняет этот путь.

Перед запуском скопируйте CLI и JSON сценария на DEV-хост в закрытый каталог. На хосте нужны Python 3.12 и `websockets==15.0.1` (из `ai-service/requirements.txt`); `ffmpeg` нужен для преобразования или синтеза, но готовый mono PCM WAV 24 кГц читается без него. Сценарий с `text` также требует `say` или `espeak-ng`. Проверьте loopback порты Ktor и AI service без публикации маршрутов наружу.

Сначала создайте отдельного `agent_test` ученика через текстовый CLI выше, дождитесь реальной записи Trial и возьмите `lesson.id` из его snapshot. Для regular lesson нужен настоящий admission: активный entitlement и готовая подготовка урока. Клиент их не обходит. Если текстовый агент создал mock checkout, `POST /internal/agent-test/v1/{student}/mock-payment` с `{ "checkoutId": "UUID" }` на loopback проводит его через существующий mock billing adapter. Он работает только при настроенном mock provider и только для checkout этого `agent_test` ученика; реальные деньги не списываются. После этого дождитесь реальной записи и подготовки regular lesson. Запускайте урок только после его `starts_at`. Оба origin должны быть loopback DEV-хоста. Токен передаётся через окружение, не через аргументы или файлы.

Если DEV billing выключен и checkout не существует, локально подготовлен отдельный `POST /internal/agent-test/v1/{student}/regular-entitlement`. Он пока **не поставлен на DEV**. Маршрут зарегистрирован только с `SPEAKY_ENV=dev` и `AGENT_TEST_ENABLED=true`; запрос требует loopback и `X-Internal-Token`. В транзакции проверяются владелец `agent_test` и отсутствие Telegram identity, затем создаётся двухдневный test entitlement без автопродления. Повтор при действующем entitlement возвращает его ID. Это тестовый вход к обычному `TrialAccess.open`, а не успешная оплата или проверка биллинга. После поставки вызов без тела выполняется на DEV-хосте через закрытый loopback; ответ не содержит token.

```sh
AI_INTERNAL_TOKEN=... python3 infra/dev/agent-voice.py infra/dev/scenarios/voice-short.json \
  --ktor https://127.0.0.1:DEV_SERVER_PORT --ai http://127.0.0.1:AI_PORT \
  --student AGENT_TEST_UUID --lesson OWNED_LESSON_UUID \
  --output /secure/agent-voice-run
```

Сценарий: `{"turns":[{"text":"Hello, I want to practise speaking."},{"audio":"/secure/second-turn.wav"}],"reconnectAfterTurn":1}`. Для `text` клиент синтезирует речь локальной `say` или `espeak-ng`, затем `ffmpeg` переводит её в mono PCM 24 кГц. Надёжный вход без TTS/ffmpeg на DEV-хосте — заранее подготовленный PCM WAV: mono, signed 16-bit, 24 кГц. Для другого `audio` формата нужен `ffmpeg`. Оба типа помечаются в JSON; микрофон не используется. Не более пяти коротких ходов за запуск. PCM отправляется кадрами по 100 мс в том же `realtime-pcm-v1` протоколе, затем тишина даёт VAD завершить ход. Клиент принимает `audio_start`, бинарный PCM, `audio_end`, `interrupt`, `reconnect` и отправляет `played` только после времени аудио; при interrupt отправляет неполный playback ack. Это имитация времени прослушивания, а не доказательство, что человек услышал ответ.

`dialogue.json` содержит попытки, события relay, тайминги, ID аудио, read-after-write snapshot после ходов и итоговый Foundation snapshot с source IDs и архивными метаданными S3. `dialogue.md` содержит сохранённые транскрипты из Foundation, если ASR и ответ успели сохраниться. WAV файлы содержат ответное аудио. Выход создаётся с правами `0700`/`0600`. Допуск и service token в отчёт не пишутся. `completed_transport` означает прохождение короткого сценария, не успешную голосовую приёмку. При HTTP 402 остановите прогон без повторов. Для оценки пауз, barge-in, голоса и педагогики прослушайте WAV и проверьте ID источников и состояние; затем проведите отдельный тест с реальным микрофоном.

В [первом DEV-прогоне](../acceptance/runs/agent-voice-dev-2026-11-17.md) два assistant audio items перекрылись, а бинарные PCM кадры не содержали item ID. Поэтому привязка отдельных WAV к provider item ID неоднозначна. Клиент помечает такие WAV как `ambiguous_overlap` и ждёт подтверждения всех ответов перед завершением хода; исправленная версия ещё не проверена на DEV. Это ограничение есть и у Mini App, который принимает тот же протокол.
