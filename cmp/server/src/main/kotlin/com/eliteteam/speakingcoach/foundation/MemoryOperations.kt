package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Scoped memory use cases shared by text and voice. Interpretation belongs to the model. */
internal class MemoryOperations {
    fun read(c: Connection, scope: ExecutionScope, args: JsonObject, now: Instant, candidates: Boolean = false): JsonObject {
        requireRole(scope, "text", "voice", "operator")
        args.only("query", "offset")
        val query = args.text("query").trim()
        val offset = args.number("offset")
        if (query.length > 200 || offset !in 0..10000 || (candidates && query.isBlank())) reject("invalid_query")
        // strpos treats user text literally (no wildcard/operator injection).
        val rows = if (candidates) c.documents("""SELECT to_jsonb(m) FROM memory_candidate m
            WHERE student_id=? AND status='pending' AND expires_at>? AND value IS NOT NULL
            AND (strpos(lower(fact_key),lower(?))>0 OR strpos(lower(value),lower(?))>0)
            ORDER BY id LIMIT 21 OFFSET ?""", scope.studentId, now, query, query, offset)
        else c.documents("""SELECT to_jsonb(m) || jsonb_build_object('source_content_expired',e.body IS NULL OR e.expires_at<=?,
            'occurred_at',e.occurred_at) FROM memory_fact m LEFT JOIN conversation_event e ON e.id=m.source_event_id
            WHERE m.student_id=? AND forgotten_at IS NULL AND value IS NOT NULL AND (valid_until IS NULL OR valid_until>?)
            AND (?='' OR strpos(lower(fact_key),lower(?))>0 OR strpos(lower(value),lower(?))>0)
            ORDER BY updated_at DESC,category,fact_key LIMIT 21 OFFSET ?""", now, scope.studentId, now, query, query, query, offset)
        val observations = if (candidates) emptyList() else c.documents("""SELECT to_jsonb(o) || jsonb_build_object(
            'review',to_jsonb(r),'occurred_at',e.occurred_at,'source_content_expired',e.body IS NULL OR e.expires_at<=?)
            FROM memory_observation o JOIN lesson l ON l.id=o.lesson_id AND l.transcript_revision=o.source_revision
            LEFT JOIN conversation_event e ON e.id=o.source_event_id
            LEFT JOIN memory_observation_review r ON r.student_id=o.student_id AND r.observation_key=o.observation_key
            WHERE o.student_id=? AND (?='' OR strpos(lower(o.observation_key),lower(?))>0 OR strpos(lower(o.content),lower(?))>0)
            ORDER BY e.occurred_at DESC,o.id LIMIT 21 OFFSET ?""", now, scope.studentId, query, query, query, offset)
        return json((if (candidates) "candidates" else "facts") to JsonArray(rows.take(20)),
            "observations" to JsonArray(observations.take(20)),
            "nextOffset" to if (rows.size > 20 || observations.size > 20) offset + 20 else null,
            "purpose" to if (candidates) "discussion_only_not_confirmed_facts" else "on_demand")
    }

    fun policy(c: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        args.only("sourceId", "evidence", "category", "key", "expectedVersion", "mentionPolicy", "validUntil")
        evidence(c, scope, args, now)
        val policy = args.text("mentionPolicy")
        if (policy !in setOf("natural", "context_only")) reject("invalid_policy")
        val until = args.optionalText("validUntil")?.let {
            try { Instant.parse(it) } catch (_: java.time.format.DateTimeParseException) { reject("invalid_expiry") }
        }
        val n = c.update("""UPDATE memory_fact SET mention_policy=?,valid_until=?,revision=revision+1
            WHERE student_id=? AND category=? AND fact_key=? AND revision=? AND forgotten_at IS NULL""",
            policy, until, scope.studentId, args.text("category"), args.text("key"), args.number("expectedVersion"))
        if (n != 1) reject("version_conflict")
        c.invalidatePreparation(scope.studentId, now)
        return json("updated" to true)
    }

    fun review(c: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        args.only("sourceId", "evidence", "key", "expectedVersion", "status", "explanation")
        val source = evidence(c, scope, args, now)
        val key = args.text("key")
        if (args.text("status") !in setOf("disputed", "resolved") || args.text("explanation").length !in 1..1000) reject("invalid_review")
        if (c.rows("SELECT count(*) FROM memory_observation WHERE student_id=? AND observation_key=?", scope.studentId, key) { it.getLong(1) }.single() == 0L) reject("not_owned")
        val old = c.document("SELECT to_jsonb(r) FROM memory_observation_review r WHERE student_id=? AND observation_key=?", scope.studentId, key)
        if ((old?.number("revision") ?: 0) != args.number("expectedVersion")) reject("version_conflict")
        c.update("""INSERT INTO memory_observation_review(student_id,observation_key,status,explanation,source_event_id,revision)
            VALUES (?,?,?,?,?,?) ON CONFLICT(student_id,observation_key) DO UPDATE SET status=EXCLUDED.status,
            explanation=EXCLUDED.explanation,source_event_id=EXCLUDED.source_event_id,revision=EXCLUDED.revision""",
            scope.studentId, key, args.text("status"), args.text("explanation"), source.text("id"), args.number("expectedVersion") + 1)
        c.invalidatePreparation(scope.studentId, now)
        return json("updated" to true)
    }

    fun reject(c: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        args.only("sourceId", "evidence", "candidateId", "expectedVersion")
        evidence(c, scope, args, now)
        return FoundationMaintenance().rejectCandidate(c, scope, json("candidateId" to args.text("candidateId"),
            "expectedVersion" to args.number("expectedVersion")))
    }

    fun forget(c: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        args.only("sourceId", "evidence", "category", "key", "expectedVersion", "targets")
        evidence(c, scope, args, now)
        val targets = args["targets"]?.jsonArray?.map { it.jsonObject } ?: listOf(json("category" to args.text("category"),
            "key" to args.text("key"), "expectedVersion" to args.number("expectedVersion")))
        if (targets.size !in 1..20 || targets.distinctBy { it.text("category") to it.text("key") }.size != targets.size) reject("invalid_targets")
        targets.forEachIndexed { index, target ->
            target.only("category", "key", "expectedVersion")
            // One transaction: if any target/version is invalid, no deletion is committed.
            FoundationMaintenance().forget(c, scope.copy(role = "operator", epoch = scope.epoch + index), target, now)
        }
        c.update("UPDATE call_session SET status='ended',ended_at=?,end_reason='technical_disconnect',lease_until=NULL WHERE student_id=? AND status='connected'", now, scope.studentId)
        c.update("UPDATE lesson SET status='scheduled',version=version+1 WHERE student_id=? AND status='in_progress'", scope.studentId)
        c.invalidatePreparation(scope.studentId, now)
        // Do not resume a model whose prompt still contains deleted data. The next input gets a fresh epoch.
        val response = json("conversationId" to c.conversation(scope.studentId, "telegram"),
            "reply" to "Сведения удалены из памяти. Продолжим с нового сообщения.", "booking" to null, "degraded" to false)
        if (scope.role == "text") c.update("UPDATE tutor_inbox SET status='ready',response=?::jsonb WHERE student_id=? AND request_id=?",
            response, scope.studentId, scope.requestId)
        return JsonObject(response + json("forgotten" to true, "contextRevoked" to true))
    }

    private fun evidence(c: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "text", "voice", "operator")
        val source = sourceMessage(c, scope, args.text("sourceId"), now)
        if (args.text("evidence").isBlank() || !source.text("body").contains(args.text("evidence"))) reject("invalid_evidence")
        return source
    }
}
