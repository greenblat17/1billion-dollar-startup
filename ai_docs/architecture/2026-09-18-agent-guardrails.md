# What not to touch (unless the task is exactly that)

## Compiled `bin/` trees

`cmp/server/bin/` and `cmp/app/desktopApp/bin/` are **IDE/compiler output**, not source. They show up untracked. Never edit them; never commit them. Change `src/` only. `cmp/.gitignore` ignores `**/build/` but not `bin/`.

## Template Compose `App.kt`

`cmp/app/shared/.../App.kt` is the JetBrains “Click me” sample. Desktop `main.kt` still calls `App()`. Do not grow product UI there unless the task is the CMP client. Greeting string `Hello, $to!` in `cmp/core/.../GreetingUtil.kt` is template noise; Ktor `/` uses it.

## BotFather / Telegram identity

The **bot username, display name, and token** are set in Telegram BotFather / GitHub secrets, not in this repo. In-product persona is **Speaky** (start text, LLM system prompt, OpenRouter `X-Title`). Do not “fix” Telegram by inventing a new `@username` or rewriting BotFather fields. Change copy in `StartMessage.kt` / `sessions.py` / `llm.py` if the task is in-app wording.

## Secrets and VPS credentials

Do not put tokens, SSH keys, VPS passwords, or `.env` values into docs or commits. `.env` is gitignored. Template names only: `.env.example`.

Выкат — только `workflow_dispatch` Redeploy DEV / Redeploy PROD, одинаково с `main` и с PR. В Deployments только environment `deploy-dev` и `deploy-prod`, без required reviewers. Секреты серверов — **repository Actions secrets**, не Environment secrets. CMP packages склеивают клиентский HTTPS из SSH `PROD_CMP_SERVER_HOST` (ветка `main`) или `DEV_CMP_SERVER_HOST`, не из блоба `.env` и не отдельной var. Беспрефиксные repo secrets (`CMP_SERVER_*`, `AI_SERVICE_HOST`, `TELEGRAM_*`, `GROQ_API_KEY`, `OPENAI_API_KEY`, `AI_INTERNAL_TOKEN`, `AI_SERVICE_BASE_URL`) для CI больше не нужны. Внутренние URL вроде `AI_SERVICE_BASE_URL` в vars не класть.

**Prod** (префикс `PROD_`, содержимое блобов оператор кладёт сам; workflow Redeploy PROD):

- SSH: `PROD_CMP_SERVER_HOST`, `PROD_CMP_SERVER_USER`, `PROD_CMP_SERVER_SSH_KEY`, `PROD_AI_SERVER_HOST`, `PROD_AI_SERVER_USER`, `PROD_AI_SERVER_SSH_KEY`
- Runtime KV: `PROD_CMP_SERVER_ENV` → `/opt/speaking-coach/.env`, `PROD_AI_SERVER_ENV` → `/opt/ai-service/.env` (`REDIS_URL` только во втором; `AI_INTERNAL_TOKEN` один и тот же в обоих)

**Dev** (префикс `DEV_`, содержимое блобов оператор кладёт сам; workflow Redeploy DEV):

- SSH: `DEV_CMP_SERVER_HOST`, `DEV_CMP_SERVER_USER`, `DEV_CMP_SERVER_SSH_KEY`, `DEV_AI_SERVER_HOST`, `DEV_AI_SERVER_USER`, `DEV_AI_SERVER_SSH_KEY`
- Runtime KV: `DEV_CMP_SERVER_ENV` → `/opt/speaking-coach/.env`, `DEV_AI_SERVER_ENV` → `/opt/ai-service/.env` (`REDIS_URL` только во втором; `AI_INTERNAL_TOKEN` один и тот же в обоих)

VPS layout (mechanism): `/opt/speaking-coach/` (JAR, TLS, `.env`), `/opt/ai-service/` (image sources, `.env`, `8090.allow`). Redis container name `redis`, Docker network `speaking-coach` **on the AI host only**. Postgres container name `postgres`, **127.0.0.1:5432 on the Ktor host only**. Hosts may be different providers — no same-DC private LAN. **Do not `docker rm` Redis** on deploy (`infra/redis/deploy-remote.sh` creates if missing, else leaves running). **Do not `docker rm` Postgres** (`infra/postgres/deploy-remote.sh`).

## Stub vs real notes

Production notes are `wrong|||better` pairs. Do not invent random note strings or echo the same audio as a fake pipeline.

## Research doc vs code

Do not implement against `ai_docs/researches/2026-09-14-stt-llm-tts-ai-service.md` as if it were current (polling bot, empty Python service).
