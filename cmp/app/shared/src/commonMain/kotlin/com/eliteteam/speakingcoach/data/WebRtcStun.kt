package com.eliteteam.speakingcoach.data

internal object WebRtcStun {
    val urls = listOf(
        "stun:stun.l.google.com:19302",
        "stun:stun1.l.google.com:19302",
    )
}

internal class IceFailedException(state: String) : IllegalStateException("ice $state")

internal expect fun startCallAudio()

internal expect fun stopCallAudio()
