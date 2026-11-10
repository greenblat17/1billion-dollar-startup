package com.eliteteam.speakingcoach.foundation

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
    require(args.size in 2..3) { "Usage: foundationMaintenance export-deletions|restore-deletions <file> OR diagnostics <student-id> OR retry-job <student-id> <job-id>" }
    FoundationService(FoundationDatabase(requireNotNull(System.getenv("DATABASE_URL")))).use { service ->
        when (args[0]) {
            "export-deletions" -> {
                val records = service.database.transaction(readOnly = true) { c, _ -> c.documents("SELECT to_jsonb(d) FROM foundation_deletion d ORDER BY deleted_at,id") }
                val path = Path.of(args[1])
                Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                Files.writeString(path, JsonArray(records).toString(), StandardOpenOption.WRITE)
                println("Deletion records exported: ${records.size}")
            }
            "restore-deletions" -> {
                val records = foundationJson.parseToJsonElement(Files.readString(Path.of(args[1]))).jsonArray.map { it.jsonObject }
                service.database.transaction { c, _ ->
                    records.forEach { record ->
                        record.only("id", "student_id", "kind", "category", "fact_key", "deleted_at", "resulting_epoch")
                        val existing = c.document("SELECT to_jsonb(d) FROM foundation_deletion d WHERE id=?", record.text("id"))
                        if (existing != null && canonical(existing) != canonical(record)) reject("deletion_ledger_conflict")
                        if (existing == null) c.update("INSERT INTO foundation_deletion SELECT * FROM jsonb_populate_record(NULL::foundation_deletion,?::jsonb)", record)
                    }
                    // Replay before serving, including prior ledger records retained in the backup.
                    c.documents("SELECT to_jsonb(s) FROM student s WHERE deleted_at IS NULL FOR UPDATE").forEach { student ->
                        FoundationMaintenance().replayDeletions(c, ExecutionScope(student.text("id"), "operator", student.number("data_epoch")), service.database.clock.instant())
                    }
                }
                println("Deletion ledger restored and replayed")
            }
            "retry-job" -> {
                require(args.size == 3) { "retry-job requires student-id and job-id" }
                val scope = service.identity.issue(args[1], "operator")
                println(service.command(scope, json("version" to 1, "operationId" to newId(), "action" to "job.retry", "args" to json("jobId" to args[2]))))
            }
            "diagnostics" -> {
                val scope = service.identity.issue(args[1], "operator")
                println(service.diagnostics(scope))
            }
            else -> error("Unknown maintenance command")
        }
    }
}
