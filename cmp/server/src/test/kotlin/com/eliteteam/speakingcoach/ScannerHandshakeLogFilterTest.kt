package com.eliteteam.speakingcoach

import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScannerHandshakeLogFilterTest {
    @Test
    fun dropsBareHttpAndObsoleteTlsOnTheNegotiationHandler() {
        val logger = "io.netty.handler.ssl.ApplicationProtocolNegotiationHandler"
        assertTrue(isScannerHandshakeNoise(logger, exception("not an SSL/TLS record: 47455420")))
        assertTrue(
            isScannerHandshakeNoise(
                logger,
                SSLHandshakeException("Client requested protocol TLSv1.1 is not enabled or supported in server context"),
            ),
        )
        assertTrue(
            isScannerHandshakeNoise(
                logger,
                SSLHandshakeException("Client requested protocol TLSv1 is not enabled or supported in server context"),
            ),
        )
        assertTrue(
            isScannerHandshakeNoise(
                logger,
                SSLHandshakeException("Client requested protocol SSLv3 is not enabled or supported in server context"),
            ),
        )
        assertTrue(
            isScannerHandshakeNoise(
                logger,
                SSLHandshakeException("(handshake_failure) no cipher suites in common"),
            ),
        )
        assertTrue(
            isScannerHandshakeNoise(
                logger,
                SSLHandshakeException("(handshake_failure) No available authentication scheme"),
            ),
        )
        assertTrue(
            isScannerHandshakeNoise(
                logger,
                SSLHandshakeException("handshake failed").apply {
                    initCause(java.io.IOException("Connection reset by peer"))
                },
            ),
        )
    }

    @Test
    fun keepsModernTlsAndCertificateAlertsAndOtherLoggers() {
        val logger = "io.netty.handler.ssl.ApplicationProtocolNegotiationHandler"
        assertFalse(
            isScannerHandshakeNoise(
                logger,
                SSLHandshakeException("Client requested protocol TLSv1.2 is not enabled or supported in server context"),
            ),
        )
        assertFalse(
            isScannerHandshakeNoise(
                logger,
                SSLHandshakeException(
                    "The client supported protocol versions [TLSv1.3, TLSv1.2] are not accepted by server preferences",
                ),
            ),
        )
        assertFalse(
            isScannerHandshakeNoise(
                logger,
                SSLHandshakeException("(certificate_unknown) Received fatal alert: certificate_unknown"),
            ),
        )
        assertFalse(
            isScannerHandshakeNoise(
                logger,
                SSLHandshakeException("(bad_certificate) Received fatal alert: bad_certificate"),
            ),
        )
        assertFalse(
            isScannerHandshakeNoise(
                "io.netty.handler.ssl.SslHandler",
                SSLHandshakeException("Client requested protocol TLSv1 is not enabled"),
            ),
        )
        assertFalse(isScannerHandshakeNoise(logger, null))
    }
}

private fun exception(message: String): Exception = Exception(message)
