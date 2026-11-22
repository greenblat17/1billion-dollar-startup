package com.eliteteam.speakingcoach.foundation

import java.net.URLDecoder
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.jsonObject

/** Telegram signature authenticates the subject; URL/lesson IDs never authenticate ownership. */
internal fun telegramMiniSubject(raw: String, botToken: String, now: Instant): String {
    if (raw.isBlank() || raw.length > 16000 || botToken.isBlank()) reject("invalid_telegram_auth")
    val pairs = raw.split('&').map {
        val parts = it.split('=', limit = 2)
        if (parts.size != 2) reject("invalid_telegram_auth")
        URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
    }
    if (pairs.map { it.first }.distinct().size != pairs.size) reject("invalid_telegram_auth")
    val fields = pairs.toMap()
    val date = fields["auth_date"]?.toLongOrNull() ?: reject("invalid_telegram_auth")
    if (date > now.epochSecond + 30 || now.epochSecond - date > 86400) reject("telegram_auth_expired")
    fun hmac(key: ByteArray, value: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(value.toByteArray(Charsets.UTF_8))
    }
    val secret = hmac("WebAppData".toByteArray(), botToken)
    val expected = hmac(secret, fields.filterKeys { it != "hash" }.toSortedMap().entries.joinToString("\n") { "${it.key}=${it.value}" })
        .joinToString("") { "%02x".format(it) }
    if (!MessageDigest.isEqual(expected.toByteArray(), (fields["hash"] ?: "").toByteArray())) reject("invalid_telegram_auth")
    val user = foundationJson.parseToJsonElement(fields["user"] ?: reject("invalid_telegram_auth")).jsonObject
    return user.text("id").also { if (it.toLongOrNull()?.let { id -> id > 0 } != true) reject("invalid_telegram_auth") }
}
