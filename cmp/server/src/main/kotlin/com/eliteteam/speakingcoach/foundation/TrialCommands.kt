package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonObject

internal class TrialCommands {
    fun execute(c: Connection, scope: ExecutionScope, action: String, args: JsonObject, now: Instant): JsonObject {
        val session = c.activeTrial(scope, args, now)
        val lesson = c.lesson(scope, args.text("lessonId"))
        return when (action) {
            "voice.heartbeat" -> {
                c.update("UPDATE call_session SET lease_until=? WHERE id=?", now.plusSeconds(45), session.text("id"))
                c.update("UPDATE execution_scope SET expires_at=? WHERE token_digest=?", now.plusSeconds(120), scope.tokenDigest)
                json("alive" to true)
            }
            "voice.append" -> LessonManagement().append(c, scope, args, now)
            "voice.audio" -> {
                val direction = args.text("direction")
                val seq = args.number("sequence")
                if (direction !in setOf("user", "assistant") || seq < 0) reject("invalid_audio")
                val key = "trial/${scope.studentId}/${session.text("id")}/$direction/$seq.pcm"
                c.update("INSERT INTO trial_audio(object_key,student_id,lesson_id,call_session_id,data_epoch,direction,sequence,created_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                    key, scope.studentId, scope.lessonId, session.text("id"), scope.epoch, direction, seq, now, now.plusSeconds(HISTORY_SECONDS))
                if (args["metadata"] is JsonObject) c.update("UPDATE trial_audio SET metadata=?::jsonb WHERE object_key=? AND status='reserved'", args.obj("metadata"), key)
                json("objectKey" to key)
            }
            "voice.audio_stored" -> {
                val n = c.update("UPDATE trial_audio SET status='stored' WHERE object_key=? AND student_id=? AND call_session_id=? AND data_epoch=? AND status IN ('reserved','stored') AND expires_at>?",
                    args.text("objectKey"), scope.studentId, session.text("id"), scope.epoch, now)
                if (n == 0) reject("audio_expired")
                json("stored" to true)
            }
            "voice.playback" -> {
                c.update("UPDATE trial_audio SET metadata=metadata || ?::jsonb WHERE call_session_id=? AND direction='assistant' AND metadata->>'utteranceId'=? AND status IN ('reserved','stored')",
                    json("playbackComplete" to (args.optionalText("complete") == "true"), "playedMs" to args["playedMs"], "playbackReportedAt" to now.toString()), session.text("id"), args.text("utteranceId"))
                json("recorded" to true)
            }
            "voice.end" -> {
                val reason = args.text("reason")
                if (reason !in setOf("natural_goodbye", "end_button", "technical_disconnect")) reject("invalid_reason")
                c.update("UPDATE call_session SET status='ended',ended_at=?,end_reason=?,lease_until=NULL WHERE id=?", now, reason, session.text("id"))
                if (reason == "natural_goodbye") {
                    c.update("UPDATE lesson SET status='completed',outcome_reason=?,version=version+1 WHERE id=?", reason, scope.lessonId)
                    c.enqueueEvaluation(scope.studentId, lesson.text("id"), now)
                }
                c.trialNotice(scope.studentId, lesson.text("id"), scope.epoch,
                    if (reason == "natural_goodbye") "followup" else reason, now.plusSeconds(if (reason == "natural_goodbye") 3 else 30))
                json("ended" to true, "completed" to (reason == "natural_goodbye"))
            }
            else -> reject("unknown_action")
        }
    }
}

internal fun Connection.trialNotice(student: String, lesson: String, epoch: Long, kind: String, due: Instant) {
    update("INSERT INTO trial_notice(id,student_id,lesson_id,kind,due_at,data_epoch) VALUES (?,?,?,?,?,?) ON CONFLICT(lesson_id,kind) DO UPDATE SET status='pending',due_at=EXCLUDED.due_at WHERE trial_notice.status='cancelled'",
        newId(), student, lesson, kind, due, epoch)
}
