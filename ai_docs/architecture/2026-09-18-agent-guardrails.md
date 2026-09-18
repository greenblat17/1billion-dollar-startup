# What not to touch (unless the task is exactly that)

## Compiled `bin/` trees

`cmp/server/bin/` and `cmp/app/desktopApp/bin/` are **IDE/compiler output**, not source. They show up untracked. Never edit them; never commit them. Change `src/` only. `cmp/.gitignore` ignores `**/build/` but not `bin/`.

## Template Compose `App.kt`

`cmp/app/shared/.../App.kt` is the JetBrains “Click me” sample. Desktop `main.kt` still calls `App()`. Do not grow product UI there unless the task is the CMP client. Greeting string `Hello, $to!` in `cmp/core/.../GreetingUtil.kt` is template noise; Ktor `/` uses it.

## BotFather / Telegram identity

The **bot username, display name, and token** are set in Telegram BotFather / GitHub secrets, not in this repo. In-product persona is **Speaky** (start text, LLM system prompt, OpenRouter `X-Title`). Do not “fix” Telegram by inventing a new `@username` or rewriting BotFather fields. Change copy in `StartMessage.kt` / `sessions.py` / `llm.py` if the task is in-app wording.

## Secrets and VPS credentials

Do not put tokens, SSH keys, VPS passwords, or `.env` values into docs or commits. `.env` is gitignored. Template names only: `.env.example`.

GitHub **environment** `deploy`. Secret **names** (values live in GitHub):

- Server: `CMP_SERVER_HOST`, `CMP_SERVER_USER`, `CMP_SERVER_SSH_KEY`, `TELEGRAM_BOT_TOKEN`, `TELEGRAM_WEBHOOK_SECRET`, optional `TELEGRAM_WEBHOOK_URL`, `AI_SERVICE_BASE_URL` (or repo **vars** for the last two).
- AI: `AI_SERVICE_HOST`, `AI_SERVICE_USER`, `AI_SERVICE_SSH_KEY`, `GROQ_API_KEY`, `OPENAI_API_KEY`.

VPS layout (mechanism): `/opt/speaking-coach/` (JAR, TLS, `.env`), `/opt/ai-service/` (image sources, `.env`). Redis container name `redis`, Docker network `speaking-coach`. **Do not `docker rm` Redis** on deploy (`infra/redis/deploy-remote.sh` creates if missing, else leaves running).

## Stub vs real notes

Do not copy stub `NOTE_POOL` phrasing into the LLM. Production notes are `wrong|||better` pairs.

## Research doc vs code

Do not implement against `ai_docs/researches/2026-09-14-stt-llm-tts-ai-service.md` as if it were current (polling bot, empty Python service).
