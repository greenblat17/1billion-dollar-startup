package com.eliteteam.speakingcoach.data

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess

class HttpSpeakingCoachClient(
    private val http: HttpClient,
    private val sessionStore: SessionStore,
    private val logger: Logger,
    baseUrl: String,
) : SpeakingCoachClient {
    private val root = baseUrl.trimEnd('/')

    override suspend fun register(email: String, password: String, displayName: String): AuthSession {
        val response = http.post("$root/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequestDto(email = email, password = password, displayName = displayName))
        }
        return readAuth(response)
    }

    override suspend fun login(email: String, password: String): AuthSession {
        val response = http.post("$root/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequestDto(email = email, password = password))
        }
        return readAuth(response)
    }

    override suspend fun logout() {
        val response = http.post("$root/v1/auth/logout") { applyBearer() }
        if (response.status == HttpStatusCode.Unauthorized) {
            throw ApiException(response.status)
        }
        if (!response.status.isSuccess()) {
            logger.w { "logout failed HTTP ${response.status.value}" }
            throw ApiException(response.status)
        }
    }

    override suspend fun loadHome(): String {
        val response = http.get("$root/v1/home") { applyBearer() }
        ensureSuccess(response)
        return response.body<HomeResponseDto>().userName
    }

    override suspend fun createSession(topic: String, tutorVoice: String): String {
        val response = http.post("$root/v1/sessions") {
            applyBearer()
            contentType(ContentType.Application.Json)
            setBody(CreateSessionRequestDto(topic = topic, tutorVoice = tutorVoice))
        }
        ensureSuccess(response)
        return response.body<CreateSessionResponseDto>().sessionId
    }

    private fun HttpRequestBuilder.applyBearer() {
        sessionStore.session.value?.token?.let { bearerAuth(it) }
    }

    private suspend fun readAuth(response: HttpResponse): AuthSession {
        if (!response.status.isSuccess()) {
            throw ApiException(response.status)
        }
        val body = response.body<AuthResponseDto>()
        return AuthSession(
            token = body.token,
            user = AuthUser(
                id = body.user.id,
                email = body.user.email,
                displayName = body.user.displayName,
            ),
        )
    }

    private fun ensureSuccess(response: HttpResponse) {
        if (!response.status.isSuccess()) {
            throw ApiException(response.status)
        }
    }
}
