package com.eliteteam.speakingcoach.foundation

import com.eliteteam.speakingcoach.RuntimeDiagnostics
import com.eliteteam.speakingcoach.RuntimeDiagnostics.Event
import com.eliteteam.speakingcoach.RuntimeDiagnostics.Outcome
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Offline operations, with the serving application stopped during restore/deletion replay. */
fun main(args: Array<String>) = runBlocking {
    val action = args.firstOrNull()?.takeIf { it in setOf("export-deletions", "restore-deletions", "diagnostics", "retry-job") }
    val operation = "foundation.maintenance.${action ?: "unknown"}"
    runFoundationOperatorCommand(operation) {
        if (action == null || args.size !in 2..3 || (action == "retry-job" && args.size != 3)) {
            System.err.println("Usage: foundationMaintenance export-deletions|restore-deletions <file> OR diagnostics <student-id> OR retry-job <student-id> <job-id>")
            throw IllegalArgumentException()
        }
        val database = System.getenv("DATABASE_URL") ?: reject("database_configuration_missing")
        FoundationService(FoundationDatabase(database)).use { executeMaintenance(args, action, operation, it) }
    }
}

private suspend fun executeMaintenance(args: Array<String>, action: String, operation: String, service: FoundationService) {
    when (action) {
        "export-deletions" -> {
            val records = service.database.transaction(readOnly = true) { c, _ -> c.documents("SELECT to_jsonb(d) FROM foundation_deletion d ORDER BY deleted_at,id") }
            val path = Path.of(args[1])
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            Files.writeString(path, JsonArray(records).toString(), StandardOpenOption.WRITE)
            RuntimeDiagnostics.record(Event("foundation_deletion_ledger", operation, "write", Outcome.COMPLETED,
                "Foundation wrote the deletion ledger export."), mapOf("count" to records.size.toString()))
            println("Deletion records exported: ${records.size}")
        }
        "restore-deletions" -> {
            val records = foundationJson.parseToJsonElement(Files.readString(Path.of(args[1]))).jsonArray.map { it.jsonObject }
            service.database.transaction { c, _ ->
                var inserted = 0
                records.forEach { record ->
                    record.only("id", "student_id", "kind", "category", "fact_key", "deleted_at", "resulting_epoch")
                    val existing = c.document("SELECT to_jsonb(d) FROM foundation_deletion d WHERE id=?", record.text("id"))
                    if (existing != null && canonical(existing) != canonical(record)) reject("deletion_ledger_conflict")
                    if (existing == null) inserted += c.update(
                        "INSERT INTO foundation_deletion SELECT * FROM jsonb_populate_record(NULL::foundation_deletion,?::jsonb)", record)
                }
                // Replay before serving, including prior ledger records retained in the backup.
                val replayed = c.documents("SELECT to_jsonb(s) FROM student s WHERE deleted_at IS NULL FOR UPDATE").sumOf { student ->
                    FoundationMaintenance().replayDeletions(c,
                        ExecutionScope(student.text("id"), "operator", student.number("data_epoch")), service.database.clock.instant()).number("replayed")
                }
                val changed = inserted > 0 || replayed > 0
                FoundationDatabase.recordAfterCommit(Event("foundation_deletion_ledger", operation, "restore",
                    if (changed) Outcome.COMPLETED else Outcome.REPLAYED,
                    if (changed) "Foundation restored and replayed the deletion ledger."
                    else "The deletion ledger contained no changes to apply.",
                    if (changed) null else "deletion_ledger_current"),
                    mapOf("count" to records.size.toString()))
            }
            println("Deletion ledger restored and replayed")
        }
        "retry-job" -> {
            val scope = service.identity.issue(args[1], "operator")
            println(service.command(scope, json("version" to 1, "operationId" to newId(), "action" to "job.retry", "args" to json("jobId" to args[2]))))
        }
        "diagnostics" -> {
            val scope = service.identity.issue(args[1], "operator")
            val result = service.diagnostics(scope)
            RuntimeDiagnostics.record(Event("foundation_operator_command", operation, "read", Outcome.COMPLETED,
                "Foundation produced the requested diagnostic report."))
            println(result)
        }
        else -> error("Unknown maintenance command")
    }
}
