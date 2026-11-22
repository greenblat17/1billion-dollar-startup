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
    fun providerDescriptionDoesNotExposeQuotedInputOrCredentials() {
        val result = RuntimeDiagnostics.cleanMessage("Refused input 'PRIVATE_TEXT' Bearer SECRET_VALUE https://api.telegram.org/bot12345:SECRET/sendMessage")
        assertTrue(result.contains("Refused input"))
        assertFalse(result.contains("PRIVATE_TEXT"))
        assertFalse(result.contains("SECRET"))
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
        assertEquals("Forbidden", fields["provider_message"])
        assertFalse(fields.toString().contains("PRIVATE_TEXT"))
    }
}
