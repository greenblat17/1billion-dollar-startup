package com.eliteteam.speakingcoach

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import java.util.concurrent.TimeUnit

suspend fun main() {
    startSpeakyServer(AppConfig.fromEnv())
}

internal fun speakingCoachAiHttpClient(): HttpClient = HttpClient(OkHttp) {
    expectSuccess = false
    install(HttpTimeout)
    engine {
        config {
            // AI closes idle connections after 5 seconds; retire them on CMP first.
            connectionPool(ConnectionPool(5, 3, TimeUnit.SECONDS))
        }
    }
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
            },
        )
    }
}
