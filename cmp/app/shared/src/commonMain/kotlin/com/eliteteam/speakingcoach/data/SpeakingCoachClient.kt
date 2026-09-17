package com.eliteteam.speakingcoach.data

/**
 * Mobile HTTP surface to `:server` / ai-service. Not called from UI yet — screens
 * render [com.eliteteam.speakingcoach.ui.mock.MockSpeakingData] until the contract
 * is agreed with backend.
 */
interface SpeakingCoachClient {
    suspend fun signIn()
    suspend fun startSession(topicId: String)
    suspend fun loadHome()
    suspend fun loadHistory()
    suspend fun loadReview(sessionId: String)
    suspend fun loadProfile()
    suspend fun signOut()
}

class UnimplementedSpeakingCoachClient : SpeakingCoachClient {
    override suspend fun signIn() {
        TODO("signIn: agree auth API with backend")
    }

    override suspend fun startSession(topicId: String) {
        TODO("startSession: agree clip/session API with backend")
    }

    override suspend fun loadHome() {
        TODO("loadHome: agree home payload with backend")
    }

    override suspend fun loadHistory() {
        TODO("loadHistory: agree history payload with backend")
    }

    override suspend fun loadReview(sessionId: String) {
        TODO("loadReview: agree review payload with backend")
    }

    override suspend fun loadProfile() {
        TODO("loadProfile: agree profile payload with backend")
    }

    override suspend fun signOut() {
        TODO("signOut: agree auth API with backend")
    }
}
