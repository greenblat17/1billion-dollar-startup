package com.eliteteam.speakingcoach.foundation

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Offline operator command. Never registered in the HTTP server or AI tools. */
fun main(args: Array<String>) = runBlocking {
    require(args.size == 1) { "Usage: foundationImport <frozen-tutor-manifest.json>" }
    val manifest = Files.readString(Path.of(args.single()))
    val database = requireNotNull(System.getenv("DATABASE_URL")) { "DATABASE_URL required" }
    FoundationService(FoundationDatabase(database)).use { service ->
        val count = TutorCutover(service).apply(manifest)
        println("Tutor cutover validated: $count states")
    }
}

internal class TutorCutover(private val service: FoundationService) {
    suspend fun apply(raw: String): Int {
        val manifest = foundationJson.parseToJsonElement(raw).jsonObject
        manifest.only("schemaVersion", "sourceFrozenAt", "entries")
        if (manifest.number("schemaVersion") != 1L) reject("unsupported_manifest")
        val frozen = Instant.parse(manifest.text("sourceFrozenAt"))
        if (frozen > service.database.clock.instant()) reject("invalid_freeze_time")
        val entries = manifest["entries"]!!.jsonArray.map { it.jsonObject }
        val subjects = entries.map { it.text("subject") }
        if (subjects.toSet().size != subjects.size) reject("duplicate_identity")
        entries.forEach { validate(it) }
        val hash = digest(raw)
        val previous = service.database.transaction(readOnly = true) { c, _ ->
            c.document("SELECT to_jsonb(c) FROM foundation_cutover c WHERE singleton=TRUE")
        }
        if (previous != null) {
            if (previous.text("manifest_digest") != hash) reject("cutover_already_completed")
            return entries.size
        }
        entries.forEach { entry ->
            TutorStateImport(service).import(entry.text("subject"), foundationJson.parseToJsonElement(entry.text("stateJson")).jsonObject)
        }
        service.database.transaction { c, now ->
            val imports = c.rows("SELECT source_id FROM foundation_import") { it.getString(1) }.toSet()
            if (imports != entries.map { it.text("sourceKey") }.toSet()) reject("import_manifest_mismatch")
            if (c.rows("SELECT count(*) FROM student") { it.getLong(1) }.single() != entries.size.toLong()) reject("import_target_not_empty")
            // A marker is issued only after every source has a committed import receipt.
            c.update("INSERT INTO foundation_cutover(singleton,manifest_digest,completed_at,source_frozen_at) VALUES (TRUE,?,?,?) ON CONFLICT DO NOTHING", hash, now, frozen)
            if (c.document("SELECT to_jsonb(c) FROM foundation_cutover c WHERE singleton=TRUE")!!.text("manifest_digest") != hash) reject("cutover_already_completed")
        }
        return entries.size
    }

    private fun validate(entry: JsonObject) {
        entry.only("sourceKey", "subject", "stateJson", "sha256")
        if (digest(entry.text("stateJson")) != entry.text("sha256")) reject("import_checksum_mismatch")
        val state = foundationJson.parseToJsonElement(entry.text("stateJson")).jsonObject
        if (entry.text("sourceKey") != "tutor:v1:${state.text("learnerId")}") reject("import_identity_conflict")
        val subject = entry.text("subject")
        if (subject.toLongOrNull()?.let { it > 0 } != true || state.text("learnerId") !in setOf("tg-$subject", "tg-ChatId(chatId=$subject)")) reject("import_identity_conflict")
    }
}
