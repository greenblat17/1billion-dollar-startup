package com.eliteteam.speakingcoach.app

import com.eliteteam.speakingcoach.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

internal fun createAppApi(config: AppConfig, http: HttpClient? = null): AppApi? {
    val secret = config.jwtSecret?.takeIf { it.isNotBlank() } ?: return null
    val client = http ?: HttpClient(CIO) {
        expectSuccess = false
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }
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
