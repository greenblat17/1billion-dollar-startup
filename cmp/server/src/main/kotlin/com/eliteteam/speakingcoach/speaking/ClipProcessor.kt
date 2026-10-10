package com.eliteteam.speakingcoach.speaking

import com.eliteteam.speakingcoach.analytics.OnboardingVoiceFacts
import com.eliteteam.speakingcoach.ai.OnboardingReview

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

data class CallProgress(
    val callId: String,
    val todaySeconds: Double,
    val goalSeconds: Double,
    val goalJustCrossed: Boolean,
    val recognizedSeconds: Double? = null,
)

data class ClipReply(
    val corrections: List<Correction>,
    val audio: AudioClip?,
    val text: String = "",
    val onboarding: OnboardingStatus? = null,
    val transcript: String = "",
    val streak: TurnStreak? = null,
    val call: CallProgress? = null,
    val jobId: String? = null,
    val timingsMs: Map<String, Long> = emptyMap(),
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
    val explanation: String? = null,
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

data class OnboardingStatus(
    val runId: String,
    val status: String,
    val seconds: Double = 0.0,
    val cefr: String? = null,
    val review: OnboardingReview? = null,
    val overallScore: Int? = null,
    val nextBand: String? = null,
    val pointsToNext: Int? = null,
    val analytics: OnboardingVoiceFacts? = null,
    val preliminary: Boolean = false,
    val shortResultAvailable: Boolean = false,
)
