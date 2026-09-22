package com.eliteteam.speakingcoach.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

actual fun createHttpClient(): HttpClient = HttpClient(OkHttp) {
    engine {
        preconfigured = insecureOkHttp()
    }
    installAppHttp()
}

actual fun apiBaseUrl(): String = resolveApiBaseUrl()

private fun insecureOkHttp(): OkHttpClient {
    val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    val ssl = SSLContext.getInstance("TLS")
    ssl.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
    return OkHttpClient.Builder()
        .sslSocketFactory(ssl.socketFactory, trustAll)
        .hostnameVerifier { _, _ -> true }
        .build()
}
