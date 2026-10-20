package com.eliteteam.speakingcoach.telegram

/** A callback can start only the topic attached to the latest accepted reminder in its chat. */
internal suspend fun handleReminderPracticeButton(
    callbackData: String,
    chatId: Long,
    messageId: Long,
    store: ReminderButtonStore?,
    startCall: suspend (String) -> Unit,
): Boolean {
    val token = reminderPracticeToken(callbackData) ?: return false
    val question = store?.active(token, chatId, messageId)?.openingQuestion ?: return false
    startCall(question)
    return true
}
