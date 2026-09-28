package com.eliteteam.speakingcoach

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationTest {

    @Test
    fun testRoot() = testApplication {
        application {
            module()
        }
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Hello, Ktor!", response.bodyAsText())
    }

    @Test
    fun healthIsOpen() = testApplication {
        application {
            module()
        }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.bodyAsText())
    }

    @Test
    fun doesNotExposeTelegramOrClipApi() = testApplication {
        application {
            module()
        }
        assertEquals(HttpStatusCode.NotFound, client.post("/telegram/webhook").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/swagger").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/clips/x").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/admin/metrics").status)
    }
}
