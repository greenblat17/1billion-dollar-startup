package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.ai.HttpClipClient
import com.eliteteam.speakingcoach.speaking.AudioClip
import com.eliteteam.speakingcoach.speaking.SessionId
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class AiHttpClientMockLoadTest {
    @Test
    fun processesTenClipsPerSecondWithMockAi() = runBlocking {
        val seconds = System.getenv("MOCK_AI_LOAD_SECONDS")?.toIntOrNull() ?: 20
        require(seconds in 1..120)
        val count = seconds * 10
        val submitted = AtomicInteger()
        val downloaded = AtomicInteger()
        val executor = Executors.newFixedThreadPool(16)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = executor
            createContext("/v1/clips") { exchange ->
                when {
                    exchange.requestMethod == "POST" && exchange.requestURI.path == "/v1/clips" -> {
                        exchange.requestBody.use { it.readBytes() }
                        assertEquals("1", exchange.requestHeaders.getFirst("X-Speaky-Load-Test"))
                        val id = submitted.incrementAndGet().toString()
                        exchange.respond(202, "{\"jobId\":\"$id\"}")
                    }
                    exchange.requestMethod == "GET" && exchange.requestURI.path.endsWith("/audio") -> {
                        downloaded.incrementAndGet()
                        exchange.respond(200, "mock audio", "audio/ogg")
                    }
                    exchange.requestMethod == "GET" -> {
                        val id = exchange.requestURI.path.substringAfterLast('/')
                        exchange.respond(200, "{\"jobId\":\"$id\",\"status\":\"ok\",\"replyText\":\"mock\"}")
                    }
                    else -> exchange.respond(404, "")
                }
            }
            start()
        }
        val http = speakingCoachAiHttpClient()
        val ai = HttpClipClient("http://127.0.0.1:${server.address.port}", http)
        val clip = AudioClip("mock input".toByteArray(), "audio/ogg", "mock.ogg", loadTest = true)
        try {
            val replies = withTimeout((seconds + 30) * 1_000L) {
                coroutineScope {
                    (0 until count).map { index ->
                        async {
                            delay(index * 100L)
                            ai.process(SessionId("mock-$index"), clip)
                        }
                    }.awaitAll()
                }
            }
            assertEquals(count, replies.size)
            assertEquals(count, submitted.get())
            assertEquals(count, downloaded.get())
        } finally {
            http.close()
            server.stop(0)
            executor.shutdownNow()
        }
    }
}

private fun HttpExchange.respond(status: Int, body: String, contentType: String = "application/json") {
    val bytes = body.toByteArray()
    responseHeaders.add("Content-Type", contentType)
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}
