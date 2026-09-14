# Local infra

```bash
cd infra
cp ../.env.example .env
sed -i 's|^TELEGRAM_BOT_TOKEN=.*|TELEGRAM_BOT_TOKEN=123456:ABC|' .env
docker compose up --build
```

В `.env` подставь токен от [@BotFather](https://t.me/BotFather). Без него контейнер сервера стартует, поллер — нет.

По умолчанию поднимаются:

- `server` — Ktor + Telegram long polling, http://127.0.0.1:8080/health
- `ai-service-stub` — заглушка clip API, http://127.0.0.1:8090/health

В Telegram: `/start`, затем голосовое. Заглушка отвечает тем же voice.

Реальный ai-service (STT → LLM → TTS), порт на хосте 8091:

```bash
# в infra/.env
AI_SERVICE_BASE_URL=http://ai-service:8090
GROQ_API_KEY=...
OPENAI_API_KEY=...
docker compose --profile llm up --build
```

Остановка: `Ctrl+C` или `docker compose down`.
