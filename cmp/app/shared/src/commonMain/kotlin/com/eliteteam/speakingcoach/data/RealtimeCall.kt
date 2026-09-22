package com.eliteteam.speakingcoach.data

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.Flow

interface RealtimeCall : AutoCloseable {
    suspend fun createOffer(): String
    suspend fun setRemoteAnswer(sdp: String)
    fun setMuted(muted: Boolean)
    val captions: Flow<String>
    fun snapshotTurns(): List<TranscriptTurn>
}

expect fun createRealtimeCall(logger: Logger): RealtimeCall
