# Kora H pilot analytics

Use the existing protected `/admin/metrics/calls` page and its Moscow-day
today/7/30-day filter. The pilot section compares **free calls only**. A call
belongs to its opening day. Menu and selection taps belong to their callback
day, so they are not a strict same-day funnel.

- `voice_menu_clicked`: Telegram received a tap on the announcement's
  `voice:menu` callback. Count taps and distinct chats. This callback is
  currently used only by the pilot announcement. A tap does not prove that
  the menu was delivered.
- `voice_character_tapped`: Telegram received a Speaky or Kora H selector
  tap. Count Kora H taps and distinct chats. Rejected or failed selections
  remain taps, not completed changes.
- `call_character`: the actual character stored by the AI service for a
  call, linked by call ID. Record it for button and direct-voice openings.
  Compare distinct chats, calls, repeat chats with 2+ calls, calls with a
  user voice, calls with an audio reply, voice turns, recognized user speech,
  and calls with voice failures or text fallback. Historical calls without
  this fact remain `unknown` and are excluded from the comparison.
- `feedback_offered` is recorded only after Telegram accepts the feedback
  prompt. `feedback_rated` records the first accepted thumbs-up, neutral, or
  thumbs-down rating. Both link to the call ID and show the raw sample per
  character. The text comment stays in the existing feedback table.

Flyway V23 copies recent callback taps from the existing interaction audit
before its seven-day content expires. It does not backfill a call character:
the older call event has no reliable character. The existing 30-day event
retention and 50,000-event dashboard cap apply. The page shows write failures
and truncation. Feedback comments remain in the existing feedback table
below the pilot section. Do not use rated calls divided by all calls as a
rating response rate; use delivered feedback prompts as the denominator.

After deploying AI and CMP, check one new call for each character and both
ways of opening a call. Confirm the pilot totals against read-only event
queries. Do not interpret raw call volume as preference: this is a 12-user
opt-in pilot, not a randomized experiment.
