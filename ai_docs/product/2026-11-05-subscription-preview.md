# Telegram subscription preview after onboarding

Status: local UI preview. Checkout and paid access are not implemented or enabled.

After a new user receives the onboarding level card, send a separate subscription preview message. Keep the result card and detailed feedback available. The preview offers three durations at the prices selected for this draft:

| Duration | Draft price |
| --- | ---: |
| 1 month | 300 ₽ |
| 3 months | 500 ₽ |
| 1 year | 1 700 ₽ |

Each button selects a duration and updates the same message. The message says checkout is coming soon and practice remains free for now. Do not create an order, charge the user, or grant or deny access from these buttons. Existing legacy users do not receive the preview. A voluntary onboarding restart can show it again when the new result is delivered.

The intended later product flow is to require an active subscription before ordinary practice. That requires a payment decision, verified entitlement state, expiry and renewal rules, and a gate for every practice entry point. This preview does not claim those pieces are present.

Telegram's [digital goods payments rules](https://core.telegram.org/bots/payments-stars) require Telegram Stars for digital services sold inside a bot, including when an external website exists. Do not add an external checkout link to this Telegram preview. Revisit prices and currency presentation when real payment is designed.
