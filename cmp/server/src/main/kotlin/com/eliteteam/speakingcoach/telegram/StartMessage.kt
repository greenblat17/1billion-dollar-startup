package com.eliteteam.speakingcoach.telegram

internal const val SEND_VOICE_HINT = "Please send a voice message."
internal const val QUEUE_FULL_TEXT = "Too many voice messages at once. Wait for my reply, then send again."
internal const val QUEUED_TEXT = "Got it — I'll answer in order."
internal const val ERROR_TEXT = "Something went wrong. Please send the voice message again."

private const val START_TEXT_REST =
    "I'm Speaky, your English practice buddy. Let's improve your English in real conversations. Ready? Send a voice message and tell me a bit about yourself. 😊"

internal fun startTextMessage(firstName: String?): String {
    val name = firstName?.trim().orEmpty()
    return if (name.isNotEmpty()) {
        "Hey, $name! 👋 $START_TEXT_REST"
    } else {
        "Hey! 👋 $START_TEXT_REST"
    }
}
