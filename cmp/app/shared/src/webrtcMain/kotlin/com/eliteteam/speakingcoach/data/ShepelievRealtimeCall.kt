package com.eliteteam.speakingcoach.data

import co.touchlab.kermit.Logger
import com.shepeliev.webrtckmp.AudioTrack
import com.shepeliev.webrtckmp.DataChannel
import com.shepeliev.webrtckmp.IceConnectionState
import com.shepeliev.webrtckmp.IceGatheringState
import com.shepeliev.webrtckmp.IceServer
import com.shepeliev.webrtckmp.MediaDevices
import com.shepeliev.webrtckmp.MediaStreamTrack
import com.shepeliev.webrtckmp.MediaStreamTrackKind
import com.shepeliev.webrtckmp.OfferAnswerOptions
import com.shepeliev.webrtckmp.PeerConnection
import com.shepeliev.webrtckmp.RtcConfiguration
import com.shepeliev.webrtckmp.SessionDescription
import com.shepeliev.webrtckmp.SessionDescriptionType
import com.shepeliev.webrtckmp.onConnectionStateChange
import com.shepeliev.webrtckmp.onIceConnectionStateChange
import com.shepeliev.webrtckmp.onIceGatheringState
import com.shepeliev.webrtckmp.onTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

internal class ShepelievRealtimeCall(
    private val logger: Logger,
    private val dataChannelMessages: (DataChannel) -> Flow<ByteArray> = { it.onMessage },
) : RealtimeCall {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val peer = PeerConnection(
        RtcConfiguration(iceServers = listOf(IceServer(urls = WebRtcStun.urls))),
    )
    private val collector = OpenAiTranscriptCollector()
    private val _captions = MutableSharedFlow<String>(extraBufferCapacity = 32)
    private val remoteAudio = mutableListOf<MediaStreamTrack>()
    private var localAudio: MediaStreamTrack? = null
    private var events: DataChannel? = null

    override val captions = _captions.asSharedFlow()

    init {
        scope.launch {
            peer.onIceGatheringState.collect { logger.i { "iceGathering=$it" } }
        }
        scope.launch {
            peer.onIceConnectionStateChange.collect { logger.i { "iceConnection=$it" } }
        }
        scope.launch {
            peer.onConnectionStateChange.collect { logger.i { "peerConnection=$it" } }
        }
        scope.launch {
            peer.onTrack.collect { event ->
                val track = event.track ?: return@collect
                logger.i { "onTrack kind=${track.kind} id=${track.id}" }
                if (track.kind == MediaStreamTrackKind.Audio) {
                    track.enabled = true
                    (track as? AudioTrack)?.setVolume(1.0)
                    remoteAudio += track
                }
            }
        }
    }

    override suspend fun createOffer(): String {
        startCallAudio()
        val stream = MediaDevices.getUserMedia(audio = true, video = false)
        stream.tracks.forEach { track ->
            localAudio = track
            peer.addTrack(track, stream)
        }
        val channel = peer.createDataChannel("oai-events")
            ?: error("data channel")
        events = channel
        scope.launch {
            dataChannelMessages(channel).collect { message ->
                val caption = collector.onMessage(message.decodeToString())
                if (caption != null) {
                    _captions.emit(caption)
                }
            }
        }
        val offer = peer.createOffer(OfferAnswerOptions(offerToReceiveAudio = true))
        peer.setLocalDescription(offer)
        awaitIceGathering()
        return peer.localDescription?.sdp ?: offer.sdp
    }

    override suspend fun setRemoteAnswer(sdp: String) {
        peer.setRemoteDescription(SessionDescription(SessionDescriptionType.Answer, sdp))
        awaitIceConnected()
    }

    override fun setMuted(muted: Boolean) {
        localAudio?.enabled = !muted
    }

    override fun snapshotTurns(): List<TranscriptTurn> = collector.snapshot()

    override fun close() {
        scope.cancel()
        runCatching { events?.close() }
        runCatching { localAudio?.stop() }
        remoteAudio.forEach { runCatching { it.stop() } }
        peer.close()
        stopCallAudio()
    }

    private suspend fun awaitIceGathering() {
        if (peer.iceGatheringState == IceGatheringState.Complete) {
            return
        }
        val finished = withTimeoutOrNull(15.seconds) {
            peer.onIceGatheringState
                .onStart { emit(peer.iceGatheringState) }
                .first { it == IceGatheringState.Complete }
            true
        }
        if (finished == null) {
            logger.w { "ice gathering timeout state=${peer.iceGatheringState}" }
        }
    }

    private suspend fun awaitIceConnected() {
        val state = withTimeout(20.seconds) {
            peer.onIceConnectionStateChange
                .onStart { emit(peer.iceConnectionState) }
                .first { it.isTerminalIce() }
        }
        if (state != IceConnectionState.Connected && state != IceConnectionState.Completed) {
            throw IceFailedException(state.toString())
        }
    }
}

private fun IceConnectionState.isTerminalIce(): Boolean =
    this == IceConnectionState.Connected ||
        this == IceConnectionState.Completed ||
        this == IceConnectionState.Failed ||
        this == IceConnectionState.Closed
