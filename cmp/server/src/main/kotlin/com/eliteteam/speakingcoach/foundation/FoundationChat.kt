package com.eliteteam.speakingcoach.foundation

import com.eliteteam.speakingcoach.ai.TutorDelivery
import com.eliteteam.speakingcoach.ai.TutorRequest
import com.eliteteam.speakingcoach.ai.TutorResponse
import java.time.Instant

/** Trusted Telegram adapter. Model requests cannot choose the student or issue capabilities. */
internal class FoundationChat(private val service: FoundationService) {
    suspend fun beginPayment(session: String, request: String): kotlinx.serialization.json.JsonObject? {
        val student = student(session)
        return service.database.transaction { c, now ->
            c.lockStudent(student)
            val p = c.document("""SELECT to_jsonb(p) FROM billing_checkout p JOIN foundation_operation o ON o.result->>'id'=p.id
                WHERE p.student_id=? AND o.student_id=p.student_id AND o.request_id=? AND o.action='subscription.checkout'
                AND p.status='pending' AND p.expires_at>? AND p.delivery_status='pending'
                AND p.data_epoch=(SELECT data_epoch FROM student WHERE id=p.student_id AND deleted_at IS NULL) LIMIT 1""", student, request, now)
                ?: return@transaction null
            c.update("UPDATE billing_checkout SET delivery_status='unknown' WHERE id=?", p.text("id"))
            service.billing.projection(p)
        }
    }

    suspend fun paymentDelivered(session: String, checkout: String) {
        val student = student(session)
        service.database.transaction { c, _ ->
            c.update("UPDATE billing_checkout SET delivery_status='sent' WHERE id=? AND student_id=?", checkout, student)
        }
    }

    suspend fun completeIntake(chatId: Long, messageId: Long) { service.telegramIntake.complete(chatId, messageId) }

    suspend fun beginDelivery(delivery: TutorDelivery): Boolean {
        val student = student(delivery.sessionId)
        return service.database.transaction { connection, _ ->
            connection.lockStudent(student)
            if (connection.document("SELECT to_jsonb(i) FROM tutor_inbox i WHERE student_id=? AND request_id=? AND status='ready' AND response IS NOT NULL",
                    student, delivery.requestId) == null) reject("invalid_state")
            connection.update("""INSERT INTO tutor_delivery(student_id,request_id,kind,outcome,lesson_id,lesson_version)
                VALUES (?,?,?,'unknown',?,?) ON CONFLICT(student_id,request_id,kind) DO NOTHING""",
                student, delivery.requestId, if (delivery.bookingId == null) "reply" else "card", delivery.bookingId, delivery.version) == 1
        }
    }
    suspend fun accept(request: TutorRequest, start: Boolean): String {
        val student = student(request.sessionId)
        service.history.accept(student, request.requestId, request.text,
            Instant.ofEpochMilli((request.receivedAt * 1000).toLong()), start)
        return service.identity.issue(student, "text", request.requestId)
    }

    suspend fun delivered(delivery: TutorDelivery) {
        val student = student(delivery.sessionId)
        service.database.transaction { connection, _ ->
            connection.lockStudent(student)
            val input = connection.document("SELECT to_jsonb(i) FROM tutor_inbox i WHERE student_id=? AND request_id=? AND status='ready'", student, delivery.requestId)
                ?: reject("not_owned")
            if (input["response"] == null || input["response"] == kotlinx.serialization.json.JsonNull) reject("source_expired")
            val kind = if (delivery.bookingId == null) "reply" else "card"
            val message = if (kind == "reply") delivery.replyMessageId else delivery.cardMessageId
            if (message == null || message <= 0) reject("invalid_delivery")
            if (delivery.bookingId != null) {
                val booking = input.obj("response").obj("booking")
                if (booking.text("id") != delivery.bookingId || booking.number("version") != delivery.version?.toLong()) reject("version_conflict")
            }
            connection.update("""INSERT INTO tutor_delivery(student_id,request_id,kind,outcome,message_id,lesson_id,lesson_version)
                VALUES (?,?,?,'sent',?,?,?) ON CONFLICT(student_id,request_id,kind)
                DO UPDATE SET outcome='sent',message_id=EXCLUDED.message_id,lesson_id=EXCLUDED.lesson_id,lesson_version=EXCLUDED.lesson_version""",
                student, delivery.requestId, kind, message, delivery.bookingId, delivery.version)
            if (delivery.bookingId != null) connection.update("""INSERT INTO lesson_telegram_card(student_id,lesson_id,message_id,lesson_version)
                VALUES (?,?,?,?) ON CONFLICT(lesson_id) DO UPDATE SET message_id=EXCLUDED.message_id,lesson_version=EXCLUDED.lesson_version
                WHERE lesson_telegram_card.lesson_version<=EXCLUDED.lesson_version""", student, delivery.bookingId, message, delivery.version)
        }
    }

    suspend fun withDelivery(request: TutorRequest, response: TutorResponse): TutorResponse {
        val student = student(request.sessionId)
        return service.database.transaction(readOnly = true) { connection, _ ->
            val sent = connection.documents("SELECT to_jsonb(d) FROM tutor_delivery d WHERE student_id=? AND request_id=? AND outcome='sent'", student, request.requestId)
            val previousCard = response.booking?.let { booking -> connection.document("""SELECT to_jsonb(d) FROM lesson_telegram_card d
                WHERE student_id=? AND lesson_id=? AND lesson_version<=?""", student, booking.id, booking.version) }
            response.copy(replyMessageId = sent.find { it.text("kind") == "reply" }?.number("message_id"),
                cardMessageId = previousCard?.number("message_id"))
        }
    }

    private suspend fun student(session: String): String {
        val subject = session.removePrefix("tg-")
        if (!session.startsWith("tg-") || subject.toLongOrNull()?.let { it > 0 } != true) reject("invalid_identity")
        return service.identity.resolve("telegram", subject)
    }
}
