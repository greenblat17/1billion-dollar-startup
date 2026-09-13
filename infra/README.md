# Local infra

```bash
cd infra
cp ../.env.example .env
sed -i 's|^TELEGRAM_BOT_TOKEN=.*|TELEGRAM_BOT_TOKEN=123456:ABC|' .env
docker compose up --build
```

В `.env` подставь токен от [@BotFather](https://t.me/BotFather). Без него контейнер сервера стартует, поллер — нет.

Поднимаются:

- `server` — Ktor + Telegram long polling, http://127.0.0.1:8080/health
- `ai-service-stub` — заглушка clip API, http://127.0.0.1:8090/health

В Telegram: `/start`, затем голосовое. Заглушка отвечает тем же voice.

Остановка: `Ctrl+C` или `docker compose down`.
