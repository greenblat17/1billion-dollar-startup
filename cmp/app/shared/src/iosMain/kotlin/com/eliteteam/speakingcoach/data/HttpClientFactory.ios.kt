package com.eliteteam.speakingcoach.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLSessionAuthChallengeUseCredential
import platform.Foundation.credentialForTrust
import platform.Foundation.serverTrust

actual fun createHttpClient(): HttpClient = HttpClient(Darwin) {
    engine {
        handleChallenge { _, _, challenge, completionHandler ->
            if (challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust) {
                val trust = challenge.protectionSpace.serverTrust
                if (trust != null) {
                    completionHandler(
                        NSURLSessionAuthChallengeUseCredential,
                        NSURLCredential.credentialForTrust(trust),
                    )
                    return@handleChallenge
                }
            }
            completionHandler(NSURLSessionAuthChallengeUseCredential, null)
        }
    }
    installAppHttp()
}

actual fun apiBaseUrl(): String = resolveApiBaseUrl()
