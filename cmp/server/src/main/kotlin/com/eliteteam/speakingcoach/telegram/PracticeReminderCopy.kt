package com.eliteteam.speakingcoach.telegram

import java.time.LocalDate

internal data class PracticeReminderCopy(val text: String, val kind: String)

/** A user alternates daily; hash offset puts both kinds on each calendar day. */
internal fun personalCopyDay(sessionId: String, day: LocalDate): Boolean =
    (day.toEpochDay() + Math.floorMod(sessionId.hashCode(), 2)).mod(2L) == 0L

internal suspend fun practiceReminderCopy(
    sessionId: String,
    day: LocalDate,
    generic: String,
    personalize: suspend (String) -> String?,
): PracticeReminderCopy {
    if (personalCopyDay(sessionId, day)) {
        val text = try { personalize(sessionId)?.trim()?.takeIf { it.isNotEmpty() } }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Throwable) { null }
        if (text != null) return PracticeReminderCopy(text, "personalized")
    }
    return PracticeReminderCopy(generic, "general")
}
