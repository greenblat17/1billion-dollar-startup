# PROD Telegram alerts

Status: prepared, not deployed. The recipient is the private chat of @alexgusev93 with @speaky_alerts_bot. A direct Bot API test message reached that chat on 2026-10-30. This proves the bot-to-chat path, not Grafana alert delivery.

Grafana on the CMP host evaluates the rules in `infra/monitoring/alerts/speaky.yml` every minute. PROD provisions these rules and one Telegram contact point. DEV does not. The contact point sends firing and resolved notifications, groups by rule, and repeats a continuing alert no more often than every four hours. Messages contain the rule and job labels, not user content, tokens, or provider responses.

Rules:

- Ktor or AI `/health` blackbox probe is unsuccessful for two minutes.
- Either Ktor or AI metrics scrape is unsuccessful for three minutes.
- At least three Telegram voice requests fail or hit a full queue in five minutes, sustained for one evaluation minute. This is an operational threshold, not the user-visible failure rate on `/admin/metrics/errors`.

The repo Actions secrets `TELEGRAM_ALERT_BOT_TOKEN` and `TELEGRAM_ALERT_CHAT_ID` supply the contact point. The PROD CMP deploy writes them to `/opt/speaking-coach/monitoring/alerts.env` with mode 600. Only the Grafana, daily-report, and credit-monitor containers receive this env file. Do not put either value in Git, logs, or chat. A missing secret stops the PROD deploy before SSH.

Grafana 11.3.1 requires the Telegram contact point's `chatid` to be a string. Grafana expands a numeric environment variable before parsing the YAML, even when the placeholder is quoted. The monitoring deploy validates the numeric ID from `alerts.env` and renders it into the quoted YAML field before starting Grafana. An unrendered numeric ID makes Grafana fail at startup and the monitoring proxy return 502.

Grafana, Prometheus, and blackbox run on the CMP host. If the whole host or its network fails, this stack cannot send an alert. An independent external uptime check is a separate later step. Before calling the rollout complete, verify Grafana loaded all three rules, test the contact point from Grafana, and trigger and recover one rule in a controlled way.

## Daily audience message

The same PROD bot sends one report to the same chat at 23:00 Europe/Moscow. `infra/monitoring/reporter/daily_report.py` reads the existing authenticated AI `/internal/metrics/overview` endpoint for the current Moscow date and the six preceding dates. It uses `habits.days` from the shared HabitAnalytics calculation, not the older `speaking_dau` chart series. DAU is unique normalized Telegram chats with a completed AI turn today, preliminary at 23:00. WAU is the union of those chats over the seven dates ending today. The message shows both dates and states that the values are for completed AI turns; Telegram delivery is not part of this measure. See [habit analytics](2026-10-19-habit-analytics.md) for the population and history limits.

The reporter runs in a separate container on the CMP host. It receives the alert bot credentials, the OpenRouter management key, the AI URL, and a read-only AI internal token file. Immediately before sending, it requests the current OpenRouter account balance and includes the check time in Moscow time. If that check fails, the report says `Баланс OpenRouter: данные недоступны` and still sends DAU and WAU. It stores the last successfully sent Moscow date in a persistent Docker volume to avoid a second report after restart. If the habit source is unhealthy or a value is unavailable, it sends an explicit `данные недоступны` message rather than a zero. A Telegram send failure is retried every five minutes; a crash between a successful send and saving the date can duplicate that day's message. A whole-host outage also stops this report.

## OpenRouter credit alert

A separate PROD container checks [OpenRouter account credits](https://openrouter.ai/docs/api/api-reference/credits/get-credits) every five minutes. Available USD is `total_credits - total_usage` from `GET /api/v1/credits`. The endpoint requires a management key. `OPENROUTER_MANAGEMENT_KEY` is a separate repository Actions secret, staged as `/opt/speaking-coach/monitoring/openrouter.env` with mode 600 and passed to the credit-monitor and daily-report containers. It must belong to the billing account used by the PROD OpenRouter inference key. The inference key's own spending limit is a separate measure and is not inferred from the account balance.

The bot sends one warning when the observed balance first falls below $5. It sends a recovery message after a later check reaches at least $5, which arms the next warning. State persists across container restarts. After three failed credit checks, the bot sends one technical warning and later a recovery message when checks work again. An invalid or missing response is never treated as a zero balance. A five-minute polling interval and network latency bound detection time; a full CMP-host outage prevents delivery.
