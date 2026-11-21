package com.eliteteam.speakingcoach.foundation

import com.eliteteam.speakingcoach.RuntimeDiagnostics.Event
import com.eliteteam.speakingcoach.RuntimeDiagnostics.Outcome
import java.sql.Connection
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Global, non-personal data. Writes are offline operator actions, never tutor tools. */
internal class LearningCatalog(private val database: FoundationDatabase) {
    suspend fun import(raw: String): String {
        val manifest = LearningCatalogModel.parse(raw)
        val document = LearningCatalogModel.document(manifest)
        val hash = digest(canonical(document))
        return database.transaction { c, now ->
            // Serialize all releases: identity checks and publication must see a consistent catalogue.
            c.update("LOCK TABLE learning_catalog_release IN EXCLUSIVE MODE")
            val previous = c.document("SELECT to_jsonb(r) FROM learning_catalog_release r WHERE version=?", manifest.version)
            if (previous != null) {
                if (previous.text("content_digest") != hash) reject("catalog_version_conflict")
            } else {
                checkIdentities(c, manifest)
                c.update("INSERT INTO learning_catalog_release(version,content_digest,manifest,imported_at) VALUES (?,?,?::jsonb,?)",
                    manifest.version, hash, document, now)
                document.getValue("skills").jsonArray.forEach { element ->
                    val skill = element.jsonObject
                    c.update("INSERT INTO learning_catalog_skill(version,id,content) VALUES (?,?,?::jsonb)", manifest.version, skill.text("id"), skill)
                }
                document.getValue("problems").jsonArray.forEach { element ->
                    val problem = element.jsonObject
                    c.update("INSERT INTO learning_catalog_problem(version,id,skill_id,content) VALUES (?,?,?,?::jsonb)",
                        manifest.version, problem.text("id"), problem.text("skillId"), problem)
                }
                document.getValue("topics").jsonArray.forEach { element ->
                    val topic = element.jsonObject
                    c.update("INSERT INTO learning_catalog_topic(version,id,skill_id,cefr,content) VALUES (?,?,?,?,?::jsonb)",
                        manifest.version, topic.text("id"), topic.text("skillId"), topic.text("cefr"), topic)
                }

            }
            val event = if (previous == null) Event("catalog_import", "catalog.import", "persist", Outcome.COMPLETED,
                "Foundation saved a draft catalog release.")
            else Event("catalog_import", "catalog.import", "persist", Outcome.REPLAYED,
                "Foundation kept the previously imported catalog release.", "catalog_already_imported")
            FoundationDatabase.recordAfterCommit(event, mapOf("catalog_version" to manifest.version))
            manifest.version
        }
    }

    /** Publication records a human content acceptance; deployment alone never activates a draft. */
    suspend fun publish(version: String, reviewedBy: String, reviewReference: String) {
        require(reviewedBy.isNotBlank() && reviewReference.isNotBlank()) { "catalog_review_required" }
        database.transaction { c, now ->
            c.update("LOCK TABLE learning_catalog_release IN EXCLUSIVE MODE")
            val release = c.document("SELECT to_jsonb(r) FROM learning_catalog_release r WHERE version=?", version)
                ?: reject("catalog_not_found")
            LearningCatalogModel.parse(release.obj("manifest").toString())
            val firstPublication = release.optionalText("published_at") == null
            if (firstPublication) {
                c.update("UPDATE learning_catalog_release SET published_at=?,reviewed_by=?,review_reference=? WHERE version=?",
                    now, reviewedBy, reviewReference, version)
            }
            val changed = c.update("""INSERT INTO learning_catalog_current(singleton,version) VALUES (TRUE,?)
                ON CONFLICT(singleton) DO UPDATE SET version=EXCLUDED.version
                WHERE learning_catalog_current.version<>EXCLUDED.version""", version)
            val event = if (firstPublication || changed > 0) Event("catalog_publish", "catalog.publish", "persist", Outcome.COMPLETED,
                "Foundation activated the reviewed catalog release.")
            else Event("catalog_publish", "catalog.publish", "persist", Outcome.REPLAYED,
                "Foundation kept the current catalog release.", "catalog_already_current")
            FoundationDatabase.recordAfterCommit(event, mapOf("catalog_version" to version))
        }
    }

