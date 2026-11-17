# Агентская приёмка текстового Speaky

Срез: 2026-11-17. Код находится в отдельной ветке от `origin/bb/sp-02-05-v5-5`.
На DEV он не поставлен. Проверка реальной модели этой ветки пока не выполнена.

## Проверенный текущий DEV и безопасный путь

- Последний найденный `Redeploy DEV`: [43442380355](https://github.com/greenblat17/1billion-dollar-startup/actions/runs/43442380355), 13 ноября, SHA `44f7e03`. Workflow завершился успешно. Это не доказательство текущих SHA контейнеров.
- CI ветки `8620ed9` 17 ноября: Foundation integration, CMP, AI service и Mini App checks завершились успешно. CI не поставляет код на DEV.
- Публичная страница `https://speaky-miniapp-dev.109.71.241.224.sslip.io/miniapp` отвечает HTTP 200. `/health` на том же edge отвечает 404; это не health Ktor или AI service. Адрес Ktor и отдельную readiness нужно проверить оператору в DEV.
- Ktor `/telegram/webhook` принимает только Telegram secret и передаёт update в durable inbox. Подмена чужого Telegram chat ID опасна: Bot API отправил бы ответ человеку. Внутренние Foundation API требуют service token и scope, а AI `/internal/foundation/tutor/turn` требует scope. Они не являются готовым входом для тестового ученика.

## Подготовленный адаптер

Маршрут Ktor `/internal/agent-test/v1/{uuid}` включается только при `SPEAKY_ENV=dev` и `AGENT_TEST_ENABLED=true`. Оба действия требуют отдельного разрешения на DEV deploy. Запросы требуют `X-Internal-Token`; внешний reverse proxy не должен публиковать этот маршрут. UUID относится к `student_identity.provider=agent_test`, не к Telegram. У ученика нет Telegram chat ID, поэтому TrialWorker и Roadmap delivery не получают адресата.

`POST /turn` принимает `{messageId, text, receivedAt}`. `messageId` и ID ученика — UUID. `receivedAt` — Unix seconds в пределах суток до текущего времени или минуты после. Повторяйте тот же payload с тем же `messageId`; это ключ идемпотентности. Маршрут вызывает действующий `TutorClient` → AI service → FoundationTutor/native tools → PostgreSQL. Он возвращает ответ и сохранённый snapshot. `GET /state` читает snapshot того же ученика. Модель не заменяется fixture.

CLI `infra/dev/agent-dialogue.py` поддерживает два режима:

- **Фиксированные реплики**: JSON `{"name":"...","turns":["..."]}` для воспроизводимой проверки функций. Пример: `infra/dev/scenarios/dialogue-intent.json`.
- **BB агент как ученик**: Codex читает [сценарий](../../infra/dev/scenarios/bb-student-work-anxiety.md), сам выбирает очередную реплику и вызывает CLI с `--message`. После ответа он читает transcript и решает, что сказать дальше. Скрипт не вызывает вторую модель; интеллект ученика — сама подсессия BB.

Оба режима пишут JSON transcript с ответом и PostgreSQL snapshot. Рядом создаётся читаемый `.md` с репликами ученика и Саши. Файлы создаются с правами `0600`; ключи в них не пишутся. `completed` означает, что ход завершился и пришёл ответ или booking. Это не оценка качества: Codex сверяет живой диалог и сохранённое состояние с критериями сценария. Во время игры в ученика Codex должен смотреть только на ответы Саши; snapshot нужен для проверки после диалога.

Локальная проверка: `:server:compileKotlin` прошла; Python syntax, CLI help и `git diff --check` прошли. Два последовательных хода одного BB-ученика, сохранение transcript и права `0600` проверены с локальным HTTP stub. Stub не проверяет качество Саши. Живой DEV-сценарий не запускался: новая версия не поставлена и платный вызов модели Саши отдельно не разрешён.

```sh
AI_INTERNAL_TOKEN=... python3 infra/dev/agent-dialogue.py \
  infra/dev/scenarios/dialogue-intent.json \
  --base-url https://DEV_KTOR_ORIGIN \
  --output /secure/agent-dialogue-intent.json
```

Для BB агента первый ход выглядит так:

```sh
python3 infra/dev/agent-dialogue.py --message 'Я боюсь говорить на рабочих встречах' \
  --base-url https://DEV_KTOR_ORIGIN --output /secure/agent-work-anxiety.json
```

Команда возвращает ID тестового ученика и ответ Саши. Следующий ход агент придумывает сам и отправляет с тем же `--output` и `--student-id UUID`. В окружении команды нужен `AI_INTERNAL_TOKEN`; в саму командную строку и transcript его не записывайте. Codex может повторить сценарий с новым UUID, чтобы увидеть, как меняется диалог.

Перед запуском нужен проверенный DEV Ktor origin, service token, изолированный доступ к внутреннему маршруту и отдельно разрешённый платный вызов модели Саши. Эти данные не следует класть в git или transcript. Один новый UUID создаёт одного отдельного ученика; `--student-id UUID` продолжает его диалог. Не используйте ID реального Telegram пользователя. Не запускайте повтор после HTTP 402 без отдельного поручения.

## Границы приёмки

- Адаптер проверяет текстовый AI/Foundation pipeline и PostgreSQL в режиме обычного диалога. Он не проверяет Telegram webhook, durable intake, Bot API, typing, паузы, карточки или видимый Telegram UI. `/start` намеренно не поддержан: его Telegram reset и четыре приветствия требуют отдельного теста через настоящий UI.
- Voice/Realtime, Mini App WebSocket, микрофон, S3 audio archive, reconnect и barge-in требуют отдельной живой приёмки с согласованным тестовым аккаунтом.
- Результат CLI не подтверждает DEV readiness до проверки применённых миграций, SHA Ktor/AI/Mini App, health обоих сервисов и настроек маршрута.
