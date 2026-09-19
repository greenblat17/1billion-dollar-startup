# Deploy

Как Speaky попадает на серверы. Секреты в документе **не** перечислять значениями — только имена GitHub secrets. Локальный compose — не прод; см. `integrations/2026-09-18-run-and-ci.md`.

## Что крутится на машине

Три Docker-контейнера. **Prod** сегодня часто один сервер (Ktor host-сеть + AI/Redis на той же машине). **DEV** — два независимых VPS: так моделируем прод, где Ktor и AI **не** обязаны быть у одного провайдера и без LAN датацентра.

| Контейнер | Образ | Сеть | Назначение |
| --- | --- | --- | --- |
| `speaking-coach` | `speaking-coach:local` | **host** | Ktor, порт из `SERVER_PORT` в `.env` (обычно 443), Telegram webhook |
| `ai-service` | `ai-service:local` | `speaking-coach` | FastAPI, порт **8090** на всех интерфейсах |
| `redis` | `redis:7-alpine` | `speaking-coach` | диалог, **127.0.0.1:6379**, volume `speaking-coach-redis` |

Ktor смотрит на ai-service через `AI_SERVICE_BASE_URL` — адрес, который виден **с машины Ktor** (публичный IP/DNS другого провайдера), не `127.0.0.1` и не VPC Timeweb. Хост-порт FastAPI — `AI_SERVICE_PORT` в `.env` на AI (по умолчанию 8090); скрипт делает `-p $AI_SERVICE_PORT:8090`. В URL Ktor тот же порт. Docker публикует его на всех интерфейсах, а `restrict-8090.sh` режет чужие source в `DOCKER-USER` / `INPUT`: пускает loopback и адреса из `/opt/ai-service/8090.allow`. Без файла снаружи порт закрыт. Общий LAN между хостами не делаем: он не переживёт разъезд по провайдерам. Позже вместо голого HTTP — TLS или туннель (WireGuard и т.п.). Redis остаётся на AI-хосте, наружу и на Ktor не публикуем. `REDIS_URL=redis://redis:6379/0`.

На диске:

- `/opt/speaking-coach/` — fat JAR, `Dockerfile.runtime`, `deploy-remote.sh`, `.env`, **`tls.crt` / `tls.key` (кладёт человек, CI их не генерирует)**
- `/opt/ai-service/` — `app/`, Dockerfile, requirements, deploy-скрипты, `.env`, **`8090.allow` (кладёт человек: IPv4 Ktor, по одному в строке; CI не пишет)**

Рестарт `ai-service` **не** должен `docker rm redis`. Скрипт `infra/redis/deploy-remote.sh` если контейнер `redis` уже есть — только `start` + connect к сети, volume не трогает.

```mermaid
flowchart LR
  redeploy[Redeploy]
  ktor[speaking_coach]
  ai[ai_service]
  redis[(redis)]
  tls[tls_on_disk]
  redeploy -->|cmp-server| ktor
  redeploy -->|ai-server| ai
  ai -->|redis_if_missing| redis
  ktor --> ai
  tls --> ktor
```

**Имена:** семейство хостов — `cmp-server` и `ai-server` (галки Redeploy, `DEV_*` secrets). Каталог, Docker-имя и прод-SSH остаются `ai-service` / `AI_SERVICE_*`; URL в Ktor — `AI_SERVICE_BASE_URL`.

## Тесты авто, выкат только Redeploy

`push` / `pull_request` по path: **CMP** (`cmp/**`, `infra/server/**`) и **AI service** (`ai-service/**`, `infra/ai-service/**`, `infra/redis/**`) — package и тесты. На сервер не едут.

Выкат — [`.github/workflows/redeploy.yml`](../.github/workflows/redeploy.yml): Actions → Redeploy → **Run workflow**. Две галки `cmp-server` и `ai-server` (обе default on). Снятая галка = job skipped, workflow зелёный. Обе сняты = fail до SSH.

Куда: ветка `main`/`master` → **prod** (environment `deploy`, беспрефиксные repo secrets). Любая другая → **DEV** (environment `dev`, `DEV_*`). Environments — только approve; секреты в Environment не кладём.

Concurrency: `ktor-prod` / `ktor-dev` / `ai-prod` / `ai-dev`, `cancel-in-progress: false` (очередь, не cancelled).

### Prod — repo Actions secrets (как были)

SSH: `CMP_SERVER_HOST`, `CMP_SERVER_USER`, `CMP_SERVER_SSH_KEY`, `AI_SERVICE_HOST`, `AI_SERVICE_USER`, `AI_SERVICE_SSH_KEY`.

Runtime: раннер собирает `.env` из ячеек `TELEGRAM_BOT_TOKEN`, `TELEGRAM_WEBHOOK_SECRET`, `TELEGRAM_WEBHOOK_URL`, `AI_SERVICE_BASE_URL`, `AI_INTERNAL_TOKEN`, `GROQ_API_KEY`, `OPENAI_API_KEY`. URL и токен — **только secrets**, не `vars`. `scp` файла на хост. Скрипт env не читает — только `--env-file`. TLS PEM уже на диске. Redis не `docker rm`. Один и тот же `AI_INTERNAL_TOKEN` в `.env` Ktor и AI.

Смена бота: новый `TELEGRAM_BOT_TOKEN`, Redeploy cmp-server с `main`, у старого токена `deleteWebhook`. Имя в Telegram — BotFather.

### Dev — те же скрипты, другие ячейки

SSH: `DEV_CMP_SERVER_HOST` / `USER` / `SSH_KEY`, `DEV_AI_SERVER_HOST` / `USER` / `SSH_KEY`.

Runtime — непрозрачные блобы (оператор заполняет, CI не парсит ключи):

- `DEV_CMP_SERVER_ENV` → `/opt/speaking-coach/.env`
- `DEV_AI_SERVER_ENV` → `/opt/ai-service/.env` (сюда же `REDIS_URL` и тот же `AI_INTERNAL_TOKEN`, что у Ktor)

Пустой блоб — fail на раннере до SSH. TLS на DEV кладёт человек.

CMP Android / iOS / Desktop на сервер не едут. Jobs ai-service в памяти при рестарте пропадают; Redis-диалоги остаются.

## Ручной деплой (если файлы уже на хосте)

Положить `.env`, вызвать скрипт. Env на хост не передаём.

```bash
bash /opt/speaking-coach/deploy-remote.sh
bash /opt/ai-service/deploy-redis.sh
bash /opt/ai-service/deploy-remote.sh
```

Проверка: `docker ps` — три имени выше; с хоста Ktor `curl -k https://127.0.0.1/health`; с хоста AI или с Ktor `curl http://<ai-server>:8090/health`.