    suspend fun read(version: String?, skill: String?, cefr: String?, topicId: String?): JsonObject =
        database.transaction(readOnly = true) { c, _ ->
            if (skill != null && skill !in LearningCatalogModel.mvpSkills + setOf("reading", "writing")) reject("invalid_catalog_skill")
            if (cefr != null && cefr !in LearningCatalogModel.levels) reject("invalid_catalog_cefr")
            val selected = version ?: c.document("SELECT to_jsonb(a) FROM learning_catalog_current a WHERE singleton=TRUE")?.text("version")
                ?: reject("catalog_not_published")
            val release = c.document("SELECT to_jsonb(r) FROM learning_catalog_release r WHERE version=? AND published_at IS NOT NULL", selected)
                ?: reject("catalog_not_published")
            val manifest = LearningCatalogModel.parse(release.obj("manifest").toString())
            if (topicId != null && manifest.topics.none { it.id == topicId }) reject("catalog_topic_not_found")
            val topics = manifest.topics.filter { (skill == null || it.skillId == skill) &&
                (cefr == null || it.cefr == cefr) && (topicId == null || it.id == topicId) }
            // Display sorting only; no pedagogical dependencies or completion requirements.
            val ids = topics.sortedWith(compareBy({ LearningCatalogModel.levels.indexOf(it.cefr) },
                { it.skillId }, { it.id })).map { it.id }
            val stored = c.documents("SELECT content FROM learning_catalog_topic WHERE version=?", selected).associateBy { it.text("id") }
            json("schemaVersion" to 2, "version" to selected, "digest" to release.text("content_digest"),
                "publishedAt" to release.text("published_at"),
                "skills" to JsonArray(c.documents("SELECT content FROM learning_catalog_skill WHERE version=? ORDER BY id", selected)),
                "problems" to JsonArray(c.documents("SELECT content FROM learning_catalog_problem WHERE version=? ORDER BY id", selected)),
                "sources" to release.obj("manifest")["sources"],
                "topics" to JsonArray(ids.map { stored.getValue(it) }))
        }

    private fun checkIdentities(c: Connection, manifest: CatalogManifest) {
        val skills = manifest.skills.associateBy { it.id }
        c.documents("""SELECT DISTINCT s.content FROM learning_catalog_skill s
            JOIN learning_catalog_release r ON r.version=s.version WHERE r.published_at IS NOT NULL""").forEach { previous ->
            val skill = skills[previous.text("id")] ?: reject("catalog_identity_conflict")
            if (previous.text("description") != skill.description) reject("catalog_identity_conflict")
        }
        val problems = manifest.problems.associateBy { it.id }
        c.documents("""SELECT DISTINCT p.content FROM learning_catalog_problem p
            JOIN learning_catalog_release r ON r.version=p.version WHERE r.published_at IS NOT NULL""").forEach { previous ->
            val problem = problems[previous.text("id")] ?: reject("catalog_identity_conflict")
            if (previous.text("skillId") != problem.skillId || previous.text("description") != problem.description) {
                reject("catalog_identity_conflict")
            }
        }
        val topics = manifest.topics.associateBy { it.id }
        c.documents("""SELECT DISTINCT t.content FROM learning_catalog_topic t
            JOIN learning_catalog_release r ON r.version=t.version WHERE r.published_at IS NOT NULL""").forEach { previous ->
            val topic = topics[previous.text("id")] ?: reject("catalog_identity_conflict")
            // Labels and review metadata may change; learning outcome and boundaries require a new ID.
            if (previous.text("skillId") != topic.skillId || previous.text("cefr") != topic.cefr ||
                previous.text("objective") != topic.objective || previous.text("scope") != topic.scope ||
                previous.text("coverageArea") != topic.coverageArea) reject("catalog_identity_conflict")
        }
    }
}
