package com.eliteteam.speakingcoach.app

import com.eliteteam.speakingcoach.AppConfig
import com.eliteteam.speakingcoach.ai.speakingCoachAiHttpClient
import io.ktor.client.HttpClient

internal fun createAppApi(config: AppConfig, http: HttpClient? = null): AppApi? {
    val secret = config.jwtSecret?.takeIf { it.isNotBlank() } ?: return null
    val client = http ?: speakingCoachAiHttpClient()
    val store = if (config.databaseUrl.isNullOrBlank()) {
        MemoryAppStore()
    } else {
        PostgresAppStore(config.databaseUrl)
    }
    return AppApi(
        store = store,
        tokens = JwtTokens(secret),
        passwords = PasswordHasher(),
        ai = HttpInternalAi(config.aiServiceBaseUrl, client, config.aiInternalToken),
    )
}
