# One-time announcement to existing Telegram users

Status: admin-triggered delivery implemented locally; rollout and delivery pending.

The audience is every private Telegram chat already known to AI-service when the new AI image is first deployed. The AI deployment script takes a snapshot from all `metrics:funnel:user:tg-*` records and all members of `metrics:chats`, matching the two sources used by onboarding's `is_known` check. Redis stores chat IDs, not usernames: usernames may change and Telegram delivery requires chat IDs. The snapshot is never enlarged on repeat deploys. New users who arrive later receive the normal onboarding flow, not this announcement. Users who already completed onboarding are included, per the chosen audience rule.

The Telegram bot builds message entities directly: paragraph breaks stay in the message, the requested emphasis is bold, and the final CTA is an inline button. Its `campaign:onboarding` callback enters the existing forced `/onboarding` flow and the button is removed after a successful start. No onboarding state is reset by delivering the announcement itself.

An exempt existing user who sends a voice before completing onboarding keeps the ordinary dialogue. After the first successfully answered voice, the bot sends the same announcement once more with an extra line saying they can continue sending voice messages without onboarding. This in-chat follow-up has its own durable claim, independent of the admin campaign's delivery status, so a broadcast recipient can receive it once. Subsequent voice replies do not repeat it.

Run order for the target environment:

1. Deploy the AI-service and CMP server. The AI deployment automatically freezes the audience in Redis. It does not send a message.
2. Open `/admin/metrics/onboarding-campaign` with the existing metrics password. Review the audience count and rendered message.
3. Use `Отправить тест себе` with your private chat ID. Check the bold text, spacing, and Telegram button.
4. Press `Отправить выбранным пользователям` and confirm the count in the browser. The CMP bot claims one frozen recipient at a time from the authenticated AI API, sends the Telegram message, and reports `sent`, `blocked`, or `failed`. The page shows counts on refresh.

Claimed recipients are marked `pending` before a Telegram API call. A restart or ambiguous network failure leaves them out of automatic retries, because Telegram may have delivered the message. They appear under `Неясный результат` for manual review. The bot sends at roughly eight messages per second, below Telegram's normal broadcast limit; a 429 response waits once for Telegram's `retry_after`. Snapshot and delivery statuses survive restarts. A later click only processes still unclaimed members of the same frozen audience.
