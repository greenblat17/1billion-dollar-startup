package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** References existing durable evidence; does not manufacture observations or retain transcript copies. */
internal object StudentLearningSources {
    fun validate(c: Connection, student: String, value: kotlinx.serialization.json.JsonElement?, now: Instant): JsonArray {
        val sources = StudentLearningModel.sources(value)
        val identities = mutableSetOf<Pair<String, String>>()
        return JsonArray(sources.map { element ->
            val source = StudentLearningModel.objectValue(element)
            source.only("type", "id", "revision")
            val type = StudentLearningModel.enum(source["type"], setOf("event", "observation"))
            val id = StudentLearningModel.requiredString(source["id"], 250)
            val revision = source["revision"] as? JsonPrimitive ?: reject("invalid_source_revision")
            if (revision.isString || (revision.longOrNull ?: 0) <= 0) reject("invalid_source_revision")
            if (!identities.add(type to id)) reject("duplicate_learning_source")
            val stored = resolve(c, student, type, id) ?: reject("learning_source_not_owned")
            if (stored.number("revision") != revision.longOrNull) reject("stale_learning_source")
            if (stored.optionalText("body") == null || Instant.parse(stored.text("expires_at")) <= now) reject("source_expired")
            if (stored.optionalText("current_revision")?.toLong() != null && stored.number("current_revision") != stored.number("revision")) {
                reject("stale_learning_source")
            }
            JsonObject(source + json("sourceEventId" to stored.text("event_id"), "lessonId" to stored.optionalText("lesson_id")))
        })
    }

    fun project(c: Connection, student: String, sources: JsonArray, now: Instant): JsonArray = JsonArray(sources.map { element ->
        val source = StudentLearningModel.objectValue(element)
        val stored = resolve(c, student, source.text("type"), source.text("id"))
        json("type" to source["type"], "id" to source["id"], "revision" to source["revision"],
            "lessonId" to (source.optionalText("lessonId") ?: stored?.optionalText("lesson_id")),
            "sourceEventId" to (source.optionalText("sourceEventId") ?: stored?.optionalText("event_id")),
            "sourceContentExpired" to (stored?.optionalText("body") == null || Instant.parse(stored.text("expires_at")) <= now),
            "sourceRevisionCurrent" to (stored != null && stored.number("revision") == source.number("revision") &&
                (stored.optionalText("current_revision") == null || stored.number("current_revision") == source.number("revision"))))
    })

    private fun resolve(c: Connection, student: String, type: String, id: String): JsonObject? = when (type) {
        "event" -> c.document("""SELECT jsonb_build_object('event_id',e.id,'revision',e.sequence,'body',e.body,'expires_at',e.expires_at,
            'lesson_id',le.lesson_id) FROM conversation_event e LEFT JOIN lesson_event le ON le.event_id=e.id
            WHERE e.student_id=? AND e.id=? AND e.role='user'""", student, id)
        "observation" -> c.document("""SELECT jsonb_build_object('event_id',o.source_event_id,'revision',o.source_revision,'current_revision',l.transcript_revision,
            'body',e.body,'expires_at',e.expires_at,'lesson_id',o.lesson_id)
            FROM memory_observation o JOIN lesson l ON l.id=o.lesson_id AND l.student_id=o.student_id
            LEFT JOIN conversation_event e ON e.id=o.source_event_id AND e.student_id=o.student_id
            WHERE o.student_id=? AND o.id=?""", student, id)
        else -> reject("invalid_learning_source_type")
    }
}
