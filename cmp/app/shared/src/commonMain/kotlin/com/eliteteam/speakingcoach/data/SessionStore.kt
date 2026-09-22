package com.eliteteam.speakingcoach.data

import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val KeyToken = "auth.token"
private const val KeyUserId = "auth.userId"
private const val KeyEmail = "auth.email"
private const val KeyDisplayName = "auth.displayName"
private const val KeyTutorVoice = "prefs.tutorVoice"
private const val KeyCaptions = "prefs.captionsByDefault"

class SessionStore(
    private val settings: Settings,
    private val logger: Logger,
) {
    private val _session = MutableStateFlow(readSession())
    val session: StateFlow<AuthSession?> = _session.asStateFlow()

    init {
        val restored = _session.value
        if (restored == null) {
            logger.i { "no stored session" }
        } else {
            logger.i { "session restored userId=${restored.user.id}" }
        }
    }

    val tutorVoice: String
        get() = settings.getString(KeyTutorVoice, "marin")

    val captionsByDefault: Boolean
        get() = settings.getBoolean(KeyCaptions, true)

    fun save(session: AuthSession) {
        settings.putString(KeyToken, session.token)
        settings.putString(KeyUserId, session.user.id)
        settings.putString(KeyEmail, session.user.email)
        settings.putString(KeyDisplayName, session.user.displayName)
        _session.value = session
        logger.i { "session saved userId=${session.user.id}" }
    }

    fun clear() {
        settings.remove(KeyToken)
        settings.remove(KeyUserId)
        settings.remove(KeyEmail)
        settings.remove(KeyDisplayName)
        _session.value = null
        logger.i { "session cleared" }
    }

    fun setTutorVoice(voice: String) {
        settings.putString(KeyTutorVoice, voice)
    }

    fun setCaptionsByDefault(enabled: Boolean) {
        settings.putBoolean(KeyCaptions, enabled)
    }

    private fun readSession(): AuthSession? {
        val token = settings.getStringOrNull(KeyToken)?.takeIf { it.isNotBlank() } ?: return null
        val id = settings.getStringOrNull(KeyUserId)?.takeIf { it.isNotBlank() } ?: return null
        val email = settings.getStringOrNull(KeyEmail)?.takeIf { it.isNotBlank() } ?: return null
        val displayName = settings.getStringOrNull(KeyDisplayName)?.takeIf { it.isNotBlank() } ?: return null
        return AuthSession(
            token = token,
            user = AuthUser(id = id, email = email, displayName = displayName),
        )
    }
}
