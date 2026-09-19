package com.eliteteam.speakingcoach.app

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.contentType
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.ExperimentalKtorApi
import io.ktor.server.routing.openapi.hide
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

internal data class AppApi(
    val store: AppStore,
    val tokens: JwtTokens,
    val passwords: PasswordHasher,
    val ai: InternalAi,
)

private val reviewJson = Json { ignoreUnknownKeys = true }

private val allowedTopics = setOf("Everyday", "Work", "Travel")
private val allowedVoices = setOf("marin", "cedar")

@OptIn(ExperimentalKtorApi::class)
internal fun Route.installAppRoutes(app: Application, api: AppApi) {
    post("/v1/auth/register") {
        val body = call.receive<RegisterRequest>()
        val email = normalizeEmail(body.email)
        val displayName = body.displayName.trim()
        if (email == null || body.password.isBlank() || displayName.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }
        val user = try {
            api.store.createUser(email, api.passwords.hash(body.password), displayName)
        } catch (_: DuplicateEmailException) {
            call.respond(HttpStatusCode.Conflict)
            return@post
        }
        call.respond(HttpStatusCode.Created, authResponse(api, user))
    }.hide()
    post("/v1/auth/login") {
        val body = call.receive<LoginRequest>()
        val email = normalizeEmail(body.email)
        if (email == null || body.password.isBlank()) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }
        val user = api.store.findUserByEmail(email)
        if (user == null || !api.passwords.matches(body.password, user.passwordHash)) {
            call.respond(HttpStatusCode.Unauthorized)
            return@post
        }
        call.respond(HttpStatusCode.OK, authResponse(api, user))
    }.hide()
    post("/v1/auth/logout") {
        call.respond(HttpStatusCode.NoContent)
    }.hide()

    authenticate("app-jwt") {
        get("/v1/home") {
            val user = call.appUser(api) ?: return@get
            call.respond(HomeResponse(userName = user.displayName))
        }.hide()
        post("/v1/sessions") {
            val user = call.appUser(api) ?: return@post
            val body = call.receive<CreateSessionRequest>()
            if (body.topic !in allowedTopics || body.tutorVoice !in allowedVoices) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            val session = api.store.createSession(user.id, body.topic, body.tutorVoice)
            call.respond(HttpStatusCode.Created, CreateSessionResponse(session.id))
        }.hide()
        route("/v1/sessions/{id}") {
            post("rtc") {
                val user = call.appUser(api) ?: return@post
                val session = ownedSession(call.parameters["id"], user.id, api) ?: run {
                    call.respond(HttpStatusCode.NotFound)
                    return@post
                }
                when (api.store.tryStartRtc(session.id)) {
                    RtcStart.NotFound -> {
                        call.respond(HttpStatusCode.NotFound)
                        return@post
                    }
                    RtcStart.Conflict -> {
                        call.respond(HttpStatusCode.Conflict)
                        return@post
                    }
                    is RtcStart.Ok -> Unit
                }
                val offer = call.readSdpOffer() ?: run {
                    api.store.releaseRtc(session.id)
                    call.respond(HttpStatusCode.BadRequest)
                    return@post
                }
                try {
                    val started = api.ai.startCall(offer, session.topic, session.tutorVoice)
                    api.store.attachOpenaiCallId(session.id, started.openaiCallId)
                    call.respondText(started.sdpAnswer, ContentType.parse("application/sdp"), HttpStatusCode.Created)
                } catch (error: Throwable) {
                    api.store.releaseRtc(session.id)
                    throw error
                }
            }.hide()
            post("complete") {
                val user = call.appUser(api) ?: return@post
                val session = ownedSession(call.parameters["id"], user.id, api) ?: run {
                    call.respond(HttpStatusCode.NotFound)
                    return@post
                }
                if (session.status == SessionStatus.Reviewing ||
                    session.status == SessionStatus.Ready ||
                    session.status == SessionStatus.TooShort ||
                    session.status == SessionStatus.ReviewFailed
                ) {
                    call.respond(HttpStatusCode.Conflict)
                    return@post
                }
                val body = call.receive<CompleteRequest>()
                val turns = body.turns.map { TranscriptTurn(it.role.trim(), it.text.trim()) }
                    .filter { it.role.isNotEmpty() && it.text.isNotEmpty() }
                val tooShort = turns.none { it.role == "user" }
                api.store.markCompleted(session.id, body.durationSec, tooShort)
                if (tooShort) {
                    call.respond(HttpStatusCode.UnprocessableEntity)
                    return@post
                }
                app.launch {
                    try {
                        val review = api.ai.review(turns)
                        api.store.saveReview(
                            session.id,
                            reviewJson.encodeToString(InternalReviewResponse.serializer(), review),
                        )
                    } catch (_: Throwable) {
                        api.store.markReviewFailed(session.id)
                    }
                }
                call.respond(HttpStatusCode.Accepted)
            }.hide()
            get("review") {
                val user = call.appUser(api) ?: return@get
                val session = ownedSession(call.parameters["id"], user.id, api) ?: run {
                    call.respond(HttpStatusCode.NotFound)
                    return@get
                }
                when (session.status) {
                    SessionStatus.TooShort -> call.respond(HttpStatusCode.UnprocessableEntity)
                    SessionStatus.ReviewFailed -> call.respond(HttpStatusCode.InternalServerError)
                    SessionStatus.Ready -> {
                        val payload = api.store.findReviewJson(session.id)
                        if (payload == null) {
                            call.respond(HttpStatusCode.Accepted)
                            return@get
                        }
                        val review = reviewJson.decodeFromString(InternalReviewResponse.serializer(), payload)
                        call.respond(ReviewResponse(sessionId = session.id, steps = review.steps))
                    }
                    SessionStatus.Reviewing -> call.respond(HttpStatusCode.Accepted)
                    SessionStatus.Created, SessionStatus.Rtc -> call.respond(HttpStatusCode.NotFound)
                }
            }.hide()
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.readSdpOffer(): String? {
    val contentType = request.contentType()
    val sdp = if (contentType.match(ContentType.Application.Json)) {
        receive<RtcOfferJson>().sdp
    } else {
        receiveText()
    }
    return sdp.takeIf { it.isNotBlank() }
}

private fun authResponse(api: AppApi, user: AppUser): AuthResponse =
    AuthResponse(
        token = api.tokens.issue(user.id),
        user = AuthUserResponse(id = user.id, email = user.email, displayName = user.displayName),
    )

private suspend fun io.ktor.server.application.ApplicationCall.appUser(api: AppApi): AppUser? {
    val userId = principal<JWTPrincipal>()?.payload?.subject
    if (userId.isNullOrBlank()) {
        respond(HttpStatusCode.Unauthorized)
        return null
    }
    val user = api.store.findUserById(userId)
    if (user == null) {
        respond(HttpStatusCode.Unauthorized)
        return null
    }
    return user
}

private suspend fun ownedSession(sessionId: String?, userId: String, api: AppApi): SpeakingSession? {
    if (sessionId.isNullOrBlank()) {
        return null
    }
    val session = api.store.findSession(sessionId) ?: return null
    if (session.userId != userId) {
        return null
    }
    return session
}

private fun normalizeEmail(raw: String): String? {
    val email = raw.trim().lowercase()
    if (email.isEmpty() || '@' !in email) {
        return null
    }
    return email
}
