package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

internal class TutorContextOperations(private val memory: StructuredMemory) {
    fun update(connection: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "text")
        args.only("sourceId", "facts", "trialPreference", "preferenceEvidence", "summary")
        val source = sourceMessage(connection, scope, args.text("sourceId"), now)
        val facts = args["facts"]?.jsonArray ?: reject("invalid_facts")
        if (facts.size > 20) reject("invalid_facts")
        val preference = args.optionalText("trialPreference")
        if (preference != null && (preference !in setOf("unknown", "interested", "declined") ||
                args.optionalText("preferenceEvidence").isNullOrBlank() || !source.text("body").contains(args.text("preferenceEvidence")))
        ) reject("invalid_preference")
        facts.forEach { item ->
            val fact = item.jsonObject
            fact.only("memory", "key", "value", "evidence", "kind")
            val key = if (fact.text("key") == "goals") "goal" else fact.text("key")
            val version = if (key == "goal") connection.goalRevision(scope.studentId) else connection.rows(
                "SELECT revision FROM memory_fact WHERE student_id=? AND category=? AND fact_key=?", scope.studentId, fact.text("memory"), key,
            ) { it.getLong(1) }.singleOrNull() ?: 0
            memory.record(connection, scope, json("sourceId" to source.text("id"), "category" to fact.text("memory"), "key" to key,
                "value" to fact["value"], "kind" to fact.text("kind"), "evidence" to fact.text("evidence"), "expectedVersion" to version), now)
        }
        if (preference != null) connection.update("UPDATE student SET profile=profile || ?::jsonb,version=version+1 WHERE id=?",
            json("trialPreference" to preference), scope.studentId)
        val summary = args.optionalText("summary")
        if (summary != null) {
            if (summary.length > 3000) reject("invalid_summary")
            val deadline = connection.rows("SELECT min(expires_at) FROM conversation_event WHERE student_id=? AND body IS NOT NULL AND expires_at>?", scope.studentId, now) {
                it.getTimestamp(1)?.toInstant()
            }.single() ?: Instant.parse(source.text("expires_at"))
            connection.update("INSERT INTO tutor_summary(student_id,body,expires_at) VALUES (?,?,?) ON CONFLICT(student_id) DO UPDATE SET body=EXCLUDED.body,expires_at=EXCLUDED.expires_at",
                scope.studentId, summary, deadline)
        }
        return json("ok" to true)
    }
}
