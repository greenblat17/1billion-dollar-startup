package com.eliteteam.speakingcoach.foundation

import com.eliteteam.speakingcoach.RuntimeDiagnostics
import com.eliteteam.speakingcoach.RuntimeDiagnostics.Event
import com.eliteteam.speakingcoach.RuntimeDiagnostics.Outcome
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.sql.SQLException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlin.system.exitProcess

internal suspend fun runFoundationOperatorCommand(operation: String, block: suspend () -> Unit) {
    RuntimeDiagnostics.withContext(mapOf("trace_id" to RuntimeDiagnostics.newId())) {
        try {
            block()
        } catch (error: CancellationException) {
            RuntimeDiagnostics.record(Event("foundation_operator_command", operation, "execute", Outcome.CANCELLED,
                "The operator command was cancelled.", "operator_command_cancelled"))
            throw error
        } catch (error: Exception) {
            val reason = when (error) {
                is FoundationFailure -> error.code
                is SerializationException -> "invalid_source_document"
                is FileAlreadyExistsException -> "destination_exists"
                is FileSystemException -> "operator_file_unavailable"
                is SQLException -> "storage_unavailable"
                is IllegalArgumentException -> "invalid_arguments"
                else -> "operator_command_failed"
            }
            val rejected = error is FoundationFailure || error is IllegalArgumentException || error is FileAlreadyExistsException
            RuntimeDiagnostics.record(Event("foundation_operator_command", operation, "execute",
                if (rejected) Outcome.REJECTED else Outcome.FAILED,
                "The operator command stopped before it completed.", reason), error = error)
            exitProcess(1)
        }
    }
}
