package com.eliteteam.speakingcoach.foundation

import com.eliteteam.speakingcoach.ai.TutorRequest
import java.sql.Connection
import java.time.Duration
import java.time.Instant

internal val TELEGRAM_INTRO_TEXTS = listOf(
    "Привет! 👋",
    "Меня зовут Саша. Я — твой личный ИИ-репетитор английского.",
    "Я помогаю людям учить английский, чтобы:\n💼 Найти работу\n🌍 Переехать за границу\n💬 Перестать бояться английского и наконец начать ГОВОРИТЬ",
    "А для чего английский нужен тебе?",
)
private val introPauses = listOf(0L, 750L, 1250L, 750L)

internal data class IntroductionPart(val index: Int, val waitMillis: Long)

/** Each distinct /start creates a fresh student; a durable chat boundary makes replay harmless. */
internal class TelegramIntroduction(private val database: FoundationDatabase) {
    suspend fun prepare(chat: Long, message: Long, request: TutorRequest): Boolean =
        database.transaction { c, now ->
            require(chat > 0 && message > 0 && request.sessionId == "tg-$chat" && request.requestId == "message:$message")
            c.lockTelegramChat(chat)
            val fingerprint = digest(canonical(json("text" to request.text, "receivedAt" to request.receivedAt)))
            val boundary = c.document("SELECT to_jsonb(r) FROM telegram_start_reset r WHERE chat_id=?", chat)
            if (boundary != null && message <= boundary.number("message_id")) {
                if (message == boundary.number("message_id") && boundary.text("input_digest") != fingerprint) reject("operation_conflict")
                return@transaction true
            }
            // Same lock as identity.resolve: no caller can recreate the old identity mid-reset.
            c.rows("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "telegram:$chat") { Unit }
            val old = c.rows("SELECT student_id FROM student_identity WHERE provider='telegram' AND subject=?", chat.toString()) { it.getString(1) }.singleOrNull()
            if (old != null) {
                c.lockStudent(old)
                val epoch = c.rows("SELECT data_epoch FROM student WHERE id=?", old) { it.getLong(1) }.single()
                FoundationMaintenance().deleteStudent(c, ExecutionScope(old, "operator", epoch), now, preserveTelegramFrom = message)
            }
            val student = newId()
            c.update("INSERT INTO student(id,telegram_intro_eligible) VALUES (?,FALSE)", student)
            c.update("INSERT INTO student_identity(provider,subject,student_id) VALUES ('telegram',?,?)", chat.toString(), student)
            c.update("""INSERT INTO telegram_start_reset(chat_id,message_id,input_digest) VALUES (?,?,?)
                ON CONFLICT(chat_id) DO UPDATE SET message_id=EXCLUDED.message_id,input_digest=EXCLUDED.input_digest""", chat, message, fingerprint)
            val conversation = c.conversation(student, "telegram")
            c.appendEvent(student, conversation, request.requestId, "user", request.text,
                Instant.ofEpochMilli((request.receivedAt * 1000).toLong()), now)
            // A reply can reach durable intake before the start handler even runs.
            val interrupted = c.rows("SELECT EXISTS(SELECT 1 FROM telegram_text_intake WHERE chat_id=? AND message_id>?)", chat, message) { it.getBoolean(1) }.single()
            c.update("""INSERT INTO telegram_introduction(student_id,chat_id,request_id,message_id,input_digest,data_epoch,status,due_at,expires_at)
                SELECT id,?,?,?,?,data_epoch,?,?,? FROM student WHERE id=?""", chat, request.requestId, message, fingerprint,
                if (interrupted) "cancelled" else "active", now, now.plusSeconds(7 * 86400), student)
            true
        }

    suspend fun next(chat: Long, request: String): IntroductionPart? = database.transaction { c, now ->
        c.lockTelegramChat(chat)
        val row = c.currentIntroduction(chat, request, now) ?: return@transaction null
        if (row.text("status") in setOf("completed", "unknown")) return@transaction null
        val index = row.number("next_part").toInt()
        val claimed = c.document("SELECT to_jsonb(d) FROM telegram_intro_delivery d WHERE student_id=? AND part=?",
            row.text("student_id"), index)
        if (claimed != null) {
            // Another dispatcher may still be sending. After a crash, never retry an unknown send.
            if (Instant.parse(claimed.text("attempted_at")).plusSeconds(150) > now) reject("introduction_busy")
            c.update("UPDATE telegram_introduction SET status='unknown' WHERE student_id=?", row.text("student_id"))
            return@transaction null
        }
        if (row.text("status") != "active") return@transaction null
        IntroductionPart(index, Duration.between(now, Instant.parse(row.text("due_at"))).toMillis().coerceAtLeast(0))
    }

    suspend fun claim(chat: Long, request: String, part: Int): Boolean = database.transaction { c, now ->
        c.lockTelegramChat(chat)
        val row = c.currentIntroduction(chat, request, now) ?: return@transaction false
        if (row.text("status") != "active" || row.number("next_part") != part.toLong() || Instant.parse(row.text("due_at")) > now) return@transaction false
        c.update("""INSERT INTO telegram_intro_delivery(student_id,part,outcome,attempted_at) VALUES (?,?,'unknown',?)
            ON CONFLICT DO NOTHING""", row.text("student_id"), part, now) == 1
    }

    suspend fun sent(chat: Long, request: String, part: Int, message: Long) = database.transaction { c, now ->
        require(message > 0)
        val row = c.document("SELECT to_jsonb(i) FROM telegram_introduction i WHERE chat_id=? AND request_id=?", chat, request)
            ?: return@transaction
        val student = row.text("student_id")
        c.lockStudent(student)
        // Forget/delete revokes this sequence, including a late external receipt.
        if (c.rows("SELECT data_epoch FROM student WHERE id=?", student) { it.getLong(1) }.single() != row.number("data_epoch")) return@transaction
        val changed = c.update("UPDATE telegram_intro_delivery SET outcome='sent',message_id=? WHERE student_id=? AND part=? AND outcome='unknown'",
            message, student, part)
        if (changed == 0) return@transaction
        c.appendEvent(student, c.conversation(student, "telegram"), "intro:$request:$part", "assistant", TELEGRAM_INTRO_TEXTS[part], now, now)
        c.update("""UPDATE telegram_introduction SET next_part=?,due_at=?,
            status=CASE WHEN status='active' AND ?=4 THEN 'completed' ELSE status END WHERE student_id=?""",
            part + 1, now.plusMillis(introPauses.getOrElse(part + 1) { 0 }), part + 1, student)
    }

    suspend fun uncertain(chat: Long, request: String) = database.transaction { c, _ ->
        c.update("UPDATE telegram_introduction SET status='unknown' WHERE chat_id=? AND request_id=? AND status='active'", chat, request)
    }
}

internal fun Connection.lockTelegramChat(chat: Long) {
    rows("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "telegram-intro:$chat") { Unit }
}

private fun Connection.currentIntroduction(chat: Long, request: String, now: Instant) = document(
    """SELECT to_jsonb(i) FROM telegram_introduction i JOIN student s ON s.id=i.student_id
        WHERE i.chat_id=? AND i.request_id=? AND i.expires_at>?
        AND i.data_epoch=s.data_epoch AND s.deleted_at IS NULL""", chat, request, now)

/** Delivery completion/cancellation is not completion of the consultation. Existing relationships stay in dialogue. */
internal fun Connection.textConversationMode(student: String): String = rows(
    """SELECT EXISTS(SELECT 1 FROM telegram_introduction WHERE student_id=?)
        AND NOT EXISTS(SELECT 1 FROM lesson WHERE student_id=?)""", student, student,
) { if (it.getBoolean(1)) "introduction" else "dialogue" }.single()
