package com.eliteteam.speakingcoach

import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.MDC

internal const val LOG_SESSION = "session"
internal const val LOG_REQUEST = "request"

internal suspend fun <T> withRequestLog(
    session: String? = null,
    request: String? = null,
    block: suspend () -> T,
): T {
    val previousSession = MDC.get(LOG_SESSION)
    val previousRequest = MDC.get(LOG_REQUEST)
    if (session != null) MDC.put(LOG_SESSION, session)
    if (request != null) MDC.put(LOG_REQUEST, request)
    return try {
        withContext(MDCContext()) { block() }
    } finally {
        restoreMdc(LOG_SESSION, previousSession)
        restoreMdc(LOG_REQUEST, previousRequest)
    }
}

private fun restoreMdc(key: String, value: String?) {
    if (value == null) MDC.remove(key) else MDC.put(key, value)
}
