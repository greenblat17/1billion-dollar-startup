package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.ai.PersonalizedReminderResponse
import java.time.LocalDate

internal data class PracticeReminderCopy(val text: String, val kind: String, val openingQuestion: String)

/** A user alternates daily; hash offset puts both kinds on each calendar day. */
internal fun personalCopyDay(sessionId: String, day: LocalDate): Boolean =
    (day.toEpochDay() + Math.floorMod(sessionId.hashCode(), 2)).mod(2L) == 0L

internal suspend fun practiceReminderCopy(
    sessionId: String,
    day: LocalDate,
    generic: String,
    genericOpeningQuestion: String,
    personalize: suspend (String) -> PersonalizedReminderResponse?,
): PracticeReminderCopy {
    if (personalCopyDay(sessionId, day)) {
        val pair = try { personalize(sessionId) }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Throwable) { null }
        val text = pair?.text?.trim()?.takeIf { it.isNotEmpty() }
        val question = pair?.openingQuestion?.trim()?.takeIf { it.isNotEmpty() }
        if (text != null && question != null) return PracticeReminderCopy(text, "personalized", question)
    }
    return PracticeReminderCopy(generic, "general", genericOpeningQuestion)
}
