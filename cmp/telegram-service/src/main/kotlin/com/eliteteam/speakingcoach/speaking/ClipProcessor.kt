package com.eliteteam.speakingcoach.speaking

fun interface ClipSource {
    suspend fun load(): AudioClip
}

fun interface ClipProcessor {
    suspend fun process(sessionId: SessionId, clip: AudioClip): ClipReply
}

data class TurnStreak(
    val current: Int,
    val best: Int,
    val firstToday: Boolean,
    val firstEver: Boolean,
    val newRecord: Boolean,
)

data class ClipReply(
    val corrections: List<Correction>,
    val audio: AudioClip,
    val transcript: String = "",
    val streak: TurnStreak? = null,
)

enum class CorrectionKind(val wire: String) {
    GRAMMAR("grammar"),
    WORD("word"),
    NATURAL("natural"),
    ;

    companion object {
        fun fromWire(value: String?): CorrectionKind? {
            val normalized = value?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wire == normalized }
        }
    }
}

data class Correction(
    val wrong: String,
    val better: String,
    val kind: CorrectionKind? = null,
) {
    val priority: Int
        get() = kind?.ordinal ?: CorrectionKind.entries.size
}

internal const val CORRECTION_SEP = "|||"

internal fun parseCorrections(notes: List<String>): List<Correction> =
    notes.mapNotNull { note ->
        val parts = note.split(CORRECTION_SEP, limit = 2)
        if (parts.size != 2) {
            return@mapNotNull null
        }
        val wrong = parts[0].trim()
        val better = parts[1].trim()
        if (wrong.isEmpty() || better.isEmpty()) null else Correction(wrong, better)
    }

data class SessionGreeting(
    val sessionId: SessionId,
    val text: String,
    val audio: AudioClip,
)

sealed interface ClipSubmitResult {
    data class Completed(val reply: ClipReply) : ClipSubmitResult
    data object QueueFull : ClipSubmitResult
}
