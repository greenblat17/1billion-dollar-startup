package com.eliteteam.speakingcoach.app

import at.favre.lib.crypto.bcrypt.BCrypt

internal class PasswordHasher {
    fun hash(password: String): String =
        BCrypt.withDefaults().hashToString(COST, password.toCharArray())

    fun matches(password: String, hash: String): Boolean =
        BCrypt.verifyer().verify(password.toCharArray(), hash).verified

    private companion object {
        const val COST = 10
    }
}
