package com.eliteteam.speakingcoach

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.core.spi.FilterReply
import org.slf4j.Marker

private const val NEGOTIATION_LOGGER = "io.netty.handler.ssl.ApplicationProtocolNegotiationHandler"

/**
 * Drops known internet-scanner handshake failures from Netty's ALPN handler.
 * Other loggers, including the rest of io.netty, are unchanged. Certificate
 * alerts stay visible: a real client that rejects our certificate is not a scan.
 */
internal class ScannerHandshakeLogFilter : TurboFilter() {
    override fun decide(
        marker: Marker?,
        logger: Logger?,
        level: Level?,
        format: String?,
        params: Array<out Any>?,
        throwable: Throwable?,
    ): FilterReply {
        if (isScannerHandshakeNoise(logger?.name, throwable)) {
            return FilterReply.DENY
        }
        return FilterReply.NEUTRAL
    }
}

internal fun isScannerHandshakeNoise(loggerName: String?, throwable: Throwable?): Boolean {
    if (loggerName != NEGOTIATION_LOGGER) {
        return false
    }
    return throwableChain(throwable).any(::isKnownScannerHandshake)
}

private fun throwableChain(root: Throwable?): Sequence<Throwable> = sequence {
    val seen = HashSet<Throwable>()
    var current = root
    while (current != null && seen.add(current)) {
        yield(current)
        current = current.cause
    }
}

private fun isKnownScannerHandshake(error: Throwable): Boolean {
    val typeName = error.javaClass.name
    val message = error.message.orEmpty()
    if (typeName.endsWith("NotSslRecordException") || "not an SSL/TLS record" in message) {
        return true
    }
    if (isObsoleteTls(message)) {
        return true
    }
    if ("no cipher suites in common" in message || "No available authentication scheme" in message) {
        return true
    }
    return isHandshakeAbort(typeName, message)
}

private fun isObsoleteTls(message: String): Boolean {
    val aboutProtocol = "protocol_version" in message ||
        "requested protocol" in message ||
        "protocol versions" in message
    if (!aboutProtocol) {
        return false
    }
    val withoutModern = MODERN_TLS.replace(message, "")
    return OBSOLETE_TLS.containsMatchIn(withoutModern)
}

private fun isHandshakeAbort(typeName: String, message: String): Boolean {
    if (typeName.endsWith("ClosedChannelException")) {
        return true
    }
    return "Connection reset" in message ||
        "Broken pipe" in message ||
        "prematurely closed" in message ||
        "connection was aborted" in message
}

private val MODERN_TLS = Regex("TLSv1\\.[23]")
private val OBSOLETE_TLS = Regex("SSLv[23]|TLSv1\\.0|TLSv1\\.1|(?<![.\\d])TLSv1(?!\\.)")
