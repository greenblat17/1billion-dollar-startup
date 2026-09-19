package com.eliteteam.speakingcoach.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

actual fun createHttpClient(): HttpClient = HttpClient(CIO) {
    engine {
        https {
            trustManager = TrustAllManager
        }
    }
    installAppHttp()
}

actual fun apiBaseUrl(): String = resolveApiBaseUrl(
    System.getenv("SPEAKING_COACH_API_BASE_URL")
        ?: System.getProperty("speakingCoach.apiBaseUrl"),
)

private object TrustAllManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
