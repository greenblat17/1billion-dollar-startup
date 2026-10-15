package com.eliteteam.speakingcoach.load

import com.eliteteam.speakingcoach.AppConfig
import com.eliteteam.speakingcoach.ai.HttpClipClient
import com.eliteteam.speakingcoach.speaking.AudioClip
import com.eliteteam.speakingcoach.speaking.ClipSource
import com.eliteteam.speakingcoach.speaking.ClipSubmitResult
import com.eliteteam.speakingcoach.speaking.SessionClipQueue
import com.eliteteam.speakingcoach.speaking.SessionId
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.contentType
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.openapi.hide
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.ExperimentalKtorApi
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import org.slf4j.LoggerFactory
import java.security.MessageDigest

private const val TOKEN_HEADER = "X-Load-Test-Token"
private const val MAX_AUDIO_BYTES = 1024 * 1024
private val SESSION_ID = Regex("loadtest-[A-Za-z0-9_-]{1,90}")
private val log = LoggerFactory.getLogger("LoadTestRoutes")

@OptIn(ExperimentalKtorApi::class)
internal fun Route.installLoadTestRoutes(config: AppConfig, ai: HttpClipClient, queue: SessionClipQueue) {
    check(config.loadTestEnabled)
    val token = checkNotNull(config.loadTestToken).toByteArray(Charsets.UTF_8)
    route("/internal/load-test") {
        post("/sessions") {
            if (!authorized(token, call.request.header(TOKEN_HEADER))) {
                call.respond(HttpStatusCode.Forbidden)
                return@post
            }
            val sessionId = call.request.queryParameters["sessionId"]?.takeIf { SESSION_ID.matches(it) }
            if (sessionId == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            try {
                ai.createLoadSession(SessionId(sessionId))
                call.respond(HttpStatusCode.Created)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.warn("Load-test session creation failed", error)
                call.respond(HttpStatusCode.BadGateway)
            }
        }.hide()

        post("/clips") {
            if (!authorized(token, call.request.header(TOKEN_HEADER))) {
                call.respond(HttpStatusCode.Forbidden)
                return@post
            }
            val sessionId = call.request.queryParameters["sessionId"]?.takeIf { SESSION_ID.matches(it) }
            val duration = call.request.queryParameters["durationSeconds"]?.toDoubleOrNull() ?: 0.0
            if (sessionId == null || !duration.isFinite() || duration !in 0.0..60.0 ||
                call.request.contentType().toString().substringBefore(';') != "audio/ogg"
            ) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            val audio = call.receiveChannel().readRemaining(MAX_AUDIO_BYTES.toLong() + 1).readByteArray()
            if (audio.isEmpty() || audio.size > MAX_AUDIO_BYTES) {
                call.respond(HttpStatusCode.PayloadTooLarge)
                return@post
            }
            try {
                val result = queue.submit(SessionId(sessionId), ClipSource {
                    AudioClip(audio, "audio/ogg", "probe.ogg", durationSeconds = duration, loadTest = true)
                })
                when (result) {
                    is ClipSubmitResult.Completed -> call.respondText("ok")
                    ClipSubmitResult.QueueFull -> call.respond(HttpStatusCode.TooManyRequests)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.warn("Load-test clip failed", error)
                call.respond(HttpStatusCode.BadGateway)
            }
        }.hide()
    }.hide()
}

private fun authorized(expected: ByteArray, provided: String?): Boolean =
    provided != null && MessageDigest.isEqual(expected, provided.toByteArray(Charsets.UTF_8))
