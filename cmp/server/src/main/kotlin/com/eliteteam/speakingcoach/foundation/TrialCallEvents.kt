package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** Raw transport facts. No Topic, outcome, correctness, or attempt classification. */
internal class TrialCallEvents {
    fun append(c: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        args.only("lessonId", "callSessionId", "sourceId", "role", "body", "occurredAt", "metadata", "revision")
        val session = c.activeTrial(scope, args, now)
        val metadata = args["metadata"] as? JsonObject ?: json()
        metadata.only("providerItemId", "finality", "audioStartMs", "audioEndMs", "playbackComplete", "playedMs", "trialClosingOffer")
        if (metadata.optionalText("finality") !in setOf(null, "final", "partial")) reject("invalid_finality")
        for (key in listOf("audioStartMs", "audioEndMs", "playedMs")) {
            if (metadata.containsKey(key)) {
                val value = metadata.optionalText(key)?.toDoubleOrNull() ?: reject("invalid_audio_span")
                if (!value.isFinite() || value < 0 || value > 3_600_000) reject("invalid_audio_span")
            }
        }
        if (metadata.containsKey("audioStartMs") && metadata.containsKey("audioEndMs") &&
            metadata.text("audioEndMs").toDouble() < metadata.text("audioStartMs").toDouble()) reject("invalid_audio_span")
        if ((metadata.optionalText("providerItemId")?.length ?: 0) > 250) reject("invalid_provider_item")
        if (metadata.containsKey("playbackComplete") && metadata.optionalText("playbackComplete") !in setOf("true", "false")) reject("invalid_playback")
        val revision = args.optionalText("revision")?.let { it.toLongOrNull() ?: reject("invalid_revision") } ?: 1L
        val sourceKey = args.text("sourceId")
        val previous = c.document("SELECT to_jsonb(e) FROM trial_call_event e WHERE call_session_id=? AND source_key=?", session.text("id"), sourceKey)
        val fingerprint = digest(canonical(json("role" to args.text("role"), "body" to args.text("body"), "metadata" to metadata)))
        if (previous != null && revision == previous.number("revision")) {
            if (previous.text("input_digest") != fingerprint) reject("operation_conflict")
            return receipt(c, previous)
        }
        if (revision != (previous?.number("revision") ?: 0L) + 1) reject("version_conflict")
        val event: JsonObject
        if (previous == null) {
            event = LessonManagement().append(c, scope, JsonObject(args - "metadata" - "revision"), now)
        } else {
            val old = c.document("SELECT to_jsonb(e) FROM conversation_event e WHERE student_id=? AND id=? AND body IS NOT NULL AND expires_at>?",
                scope.studentId, previous.text("source_event_id"), now) ?: reject("source_expired")
            if (old.text("role") != args.text("role") || args.text("body").length !in 1..12000) reject("invalid_event")
            val seq = c.rows("UPDATE tutor_conversation SET next_sequence=next_sequence+1 WHERE id=? RETURNING next_sequence-1", old.text("conversation_id")) { it.getLong(1) }.single()
            c.update("UPDATE conversation_event SET body=?,sequence=?,content_digest=? WHERE id=?", args.text("body"), seq,
                digest(canonical(json("role" to old.text("role"), "body" to args.text("body"), "occurredAt" to old.text("occurred_at")))), old.text("id"))
            c.update("UPDATE lesson_event SET sequence=? WHERE event_id=?", seq, old.text("id"))
            c.update("UPDATE lesson SET transcript_revision=transcript_revision+1 WHERE id=?", scope.lessonId)
            event = json("eventId" to old.text("id"), "sequence" to seq)
        }
        val enriched = JsonObject(metadata + json("role" to args.text("role"), "finality" to (metadata.optionalText("finality") ?: "final"),
            "turnId" to "${session.text("id")}:$sourceKey", "asrConfidence" to JsonNull,
            "audioOffsetBasis" to if (metadata.containsKey("audioStartMs") || metadata.containsKey("audioEndMs")) "provider_input_buffer" else null,
            "uncertaintySignals" to JsonArray(emptyList())))
        val id = previous?.text("id") ?: newId()
        c.update("""INSERT INTO trial_call_event(id,student_id,lesson_id,call_session_id,source_key,source_event_id,revision,kind,occurred_at,metadata,input_digest,expires_at)
            VALUES (?,?,?,?,?,?,?,'transcript',?,?::jsonb,?,?) ON CONFLICT(id) DO UPDATE SET revision=EXCLUDED.revision,metadata=EXCLUDED.metadata,input_digest=EXCLUDED.input_digest""",
            id, scope.studentId, scope.lessonId, session.text("id"), sourceKey, event.text("eventId"), revision,
            Instant.parse(args.text("occurredAt")), enriched, fingerprint, now.plusSeconds(HISTORY_SECONDS))
        if (metadata.optionalText("trialClosingOffer") == "true" && args.text("role") == "assistant" &&
            metadata.optionalText("finality") == "final" && metadata.optionalText("playbackComplete") == "true") {
            c.update("""UPDATE lesson SET trial_offer_sequence=?
                WHERE id=? AND trial_version='v5.5' AND duration_minutes=? AND trial_phase='wrapup' AND trial_offer_sequence IS NULL""",
                event.number("sequence"), scope.lessonId, TRIAL_SLOT_MINUTES)
        }
        return JsonObject(event + json("rawEventId" to id, "revision" to revision))
    }

