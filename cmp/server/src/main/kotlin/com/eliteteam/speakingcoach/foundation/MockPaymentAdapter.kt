package com.eliteteam.speakingcoach.foundation

import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** Only a verified private Telegram callback or the local renewal worker can drive this mock. */
internal class MockPaymentAdapter(private val service: FoundationService) {
    suspend fun callback(subject: String, checkoutId: String, success: Boolean): JsonObject {
        return service.database.transaction { c, _ ->
            val student = c.rows("SELECT student_id FROM student_identity WHERE provider='telegram' AND subject=?", subject) { it.getString(1) }.singleOrNull() ?: reject("not_owned")
            c.lockStudent(student)
            val p = c.document("SELECT to_jsonb(p) FROM billing_checkout p WHERE id=? AND student_id=?", checkoutId, student) ?: reject("not_owned")
            val event = event(p, success)
            service.billing.apply(c, event, service.database.clock.instant())
        }
    }

    private fun event(p: JsonObject, success: Boolean) = json("eventId" to "mock:${p.text("id")}",
        "chargeId" to "mock:${p.text("id")}", "checkoutId" to p.text("id"), "studentId" to p.text("student_id"),
        "amount" to p.number("amount"), "currency" to p.text("currency"), "outcome" to if (success) "succeeded" else "failed")

    fun launch(scope: CoroutineScope) = scope.launch {
        while (isActive) {
            try { renewDue() }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { com.eliteteam.speakingcoach.RuntimeDiagnostics.record("billing_worker_failed", "mock_renewal", error) }
            delay(30_000)
        }
    }

    suspend fun renewDue(success: Boolean = true) {
        if (service.billingConfig == null) return
        val students = service.database.transaction(readOnly = true) { c, now ->
            c.rows("SELECT DISTINCT student_id FROM subscription WHERE provider='mock' AND status='active' AND valid_until<=? LIMIT 100", now) { it.getString(1) }
        }
        for (student in students) service.database.transaction { c, _ ->
            c.lockStudent(student)
            val now = service.database.clock.instant()
            val due = c.documents("SELECT to_jsonb(s) FROM subscription s WHERE student_id=? AND provider='mock' AND status='active' AND valid_until<=?", student, now)
            for (s in due) {
                c.update("UPDATE subscription SET status='expired',version=version+1 WHERE id=?", s.text("id"))
                if (s.optionalText("auto_renew") != "true") continue
                val epoch = c.rows("SELECT data_epoch FROM student WHERE id=? AND deleted_at IS NULL", student) { it.getLong(1) }.singleOrNull() ?: continue
                val id = "renew:${s.text("id")}:${Instant.parse(s.text("valid_until")).epochSecond}"
                c.update("""INSERT INTO billing_checkout(id,student_id,subscription_id,data_epoch,provider,amount,currency,period_days,status,created_at,expires_at,delivery_status)
                    VALUES (?,?,?,?,'mock',?,?,?,'pending',?,?,'not_required') ON CONFLICT DO NOTHING""",
                    id, student, s.text("id"), epoch, s.number("amount"), s.text("currency"), s.number("period_days"), now, now.plusSeconds(86400))
                val p = c.document("SELECT to_jsonb(p) FROM billing_checkout p WHERE id=?", id)!!
                service.billing.apply(c, event(p, success), now)
            }
        }
    }
}
