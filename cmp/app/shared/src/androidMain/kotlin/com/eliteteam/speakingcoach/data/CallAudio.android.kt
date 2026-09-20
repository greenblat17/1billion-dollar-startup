package com.eliteteam.speakingcoach.data

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

object CallAudio {
    private var appContext: Context? = null
    private var previousMode: Int? = null
    private var previousSpeaker: Boolean? = null

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    internal fun start() {
        val audio = audioManager() ?: return
        if (previousMode == null) {
            previousMode = audio.mode
            @Suppress("DEPRECATION")
            previousSpeaker = audio.isSpeakerphoneOn
        }
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val speaker = audio.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
            if (speaker != null) {
                audio.setCommunicationDevice(speaker)
            }
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = true
        }
    }

    internal fun stop() {
        val audio = audioManager() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.clearCommunicationDevice()
        } else {
            previousSpeaker?.let { speaker ->
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = speaker
            }
        }
        previousMode?.let { audio.mode = it }
        previousMode = null
        previousSpeaker = null
    }

    private fun audioManager(): AudioManager? {
        val context = appContext ?: return null
        return context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
}

internal actual fun startCallAudio() = CallAudio.start()

internal actual fun stopCallAudio() = CallAudio.stop()