    private fun receipt(c: Connection, row: JsonObject): JsonObject {
        val source = c.document("SELECT to_jsonb(e) FROM conversation_event e WHERE id=?", row.text("source_event_id")) ?: reject("source_expired")
        return json("eventId" to source.text("id"), "sequence" to source.number("sequence"),
            "rawEventId" to row.text("id"), "revision" to row.number("revision"), "duplicate" to true)
    }

    fun lifecycle(c: Connection, scope: ExecutionScope, session: JsonObject, kind: String, metadata: JsonObject, now: Instant) {
        val key = "lifecycle:$kind:${metadata.optionalText("phase") ?: ""}"
        c.update("""INSERT INTO trial_call_event(id,student_id,lesson_id,call_session_id,source_key,revision,kind,occurred_at,metadata,input_digest,expires_at)
            VALUES (?,?,?,?,?,1,?,?,?::jsonb,?,?) ON CONFLICT(call_session_id,source_key) DO NOTHING""",
            newId(), scope.studentId, session.text("lesson_id"), session.text("id"), key, kind, now, metadata,
            digest(canonical(metadata)), now.plusSeconds(HISTORY_SECONDS))
    }

    fun read(c: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "operator", "learning", "voice")
        args.only("lessonId", "offset")
        val lesson = c.lesson(scope, args.text("lessonId"))
        requireV55(lesson)
        val offset = args.optionalText("offset")?.toIntOrNull() ?: 0
        if (offset < 0) reject("invalid_offset")
        val rows = c.documents("""SELECT to_jsonb(r) || jsonb_build_object('body',CASE WHEN e.expires_at>? THEN e.body ELSE NULL END,
            'sourceSequence',e.sequence,'sourceContentAvailable',e.body IS NOT NULL AND e.expires_at>?)
            FROM trial_call_event r LEFT JOIN conversation_event e ON e.id=r.source_event_id
            WHERE r.student_id=? AND r.lesson_id=? AND r.expires_at>? ORDER BY r.occurred_at,r.id LIMIT 101 OFFSET ?""",
            now, now, scope.studentId, lesson.text("id"), now, offset)
        val observationStatus = c.document("""SELECT to_jsonb(j) FROM foundation_job j WHERE j.student_id=? AND j.target_id=?
            AND j.kind='trial.observe' AND j.input_refs->>'transcriptRevision'=? ORDER BY j.created_at DESC LIMIT 1""",
            scope.studentId, lesson.text("id"), lesson.number("transcript_revision").toString())?.text("status") ?: "not_started"
        val assessmentStatus = c.document("""SELECT to_jsonb(j) FROM foundation_job j WHERE j.student_id=? AND j.target_id=?
            AND j.kind='trial.assess' AND j.input_refs->>'transcriptRevision'=? ORDER BY j.created_at DESC LIMIT 1""",
            scope.studentId, lesson.text("id"), lesson.number("transcript_revision").toString())?.text("status") ?: "not_started"
        return json("schemaVersion" to 1, "lesson" to lesson, "execution" to TrialExecution().state(c, lesson, now),
            "events" to JsonArray(rows.take(100).map { row -> json(
                "eventId" to row.text("id"), "sourceEventId" to row["source_event_id"], "sourceSequence" to row["sourceSequence"],
                "lessonId" to row.text("lesson_id"), "callSessionId" to row.text("call_session_id"),
                "revision" to row.number("revision"), "kind" to row.text("kind"), "occurredAt" to row.text("occurred_at"),
                "body" to row["body"], "sourceContentAvailable" to row["sourceContentAvailable"], "metadata" to row.obj("metadata")) }), "nextOffset" to if (rows.size > 100) offset + 100 else null,
            "assessmentStatus" to assessmentStatus, "observationStatus" to observationStatus)
    }

    fun audio(c: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "operator", "learning", "voice")
        args.only("lessonId", "cursor")
        val lesson = c.lesson(scope, args.text("lessonId"))
        val cursor = args.optionalText("cursor") ?: ""
        if (cursor.length > 512) reject("invalid_cursor")
        val rows = c.documents("SELECT to_jsonb(a) FROM trial_audio a WHERE student_id=? AND lesson_id=? AND data_epoch=? AND status='stored' AND expires_at>? AND object_key>? ORDER BY object_key LIMIT 201",
            scope.studentId, lesson.text("id"), scope.epoch, now, cursor)
        return json("objects" to JsonArray(rows.take(200)), "nextCursor" to if (rows.size > 200) rows[199].text("object_key") else null)
    }
}
