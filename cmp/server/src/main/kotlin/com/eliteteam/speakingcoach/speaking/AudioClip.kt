package com.eliteteam.speakingcoach.speaking

data class AudioClip(
    val bytes: ByteArray,
    val contentType: String,
    val fileName: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AudioClip

        if (!bytes.contentEquals(other.bytes)) return false
        if (contentType != other.contentType) return false
        if (fileName != other.fileName) return false

        return true
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + fileName.hashCode()
        return result
    }
}
