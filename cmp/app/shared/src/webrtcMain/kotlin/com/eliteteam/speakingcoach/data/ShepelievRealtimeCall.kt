package com.eliteteam.speakingcoach.data

import com.shepeliev.webrtckmp.IceGatheringState
import com.shepeliev.webrtckmp.MediaDevices
import com.shepeliev.webrtckmp.MediaStreamTrack
import com.shepeliev.webrtckmp.OfferAnswerOptions
import com.shepeliev.webrtckmp.PeerConnection
import com.shepeliev.webrtckmp.SessionDescription
import com.shepeliev.webrtckmp.SessionDescriptionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

internal class ShepelievRealtimeCall : RealtimeCall {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val peer = PeerConnection()
    private val collector = OpenAiTranscriptCollector()
    private val _captions = MutableSharedFlow<String>(extraBufferCapacity = 32)
    private var localAudio: MediaStreamTrack? = null

    override val captions = _captions.asSharedFlow()

    override suspend fun createOffer(): String {
        val stream = MediaDevices.getUserMedia(audio = true, video = false)
        stream.tracks.forEach { track ->
            localAudio = track
            peer.addTrack(track, stream)
        }
        val channel = peer.createDataChannel("oai-events")
            ?: error("data channel")
        scope.launch {
            channel.onMessage.collect { message ->
                val caption = collector.onMessage(message.decodeToString())
                if (caption != null) {
                    _captions.emit(caption)
                }
            }
        }
        val offer = peer.createOffer(OfferAnswerOptions())
        peer.setLocalDescription(offer)
        waitForIce()
        return peer.localDescription?.sdp ?: offer.sdp
    }

    override suspend fun setRemoteAnswer(sdp: String) {
        peer.setRemoteDescription(SessionDescription(SessionDescriptionType.Answer, sdp))
    }

    override fun setMuted(muted: Boolean) {
        localAudio?.enabled = !muted
    }

    override fun snapshotTurns(): List<TranscriptTurn> = collector.snapshot()

    override fun close() {
        scope.cancel()
        runCatching { localAudio?.stop() }
        peer.close()
    }

    private suspend fun waitForIce() {
        if (peer.iceGatheringState == IceGatheringState.Complete) return
        withTimeout(8.seconds) {
            while (peer.iceGatheringState != IceGatheringState.Complete) {
                delay(50)
            }
        }
    }
}
