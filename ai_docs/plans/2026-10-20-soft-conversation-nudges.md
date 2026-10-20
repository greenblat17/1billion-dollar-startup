# Soft interruption nudges

Status: implemented locally in PR #40; live Telegram validation and deployment remain outstanding.

This is a separate one-shot component from daily onboarding and practice reminders. If an onboarding attempt is actively exchanging voice messages, or a practice call is open, the bot checks for five minutes of silence after its last accepted voice reply. It sends at most one gentle text per onboarding attempt or call. Onboarding gets `Continue 🎙`, which uses the existing resume action; a call gets `End call` and can also be resumed by simply sending a voice. Neither path spends a daily reminder slot or changes its schedule or copy budget.

The due query requires the latest onboarding attempt to be incomplete, or an unclosed call, and no incoming Telegram update after the bot voice. The runner checks live AI state and the matching run/call ID, then makes a durable PostgreSQL claim and checks incoming activity again. A failed or uncertain send does not automatically retry, because Telegram might have accepted it. The one-minute scheduler only considers voice replies from the past 24 hours; it does not revive older abandoned conversations. A new attempt or call gets its own one-shot opportunity.

Telegram Bot API does not provide incoming recording/typing updates to this bot. A person can still be recording a long voice at the five-minute mark, so this is a product limitation of the Telegram integration, not a reliably detectable state. The clock starts after Speaky's delivered voice rather than when the user's prior voice arrived, so server processing does not consume the grace period.

`/admin/metrics/soft-nudges` shows sends, distinct recipients, voice returns and distinct people who returned within 24 hours, complete-window conversion per send, failures and 403 blocks by flow. A return is an incoming voice after Telegram accepted the nudge; it does not prove the person read the nudge or that the nudge caused the return. Rows are unique per attempt/call. The dashboard does not expose message content. Verify live Telegram timing, both buttons, long-recording behavior, and the callback/voice path on DEV before deploying to production.
