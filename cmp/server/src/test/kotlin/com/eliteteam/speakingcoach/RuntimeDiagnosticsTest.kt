package com.eliteteam.speakingcoach

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.OutputStreamAppender
import ch.qos.logback.core.encoder.EncoderBase
import java.io.IOException
import java.io.OutputStream
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

class RuntimeDiagnosticsTest {
    @Test
    fun writerEmitsTheSharedEnvelopeAndRejectsProviderOverrides() {
        DiagnosticOutput().use { output ->
            RuntimeDiagnostics.record("provider_call", "response", providerMetrics = Json.parseToJsonElement(
                """{"source":"telegram","user_id":"invalid_private_value","trace_id":"private_override",
                   "outcome":"failed","type":"telegram_api","result":"success","request":{"token":"private_nested"}}""").jsonObject)
            val actual = output.events().single()
            val contract = checkNotNull(javaClass.getResourceAsStream("/envelope.json")).use { stream ->
                Json.parseToJsonElement(stream.bufferedReader().readText()).jsonObject
            }
            contract.getValue("required").jsonArray.forEach { assertTrue(it.jsonPrimitive.content in actual, it.toString()) }
            for ((key, property) in contract.getValue("properties").jsonObject) {
                val value = actual[key]?.jsonPrimitive ?: continue
                val type = property.jsonObject.getValue("type")
                val types = if (type is kotlinx.serialization.json.JsonArray) type.map { it.jsonPrimitive.content }
                    else listOf(type.jsonPrimitive.content)
                val actualType = when {
                    value == JsonNull -> "null"
                    value.isString -> "string"
                    else -> { assertTrue(value.intOrNull != null); "integer" }
                }
                assertTrue(actualType in types, key)
            }
            assertEquals(1, actual.getValue("schema_version").jsonPrimitive.intOrNull)
            assertEquals("completed", actual.getValue("outcome").jsonPrimitive.content)
            assertTrue(output.lines().single().startsWith("provider_call "))
            assertFalse(output.lines().single().contains("private_"))
        }
    }

    @Test
    fun writerBoundsFallbackTextAndKeepsExpectedDecisionsOutOfFailureClassification() {
        DiagnosticOutput().use { output ->
            RuntimeDiagnostics.record("diagnostic_example", "a".repeat(128), extra = mapOf(
                "operation" to "b".repeat(128), "reason_code" to "c".repeat(128), "outcome" to "skipped", "duration_ms" to "-1"))
            val payload = output.events().single()
            assertTrue(payload.getValue("message").jsonPrimitive.content.length <= 280)
            assertEquals(JsonNull, payload["duration_ms"])
            assertFalse("failure_class" in payload)
        }
    }

    @Test
    fun logbackStreamFailureLeavesTheOperationOutcomeAndReportsTheLoss() {
        val logger = LoggerFactory.getLogger("RuntimeDiagnostics") as Logger
        val failing = OutputStreamAppender<ILoggingEvent>().apply {
            context = logger.loggerContext
            encoder = object : EncoderBase<ILoggingEvent>() {
                override fun headerBytes(): ByteArray? = null
                override fun footerBytes(): ByteArray? = null
                override fun encode(event: ILoggingEvent): ByteArray = event.formattedMessage.toByteArray()
            }
            setOutputStream(object : OutputStream() {
                override fun write(value: Int) { throw IOException("private_sink_failure") }
            })
            start()
        }
        DiagnosticOutput().use { output ->
            logger.addAppender(failing)
            try {
                assertEquals(null, RuntimeDiagnostics.record("sink_test", "write"))
            } finally {
                logger.detachAppender(failing)
                failing.stop()
            }
            RuntimeDiagnostics.record("sink_test", "write")
            val events = output.events()
            assertEquals(listOf("sink_test", "diagnostic_records_lost", "sink_test"),
                events.map { it.getValue("event").jsonPrimitive.content })
            assertEquals(1, events[1].getValue("count").jsonPrimitive.intOrNull)
            assertFalse(output.lines().any { "private_sink_failure" in it })
        }
    }

    @Test
    fun databaseErrorsKeepSqlStateWithoutQueryValues() {
        val result = RuntimeDiagnostics.fields("failed", "query", SQLException("PRIVATE_SQL_VALUE", "23505", 7), emptyMap()).toString()
        assertTrue(result.contains("23505"))
        assertTrue(result.contains("vendor_code"))
        assertFalse(result.contains("PRIVATE_SQL_VALUE"))
    }

    @Test
    fun untrustedIdentifiersAndNestedValuesAreDropped() {
        assertEquals(null, RuntimeDiagnostics.ingressId("bearer-token-with-no-spaces"))
        assertEquals(null, RuntimeDiagnostics.ingressId("1234567890"))
        val result = RuntimeDiagnostics.fields("failed", "response", null, mapOf(
            "provider_message" to "private_name", "unexpected" to "{\"token\":\"private_token\"}")).toString()
        assertFalse(result.contains("private_"))
    }

    @Test
    fun propagatedErrorsShareOneIdAndOneStack() {
        val failure = SQLException("private query", "08006")
        val first = RuntimeDiagnostics.fields("backend_command_failed", "persist", failure, emptyMap())
        val second = RuntimeDiagnostics.fields("foundation_failed", "persist", failure, emptyMap())
        assertEquals(first["error_id"], second["error_id"])
        assertTrue("stack" in first)
        assertFalse("stack" in second)
        assertEquals("\"transient\"", second["failure_class"].toString())
    }

    @Test
    fun tracesSurviveSuspensionWithoutCrossingRequests() = runTest {
        val results = listOf("one", "two").map { id -> async {
            RuntimeDiagnostics.withContext(mapOf("trace_id" to id)) {
                yield()
                RuntimeDiagnostics.current()["trace_id"]
            }
        } }.awaitAll()
        assertEquals(listOf("one", "two"), results)
        assertTrue(RuntimeDiagnostics.current().isEmpty())
    }

    @Test
    fun httpFailureExtractsCodeWithoutCopyingResponsePayload() {
        val fields = RuntimeDiagnostics.httpFields(403, """{"error_code":403,"description":"Forbidden","payload":"PRIVATE_TEXT"}""")
        assertEquals("403", fields["provider_code"])
        assertFalse("provider_message" in fields)
        assertFalse(fields.toString().contains("PRIVATE_TEXT"))
    }
}
