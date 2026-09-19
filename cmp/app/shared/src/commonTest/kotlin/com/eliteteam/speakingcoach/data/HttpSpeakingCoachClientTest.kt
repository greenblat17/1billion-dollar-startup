package com.eliteteam.speakingcoach.data

import co.touchlab.kermit.ExperimentalKermitApi
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.TestConfig
import co.touchlab.kermit.TestLogWriter
import com.eliteteam.speakingcoach.testLogger
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HttpSpeakingCoachClientTest {
    @Test
    fun registerSavesAuthPayload() = runTest {
        val http = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    assertEquals("/v1/auth/register", request.url.encodedPath)
                    respond(
                        content = """{"token":"jwt-1","user":{"id":"u1","email":"ed@example.com","displayName":"Ed"}}""",
                        status = HttpStatusCode.Created,
                        headers = jsonHeaders,
                    )
                }
            }
            installJson()
        }
        val client = client(http)
        val session = client.register("Ed@example.com", "secret12", "Ed")
        assertEquals("jwt-1", session.token)
        assertEquals("ed@example.com", session.user.email)
        assertEquals("Ed", session.user.displayName)
        http.close()
    }

    @Test
    fun loginConflictBecomesApiException() = runTest {
        val http = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond("{}", status = HttpStatusCode.Unauthorized, headers = jsonHeaders)
                }
            }
            installJson()
        }
        val client = client(http)
        val error = assertFailsWith<ApiException> {
            client.login("ed@example.com", "nope")
        }
        assertEquals(HttpStatusCode.Unauthorized, error.status)
        http.close()
    }

    @OptIn(ExperimentalKermitApi::class)
    @Test
    fun loginFailureLogsHttpStatus() = runTest {
        val writer = TestLogWriter(loggable = Severity.Verbose)
        val logger = Logger(
            TestConfig(minSeverity = Severity.Debug, logWriterList = listOf(writer)),
            "HttpSpeakingCoachClient",
        )
        val http = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond("{}", status = HttpStatusCode.Unauthorized, headers = jsonHeaders)
                }
            }
            installJson()
        }
        val client = client(http, logger = logger)
        assertFailsWith<ApiException> { client.login("ed@example.com", "nope") }
        http.close()
        writer.assertLast { message == "POST /v1/auth/login HTTP 401" && severity == Severity.Warn }
    }

    @Test
    fun createSessionSendsBearerAndTopic() = runTest {
        val store = SessionStore(MapSettings(), testLogger())
        store.save(
            AuthSession(
                token = "jwt-1",
                user = AuthUser(id = "u1", email = "ed@example.com", displayName = "Ed"),
            ),
        )
        val http = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    assertEquals("/v1/sessions", request.url.encodedPath)
                    assertTrue(request.headers[HttpHeaders.Authorization]?.startsWith("Bearer jwt-1") == true)
                    respond(
                        """{"sessionId":"app-u1-1"}""",
                        status = HttpStatusCode.Created,
                        headers = jsonHeaders,
                    )
                }
            }
            installJson()
        }
        val client = client(http, store)
        assertEquals("app-u1-1", client.createSession("Work", "marin"))
        http.close()
    }

    private fun HttpClientConfig<*>.installJson() {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private fun client(
        http: HttpClient,
        store: SessionStore = SessionStore(MapSettings(), testLogger()),
        logger: Logger = testLogger(),
    ) = HttpSpeakingCoachClient(
        http = http,
        sessionStore = store,
        logger = logger,
        baseUrl = "https://example.test",
    )

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
}

class SessionStoreTest {
    @Test
    fun saveAndClearRoundTrip() {
        val store = SessionStore(MapSettings(), testLogger())
        store.save(
            AuthSession(
                token = "jwt",
                user = AuthUser(id = "1", email = "a@b.c", displayName = "A"),
            ),
        )
        assertEquals("jwt", store.session.value?.token)
        store.clear()
        assertEquals(null, store.session.value)
    }
}
