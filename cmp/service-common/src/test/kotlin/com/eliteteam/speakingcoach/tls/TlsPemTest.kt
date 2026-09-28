package com.eliteteam.speakingcoach.tls

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TlsPemTest {

    @Test
    fun loadsOpensslPkcs8KeyIntoKeyStore() {
        val dir = Files.createTempDirectory("tls-pem-test")
        val cert = dir.resolve("tls.crt").toFile()
        val key = dir.resolve("tls.key").toFile()
        val openssl = ProcessBuilder(
            "openssl", "req", "-x509", "-newkey", "rsa:2048", "-sha256", "-nodes",
            "-keyout", key.absolutePath,
            "-out", cert.absolutePath,
            "-days", "1",
            "-subj", "/CN=localhost",
            "-addext", "subjectAltName=DNS:localhost",
        ).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.to(dir.resolve("openssl.log").toFile()))
            .start()
        assertEquals(0, openssl.waitFor())

        val password = "ktor".toCharArray()
        val store = loadPemKeyStore(cert, key, password)
        assertTrue(store.containsAlias(TLS_KEY_ALIAS))
        store.getKey(TLS_KEY_ALIAS, password)
    }
}
