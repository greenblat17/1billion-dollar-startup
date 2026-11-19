package com.eliteteam.speakingcoach

import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

class RuntimeDiagnosticsTest {
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
