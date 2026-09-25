package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.speaking.CorrectionKind

internal const val SEND_VOICE_HINT = "Please send a voice message."
internal const val QUEUE_FULL_TEXT = "Too many voice messages at once. Wait for my reply, then send again."
internal const val QUEUED_TEXT = "Got it — I'll answer in order."
internal const val ERROR_TEXT = "Something went wrong. Please send the voice message again."

internal const val GRAMMAR_LABEL = "Грамматика"
internal const val WORD_LABEL = "Выбор слова"
internal const val NATURAL_LABEL = "Нейтив сказал бы так"

internal fun correctionKindLabel(kind: CorrectionKind): String = when (kind) {
    CorrectionKind.GRAMMAR -> GRAMMAR_LABEL
    CorrectionKind.WORD -> WORD_LABEL
    CorrectionKind.NATURAL -> NATURAL_LABEL
}

private const val START_TEXT_REST =
    "I'm Speaky, your English practice buddy. Let's improve your English in real conversations\n" +
        "Ready? Send a voice message and tell me a bit about yourself 😊"

internal fun startTextMessage(firstName: String?): String {
    val name = firstName?.trim().orEmpty()
    return if (name.isNotEmpty()) {
        "Hey, $name! 👋\n$START_TEXT_REST"
    } else {
        "Hey! 👋\n$START_TEXT_REST"
    }
}
