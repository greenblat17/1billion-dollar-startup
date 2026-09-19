package com.eliteteam.speakingcoach.app

import com.eliteteam.speakingcoach.AppConfig
import com.eliteteam.speakingcoach.module
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppApiTest {

    @Test
    fun registerLoginHomeAndSessionReview() = testApplication {
        val api = testAppApi()
        application { module(appTestConfig(), api) }
        val client = jsonClient()

        val registered = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"email":"Alex@example.com","password":"secret12","displayName":"Алекс"}""",
            )
        }
        assertEquals(HttpStatusCode.Created, registered.status)
        val auth = registered.body<AuthResponse>()
        assertEquals("alex@example.com", auth.user.email)
        assertEquals("Алекс", auth.user.displayName)

        val home = client.get("/v1/home") { bearerAuth(auth.token) }
        assertEquals(HttpStatusCode.OK, home.status)
        assertEquals("Алекс", home.body<HomeResponse>().userName)

        val created = client.post("/v1/sessions") {
            bearerAuth(auth.token)
            contentType(ContentType.Application.Json)
            setBody("""{"topic":"Work","tutorVoice":"marin"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val sessionId = created.body<CreateSessionResponse>().sessionId
        assertTrue(sessionId.startsWith("app-${auth.user.id}-"))

        val rtc = client.post("/v1/sessions/$sessionId/rtc") {
            bearerAuth(auth.token)
            header(HttpHeaders.ContentType, "application/sdp")
            setBody("v=0 offer")
        }
        assertEquals(HttpStatusCode.Created, rtc.status)
        assertEquals("application/sdp", rtc.contentType()?.withoutParameters().toString())
        assertEquals("v=0 answer", rtc.bodyAsText())

        val complete = client.post("/v1/sessions/$sessionId/complete") {
            bearerAuth(auth.token)
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "turns": [
                    {"role":"assistant","text":"Hey!"},
                    {"role":"user","text":"I work here since 2023."}
                  ],
                  "durationSec": 20
                }
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.Accepted, complete.status)

        var reviewStatus = HttpStatusCode.Accepted
        var review: ReviewResponse? = null
        repeat(40) {
            val response = client.get("/v1/sessions/$sessionId/review") { bearerAuth(auth.token) }
            reviewStatus = response.status
            if (response.status == HttpStatusCode.OK) {
                review = response.body()
                return@repeat
            }
            Thread.sleep(25)
        }
        assertEquals(HttpStatusCode.OK, reviewStatus)
        assertEquals(sessionId, review?.sessionId)
        assertEquals(listOf("Grammar", "Vocabulary"), review?.steps?.map { it.metric })
    }

    @Test
    fun duplicateEmailIsConflictAndBadPasswordIsUnauthorized() = testApplication {
        val api = testAppApi()
        application { module(appTestConfig(), api) }
        val client = jsonClient()
        val body = """{"email":"a@b.c","password":"secret12","displayName":"A"}"""
        assertEquals(
            HttpStatusCode.Created,
            client.post("/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }.status,
        )
        assertEquals(
            HttpStatusCode.Conflict,
            client.post("/v1/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }.status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"a@b.c","password":"nope"}""")
            }.status,
        )
    }

    @Test
    fun completeWithoutUserSpeechIsUnprocessable() = testApplication {
        val api = testAppApi()
        application { module(appTestConfig(), api) }
        val client = jsonClient()
        val token = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"u@e.com","password":"secret12","displayName":"U"}""")
        }.body<AuthResponse>().token
        val sessionId = client.post("/v1/sessions") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"topic":"Travel","tutorVoice":"cedar"}""")
        }.body<CreateSessionResponse>().sessionId
        val complete = client.post("/v1/sessions/$sessionId/complete") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"turns":[{"role":"assistant","text":"Hi"}],"durationSec":3}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, complete.status)
        assertEquals(
            HttpStatusCode.UnprocessableEntity,
            client.get("/v1/sessions/$sessionId/review") { bearerAuth(token) }.status,
        )
    }

    @Test
    fun secondRtcForSameUserIsConflict() = testApplication {
        val api = testAppApi()
        application { module(appTestConfig(), api) }
        val client = jsonClient()
        val token = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"r@e.com","password":"secret12","displayName":"R"}""")
        }.body<AuthResponse>().token
        val first = client.post("/v1/sessions") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"topic":"Everyday","tutorVoice":"marin"}""")
        }.body<CreateSessionResponse>().sessionId
        assertEquals(
            HttpStatusCode.Created,
            client.post("/v1/sessions/$first/rtc") {
                bearerAuth(token)
                header(HttpHeaders.ContentType, "application/sdp")
                setBody("v=0")
            }.status,
        )
        val second = client.post("/v1/sessions") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"topic":"Work","tutorVoice":"marin"}""")
        }.body<CreateSessionResponse>().sessionId
        assertEquals(
            HttpStatusCode.Conflict,
            client.post("/v1/sessions/$second/rtc") {
                bearerAuth(token)
                header(HttpHeaders.ContentType, "application/sdp")
                setBody("v=0")
            }.status,
        )
    }

    @Test
    fun homeWithoutBearerIsUnauthorized() = testApplication {
        application { module(appTestConfig(), testAppApi()) }
        val response = client.get("/v1/home")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    private fun io.ktor.server.testing.ClientProvider.jsonClient() = createClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    private fun testAppApi(): AppApi = AppApi(
        store = MemoryAppStore(),
        tokens = JwtTokens("test-jwt-secret-which-is-long-enough"),
        passwords = PasswordHasher(),
        ai = FakeInternalAi(),
    )

    private fun appTestConfig() = AppConfig(
        telegramBotToken = null,
        telegramWebhookUrl = null,
        telegramWebhookSecret = null,
        aiServiceBaseUrl = "http://127.0.0.1:8090",
        aiInternalToken = "test-internal-token",
        serverPort = 8080,
        tlsCertPath = AppConfig.DEFAULT_TLS_CERT_PATH,
        tlsKeyPath = AppConfig.DEFAULT_TLS_KEY_PATH,
        jwtSecret = "test-jwt-secret-which-is-long-enough",
        databaseUrl = null,
    )
}

private class FakeInternalAi : InternalAi {
    override suspend fun startCall(sdp: String, topic: String, tutorVoice: String): RealtimeCall =
        RealtimeCall(sdpAnswer = "v=0 answer", openaiCallId = "rtc_test")

    override suspend fun review(turns: List<TranscriptTurn>): InternalReviewResponse =
        InternalReviewResponse(
            steps = listOf(
                ReviewStepDto(
                    metric = "Grammar",
                    score = 78,
                    lead = "Tenses",
                    bullets = listOf("Past Simple"),
                    examples = listOf(
                        ReviewExampleDto(
                            original = "I work here since 2023.",
                            improved = "I have worked here since 2023.",
                        ),
                    ),
                    tip = "Watch verb tense.",
                ),
                ReviewStepDto(
                    metric = "Vocabulary",
                    score = 84,
                    lead = "Word choice",
                    bullets = listOf("Stronger verbs"),
                    examples = emptyList(),
                    tip = "Swap filler words.",
                ),
            ),
        )
}
