package com.eliteteam.speakingcoach.tls

import java.io.File
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

internal const val TLS_KEY_ALIAS = "ktor"

internal fun loadPemKeyStore(certFile: File, keyFile: File, password: CharArray): KeyStore {
    require(certFile.isFile) { "TLS certificate not found: ${certFile.path}" }
    require(keyFile.isFile) { "TLS private key not found: ${keyFile.path}" }
    val certificates = CertificateFactory.getInstance("X.509")
        .generateCertificates(certFile.inputStream())
        .map { it as X509Certificate }
        .toTypedArray()
    require(certificates.isNotEmpty()) { "No certificates in ${certFile.path}" }
    val store = KeyStore.getInstance("PKCS12")
    store.load(null, password)
    store.setKeyEntry(TLS_KEY_ALIAS, parsePemPrivateKey(keyFile.readText()), password, certificates)
    return store
}

internal fun parsePemPrivateKey(pem: String): PrivateKey {
    val pkcs8 = when {
        pem.contains("BEGIN PRIVATE KEY") -> decodePemBody(pem, "PRIVATE KEY")
        pem.contains("BEGIN RSA PRIVATE KEY") -> wrapPkcs1(decodePemBody(pem, "RSA PRIVATE KEY"))
        else -> error("Unsupported TLS private key PEM")
    }
    return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
}

private fun decodePemBody(pem: String, type: String): ByteArray {
    val body = pem
        .substringAfter("-----BEGIN $type-----")
        .substringBefore("-----END $type-----")
        .replace("\\s".toRegex(), "")
    require(body.isNotEmpty()) { "Empty $type PEM body" }
    return Base64.getDecoder().decode(body)
}

private fun wrapPkcs1(pkcs1: ByteArray): ByteArray {
    val rsaOid = byteArrayOf(
        0x30, 0x0d,
        0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01,
        0x05, 0x00,
    )
    val version = byteArrayOf(0x02, 0x01, 0x00)
    val octet = byteArrayOf(0x04) + derLength(pkcs1.size) + pkcs1
    val inner = version + rsaOid + octet
    return byteArrayOf(0x30) + derLength(inner.size) + inner
}

private fun derLength(length: Int): ByteArray = when {
    length < 128 -> byteArrayOf(length.toByte())
    length < 256 -> byteArrayOf(0x81.toByte(), length.toByte())
    length < 65536 -> byteArrayOf(0x82.toByte(), (length shr 8).toByte(), length.toByte())
    else -> error("TLS private key is too large")
}
