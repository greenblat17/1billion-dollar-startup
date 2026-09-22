package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.speaking.AudioClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

private val oggMagic = "OggS".encodeToByteArray()

internal suspend fun AudioClip.toTelegramVoice(): AudioClip {
    if (isOgg()) {
        val name = if (fileName.endsWith(".ogg")) fileName else "reply.ogg"
        return AudioClip(bytes, "audio/ogg", name)
    }
    val ogg = transcodeToOggOpus(bytes, suffixFor(fileName))
    return AudioClip(ogg, "audio/ogg", "reply.ogg")
}

private fun AudioClip.isOgg(): Boolean =
    contentType.substringBefore(';').equals("audio/ogg", ignoreCase = true) ||
        (bytes.size >= oggMagic.size && bytes.copyOfRange(0, oggMagic.size).contentEquals(oggMagic))

private fun suffixFor(fileName: String): String {
    val dot = fileName.lastIndexOf('.')
    if (dot < 0 || dot == fileName.lastIndex) {
        return ".wav"
    }
    return fileName.substring(dot)
}

private suspend fun transcodeToOggOpus(source: ByteArray, suffix: String): ByteArray =
    withContext(Dispatchers.IO) {
        val dir = Files.createTempDirectory("tg-voice")
        try {
            val src = dir.resolve("in$suffix")
            val dst = dir.resolve("out.ogg")
            src.writeBytes(source)
            val process = ProcessBuilder(
                "ffmpeg",
                "-y",
                "-i",
                src.toString(),
                "-c:a",
                "libopus",
                "-b:a",
                "32k",
                "-application",
                "voip",
                "-vn",
                dst.toString(),
            ).redirectErrorStream(true).start()
            val log = process.inputStream.readBytes().decodeToString()
            val code = process.waitFor()
            if (code != 0 || !Files.exists(dst)) {
                error("ffmpeg failed ($code): ${log.takeLast(500)}")
            }
            dst.readBytes()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
