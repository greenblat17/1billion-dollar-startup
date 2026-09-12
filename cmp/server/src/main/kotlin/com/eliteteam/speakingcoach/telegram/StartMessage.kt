package com.eliteteam.speakingcoach.telegram

internal val START_INFO_TEXT = """
This is a walkie-talkie for English speaking practice.

Send a voice message — I'll reply with a voice message.
If you send several in a row, I'll answer them in order.

Tap the microphone and speak.
""".trim()

internal const val SEND_VOICE_HINT = "Please send a voice message."
internal const val QUEUE_FULL_TEXT = "Too many voice messages at once. Wait for my reply, then send again."
internal const val QUEUED_TEXT = "Got it — I'll answer in order."
internal const val ERROR_TEXT = "Something went wrong. Please send the voice message again."
