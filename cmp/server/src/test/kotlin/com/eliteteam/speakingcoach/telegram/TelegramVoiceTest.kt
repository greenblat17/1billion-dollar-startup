package com.eliteteam.speakingcoach.telegram

import com.eliteteam.speakingcoach.speaking.AudioClip
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelegramVoiceTest {

    @Test
    fun leavesOggUntouched() = runTest {
        val ogg = "OggS-voice".encodeToByteArray()
        val voice = AudioClip(ogg, "audio/ogg", "reply.ogg").toTelegramVoice()
        assertEquals(ogg.toList(), voice.bytes.toList())
        assertEquals("audio/ogg", voice.contentType)
        assertEquals("reply.ogg", voice.fileName)
    }

    @Test
    fun wrapsWavAsOpusVoice() = runTest {
        val wav = wavSilence()
        val voice = AudioClip(wav, "audio/wav", "reply.wav").toTelegramVoice()
        assertEquals("audio/ogg", voice.contentType)
        assertEquals("reply.ogg", voice.fileName)
        assertTrue(voice.bytes.copyOfRange(0, 4).contentEquals("OggS".encodeToByteArray()))
    }

    private fun wavSilence(): ByteArray {
        val samples = ByteArray(3200)
        val dataSize = samples.size
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        writeLe(header, 4, 36 + dataSize)
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        writeLe(header, 16, 16)
        header[20] = 1
        header[22] = 1
        writeLe(header, 24, 16_000)
        writeLe(header, 28, 32_000)
        header[32] = 2
        header[34] = 16
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        writeLe(header, 40, dataSize)
        return header + samples
    }

    private fun writeLe(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xff).toByte()
        target[offset + 1] = (value shr 8 and 0xff).toByte()
        target[offset + 2] = (value shr 16 and 0xff).toByte()
        target[offset + 3] = (value shr 24 and 0xff).toByte()
    }
}