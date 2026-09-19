package com.eliteteam.speakingcoach.app

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.JWTVerifier
import java.util.Date
import kotlin.time.Duration.Companion.days

internal class JwtTokens(secret: String) {
    private val algorithm = Algorithm.HMAC256(secret)
    val verifier: JWTVerifier = JWT.require(algorithm).build()

    fun issue(userId: String): String {
        val now = System.currentTimeMillis()
        return JWT.create()
            .withSubject(userId)
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(now + TTL.inWholeMilliseconds))
            .sign(algorithm)
    }

    private companion object {
        val TTL = 30.days
    }
}
