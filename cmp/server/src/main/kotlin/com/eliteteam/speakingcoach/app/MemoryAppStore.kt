package com.eliteteam.speakingcoach.app

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

internal class MemoryAppStore : AppStore {
    private val mutex = Mutex()
    private val usersById = mutableMapOf<String, AppUser>()
    private val userIdByEmail = mutableMapOf<String, String>()
    private val sessions = mutableMapOf<String, SpeakingSession>()
    private val reviews = mutableMapOf<String, String>()

    override suspend fun createUser(email: String, passwordHash: String, displayName: String): AppUser =
        mutex.withLock {
            if (userIdByEmail.containsKey(email)) {
                throw DuplicateEmailException()
            }
            val user = AppUser(
                id = UUID.randomUUID().toString(),
                email = email,
                displayName = displayName,
                passwordHash = passwordHash,
            )
            usersById[user.id] = user
            userIdByEmail[email] = user.id
            user
        }

    override suspend fun findUserByEmail(email: String): AppUser? =
        mutex.withLock { userIdByEmail[email]?.let(usersById::get) }

    override suspend fun findUserById(id: String): AppUser? =
        mutex.withLock { usersById[id] }

    override suspend fun createSession(userId: String, topic: String, tutorVoice: String): SpeakingSession =
        mutex.withLock {
            val session = SpeakingSession(
                id = "app-$userId-${UUID.randomUUID()}",
                userId = userId,
                topic = topic,
                tutorVoice = tutorVoice,
            )
            sessions[session.id] = session
            session
        }

    override suspend fun findSession(id: String): SpeakingSession? =
        mutex.withLock { sessions[id] }

    override suspend fun userHasActiveRtc(userId: String): Boolean =
        mutex.withLock { sessions.values.any { it.userId == userId && it.rtcActive } }

    override suspend fun tryStartRtc(sessionId: String): RtcStart =
        mutex.withLock {
            val session = sessions[sessionId] ?: return@withLock RtcStart.NotFound
            val busy = sessions.values.any { it.userId == session.userId && it.rtcActive }
            if (busy) {
                return@withLock RtcStart.Conflict
            }
            val updated = session.copy(rtcActive = true, status = SessionStatus.Rtc)
            sessions[sessionId] = updated
            RtcStart.Ok(updated)
        }

    override suspend fun attachOpenaiCallId(sessionId: String, openaiCallId: String?) {
        mutex.withLock {
            val session = sessions[sessionId] ?: return@withLock
            sessions[sessionId] = session.copy(openaiCallId = openaiCallId)
        }
    }

    override suspend fun releaseRtc(sessionId: String) {
        mutex.withLock {
            val session = sessions[sessionId] ?: return@withLock
            sessions[sessionId] = session.copy(rtcActive = false, status = SessionStatus.Created)
        }
    }

    override suspend fun markCompleted(sessionId: String, durationSec: Int, tooShort: Boolean): SpeakingSession? =
        mutex.withLock {
            val session = sessions[sessionId] ?: return@withLock null
            val updated = session.copy(
                rtcActive = false,
                durationSec = durationSec,
                status = if (tooShort) SessionStatus.TooShort else SessionStatus.Reviewing,
            )
            sessions[sessionId] = updated
            updated
        }

    override suspend fun saveReview(sessionId: String, payloadJson: String) {
        mutex.withLock {
            reviews[sessionId] = payloadJson
            val session = sessions[sessionId] ?: return@withLock
            sessions[sessionId] = session.copy(status = SessionStatus.Ready, rtcActive = false)
        }
    }

    override suspend fun findReviewJson(sessionId: String): String? =
        mutex.withLock { reviews[sessionId] }

    override suspend fun markReviewFailed(sessionId: String) {
        mutex.withLock {
            val session = sessions[sessionId] ?: return@withLock
            sessions[sessionId] = session.copy(status = SessionStatus.ReviewFailed, rtcActive = false)
        }
    }
}
