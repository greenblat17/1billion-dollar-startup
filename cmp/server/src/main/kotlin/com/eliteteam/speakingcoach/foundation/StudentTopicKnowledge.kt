package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

internal class StudentTopicKnowledge {
    fun read(c: Connection, student: String, version: String, topic: String?, offset: Int, history: Boolean, now: Instant): JsonObject {
        if (offset !in 0..100000) reject("invalid_offset")
        validateCatalog(c, version, topic)
        if (history && topic == null) reject("topic_required")
        val rows = if (history) c.documents("""SELECT to_jsonb(k) FROM student_topic_knowledge k
            WHERE student_id=? AND catalog_version=? AND topic_id=? ORDER BY revision DESC LIMIT 51 OFFSET ?""", student, version, topic, offset)
        else c.documents("""SELECT jsonb_build_object('topic_id',t.id,'catalog_version',t.version,
            'revision',COALESCE(k.revision,0),'status',COALESCE(k.status,'Unknown'),'explanation',k.explanation,
            'sources',COALESCE(k.sources,'[]'::jsonb),'component',k.component,'assessed_at',k.assessed_at)
            FROM learning_catalog_topic t LEFT JOIN LATERAL
              (SELECT * FROM student_topic_knowledge WHERE student_id=? AND catalog_version=t.version AND topic_id=t.id
               ORDER BY revision DESC LIMIT 1) k ON TRUE
            WHERE t.version=? AND (?::text IS NULL OR t.id=?) ORDER BY t.id LIMIT 51 OFFSET ?""", student, version, topic, topic, offset)
        return json("schemaVersion" to 1, "catalogVersion" to version, "items" to JsonArray(rows.take(50).map { row ->
            JsonObject(row + json("sources" to StudentLearningSources.project(c, student,
                StudentLearningModel.sources(row["sources"]), now)))
        }), "nextOffset" to if (rows.size > 50) offset + 50 else null)
    }

    fun record(c: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "operator")
        args.only("catalogVersion", "topicId", "expectedRevision", "status", "explanation", "sources", "component")
        val version = StudentLearningModel.requiredString(args["catalogVersion"], 250)
        val topic = StudentLearningModel.requiredString(args["topicId"], 250)
        validateCatalog(c, version, topic)
        val revision = c.rows("SELECT COALESCE(max(revision),0) FROM student_topic_knowledge WHERE student_id=? AND catalog_version=? AND topic_id=?",
            scope.studentId, version, topic) { it.getLong(1) }.single()
        if (args.number("expectedRevision") != revision) reject("version_conflict")
        val status = StudentLearningModel.enum(args["status"], StudentLearningModel.topicStates)
        val explanation = StudentLearningModel.requiredString(args["explanation"], 2000)
        val component = StudentLearningModel.requiredString(args["component"], 100)
        val sources = StudentLearningSources.validate(c, scope.studentId, args["sources"], now)
        if (status in setOf("Known", "Not Known") && sources.isEmpty()) reject("learning_source_required")
        c.update("""INSERT INTO student_topic_knowledge(student_id,catalog_version,topic_id,revision,status,explanation,sources,component,assessed_at)
            VALUES (?,?,?,?,?,?,?::jsonb,?,?)""", scope.studentId, version, topic, revision + 1, status, explanation, sources, component, now)
        c.update("UPDATE lesson_plan SET status='stale' WHERE student_id=? AND status='ready'", scope.studentId)
        c.invalidatePreparation(scope.studentId, now)
        return json("revision" to revision + 1, "status" to status, "catalogVersion" to version, "topicId" to topic)
    }

    private fun validateCatalog(c: Connection, version: String, topic: String?) {
        if (c.document("SELECT to_jsonb(r) FROM learning_catalog_release r WHERE version=? AND published_at IS NOT NULL", version) == null) {
            reject("catalog_not_published")
        }
        if (topic != null && c.document("SELECT content FROM learning_catalog_topic WHERE version=? AND id=?", version, topic) == null) {
            reject("catalog_topic_not_found")
        }
    }
}
