package com.eliteteam.speakingcoach

import com.sun.net.httpserver.HttpServer
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals

class AiHttpClientConnectionTest {
    @Test
    fun reusesConnectionsForRepeatedStatusPolls() = runBlocking {
        val sourcePorts = ConcurrentHashMap.newKeySet<Int>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/status") { exchange ->
            sourcePorts.add(exchange.remoteAddress.port)
            val body = "ok".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val client = speakingCoachAiHttpClient()
        try {
            repeat(15) {
                assertEquals("ok", client.get("http://127.0.0.1:${server.address.port}/status").body<String>())
            }
            assertEquals(1, sourcePorts.size)
            delay(4_000)
            assertEquals("ok", client.get("http://127.0.0.1:${server.address.port}/status").body<String>())
            assertEquals(2, sourcePorts.size)
        } finally {
            client.close()
            server.stop(0)
        }
    }
}
