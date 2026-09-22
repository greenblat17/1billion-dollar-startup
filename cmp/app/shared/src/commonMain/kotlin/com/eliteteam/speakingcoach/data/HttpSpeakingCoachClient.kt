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
import io.ktor.client.statement.bodyAsText
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

    init {
        logger.i { "baseUrl=$root" }
    }

    override suspend fun register(email: String, password: String, displayName: String): AuthSession {
        val response = execute("POST", "/v1/auth/register") {
            http.post("$root/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(RegisterRequestDto(email = email, password = password, displayName = displayName))
            }
        }
        val session = readAuth(response)
        logger.i { "POST /v1/auth/register HTTP ${response.status.value} userId=${session.user.id}" }
        return session
    }

    override suspend fun login(email: String, password: String): AuthSession {
        val response = execute("POST", "/v1/auth/login") {
            http.post("$root/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequestDto(email = email, password = password))
            }
        }
        val session = readAuth(response)
        logger.i { "POST /v1/auth/login HTTP ${response.status.value} userId=${session.user.id}" }
        return session
    }

    override suspend fun logout() {
        val response = execute("POST", "/v1/auth/logout") {
            http.post("$root/v1/auth/logout") { applyBearer() }
        }
        logger.i { "POST /v1/auth/logout HTTP ${response.status.value}" }
    }

    override suspend fun loadHome(): String {
        val response = execute("GET", "/v1/home") {
            http.get("$root/v1/home") { applyBearer() }
        }
        val name = response.body<HomeResponseDto>().userName
        logger.i { "GET /v1/home HTTP ${response.status.value}" }
        return name
    }

    override suspend fun createSession(topic: String, tutorVoice: String): String {
        val response = execute("POST", "/v1/sessions") {
            http.post("$root/v1/sessions") {
                applyBearer()
                contentType(ContentType.Application.Json)
                setBody(CreateSessionRequestDto(topic = topic, tutorVoice = tutorVoice))
            }
        }
        val sessionId = response.body<CreateSessionResponseDto>().sessionId
        logger.i { "POST /v1/sessions HTTP ${response.status.value} sessionId=$sessionId topic=$topic voice=$tutorVoice" }
        return sessionId
    }

    override suspend fun startRtc(sessionId: String, sdpOffer: String): String {
        val path = "/v1/sessions/$sessionId/rtc"
        val response = execute("POST", path) {
            http.post("$root$path") {
                applyBearer()
                contentType(ContentType.parse("application/sdp"))
                setBody(sdpOffer)
            }
        }
        val answer = response.bodyAsText()
        logger.i { "POST $path HTTP ${response.status.value} answerBytes=${answer.length}" }
        return answer
    }

    override suspend fun completeSession(
        sessionId: String,
        turns: List<TranscriptTurn>,
        durationSec: Int,
    ) {
        val path = "/v1/sessions/$sessionId/complete"
        val response = execute("POST", path) {
            http.post("$root$path") {
                applyBearer()
                contentType(ContentType.Application.Json)
                setBody(
                    CompleteRequestDto(
                        turns = turns.map { CompleteTurnDto(it.role, it.text) },
                        durationSec = durationSec,
                    ),
                )
            }
        }
        logger.i { "POST $path HTTP ${response.status.value} turns=${turns.size} durationSec=$durationSec" }
    }

    override suspend fun pollReview(sessionId: String): ReviewPoll {
        val path = "/v1/sessions/$sessionId/review"
        logger.i { "GET $path" }
        val response = try {
            http.get("$root$path") { applyBearer() }
        } catch (error: Throwable) {
            logger.e(error) { "GET $path ${error::class.simpleName}" }
            throw error
        }
        return when (response.status) {
            HttpStatusCode.OK -> {
                val body = response.body<ReviewResponseDto>()
                logger.i { "GET $path HTTP 200 steps=${body.steps.size}" }
                ReviewPoll.Ready(body.steps)
            }
            HttpStatusCode.Accepted -> {
                logger.i { "GET $path HTTP 202" }
                ReviewPoll.Pending
            }
            HttpStatusCode.UnprocessableEntity -> {
                logger.w { "GET $path HTTP 422" }
                ReviewPoll.TooShort
            }
            else -> {
                logger.w { "GET $path HTTP ${response.status.value}" }
                if (response.status.isSuccess()) ReviewPoll.Failed else throw ApiException(response.status)
            }
        }
    }

    private fun HttpRequestBuilder.applyBearer() {
        sessionStore.session.value?.token?.let { bearerAuth(it) }
    }

    private suspend fun execute(method: String, path: String, block: suspend () -> HttpResponse): HttpResponse {
        logger.i { "$method $path" }
        val response = try {
            block()
        } catch (error: Throwable) {
            logger.e(error) { "$method $path ${error::class.simpleName}" }
            throw error
        }
        if (!response.status.isSuccess()) {
            logger.w { "$method $path HTTP ${response.status.value}" }
            throw ApiException(response.status)
        }
        return response
    }

    private suspend fun readAuth(response: HttpResponse): AuthSession {
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
}
