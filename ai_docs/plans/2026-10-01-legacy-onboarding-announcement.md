# One-time announcement to existing Telegram users

Status: implemented locally; live rollout and delivery pending.

The audience is every private Telegram chat already known to AI-service when the operator runs `snapshot`. It is the union of all `metrics:funnel:user:tg-*` records and all members of `metrics:chats`, matching the two sources used by onboarding's `is_known` check. The snapshot is stored in Redis and is never enlarged on a repeat run. New users who arrive later receive the normal onboarding flow, not this announcement. Users who already completed onboarding are included, per the chosen audience rule.

The announcement uses Telegram HTML formatting. Only the requested emphasis is bold; the final CTA is an inline button. Its `campaign:onboarding` callback enters the existing forced `/onboarding` flow and the button is removed after a successful start. No onboarding state is reset by delivering the announcement itself.

Run order for the target environment:

1. Deploy the CMP server with the new callback handler, and the AI-service image containing `app.legacy_onboarding_campaign`.
2. On the AI host, freeze the audience **before** sending: `docker exec ai-service python -m app.legacy_onboarding_campaign snapshot`. Keep a record of the printed count.
3. Preview copy and count: `docker exec ai-service python -m app.legacy_onboarding_campaign preview`.
4. Make the **matching environment's** bot token available in the AI host shell as `TELEGRAM_BOT_TOKEN` without printing it. Run `docker exec -e TELEGRAM_BOT_TOKEN ai-service python -m app.legacy_onboarding_campaign test --chat-id <private-chat-id>`. Check the rendered bold text and button in Telegram.
5. Run `docker exec -e TELEGRAM_BOT_TOKEN ai-service python -m app.legacy_onboarding_campaign send`. `--limit N` permits a small first batch. Each recipient is recorded in `campaign:2026-10-01-legacy-onboarding:status` before the API call; reruns skip any recorded recipient. Inspect `sent`, `blocked`, `failed:*`, `rate_limited`, `uncertain`, or `pending` statuses before considering manual retries. `uncertain` and `pending` may have been delivered; do not automatically retry them.

The script sends at roughly eight messages per second, below Telegram's normal broadcast limit. A 429 response waits for Telegram's `retry_after`. The snapshot and delivery statuses survive process restart. An interrupted snapshot must finish before `send` can run. This is a one-time operational campaign; it is not launched automatically on deploy.
