# PROD Telegram alerts

Status: prepared, not deployed. The recipient is the private chat of @alexgusev93 with @speaky_alerts_bot. A direct Bot API test message reached that chat on 2026-10-30. This proves the bot-to-chat path, not Grafana alert delivery.

Grafana on the CMP host evaluates the rules in `infra/monitoring/alerts/speaky.yml` every minute. PROD provisions these rules and one Telegram contact point. DEV does not. The contact point sends firing and resolved notifications, groups by rule, and repeats a continuing alert no more often than every four hours. Messages contain the rule and job labels, not user content, tokens, or provider responses.

Rules:

- Ktor or AI `/health` blackbox probe is unsuccessful for two minutes.
- Either Ktor or AI metrics scrape is unsuccessful for three minutes.
- At least three Telegram voice requests fail or hit a full queue in five minutes, sustained for one evaluation minute. This is an operational threshold, not the user-visible failure rate on `/admin/metrics/errors`.

The repo Actions secrets `TELEGRAM_ALERT_BOT_TOKEN` and `TELEGRAM_ALERT_CHAT_ID` supply the contact point. The PROD CMP deploy writes them to `/opt/speaking-coach/monitoring/alerts.env` with mode 600. Only the Grafana container receives this env file. Do not put either value in Git, logs, or chat. A missing secret stops the PROD deploy before SSH.

Grafana, Prometheus, and blackbox run on the CMP host. If the whole host or its network fails, this stack cannot send an alert. An independent external uptime check is a separate later step. Before calling the rollout complete, verify Grafana loaded all three rules, test the contact point from Grafana, and trigger and recover one rule in a controlled way.
