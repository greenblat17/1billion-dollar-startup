package com.eliteteam.speakingcoach.data

import io.ktor.client.HttpClient

expect fun createHttpClient(): HttpClient

expect fun apiBaseUrl(): String

internal fun resolveApiBaseUrl(override: String? = null): String {
    val url = override?.trim()?.takeIf { it.isNotEmpty() }
        ?: ApiConfig.BAKED_API_BASE_URL.trim()
    require(url.isNotEmpty()) {
        "Missing speakingCoach.apiBaseUrl. Set it in cmp/client.local.properties, " +
            "or SPEAKING_COACH_API_BASE_URL / -PspeakingCoach.apiBaseUrl=."
    }
    return url.trimEnd('/')
}
