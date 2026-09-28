package com.eliteteam.speakingcoach.speaking

data class AudioClip(
    val bytes: ByteArray,
    val contentType: String,
    val fileName: String,
    val onboardingRunId: String? = null,
    val requestId: String? = null,
    val durationSeconds: Double = 0.0,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AudioClip

        if (!bytes.contentEquals(other.bytes)) return false
        if (contentType != other.contentType) return false
        if (fileName != other.fileName) return false
        if (onboardingRunId != other.onboardingRunId || requestId != other.requestId) return false
        if (durationSeconds != other.durationSeconds) return false

        return true
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + fileName.hashCode()
        result = 31 * result + onboardingRunId.hashCode()
        result = 31 * result + requestId.hashCode()
        result = 31 * result + durationSeconds.hashCode()
        return result
    }
}
