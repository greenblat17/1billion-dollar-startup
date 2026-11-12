package com.eliteteam.speakingcoach.foundation

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonObject

/** Mock is opt-in. Tariff comes from the operator, never from the model or callback. */
internal data class BillingConfig(val amount: Long, val currency: String, val periodDays: Int) {
    init {
        require(amount > 0 && currency.matches(Regex("[A-Z]{3,8}")) && periodDays in 1..366)
    }
    companion object {
        fun fromEnvironment(): BillingConfig? {
            val mode = System.getenv("SPEAKY_BILLING_PROVIDER").orEmpty()
            if (mode.isBlank()) return null
            require(mode == "mock") { "Only the explicit mock provider is implemented" }
            return BillingConfig(requireNotNull(System.getenv("SPEAKY_BILLING_AMOUNT")).toLong(),
                requireNotNull(System.getenv("SPEAKY_BILLING_CURRENCY")),
                requireNotNull(System.getenv("SPEAKY_BILLING_PERIOD_DAYS")).toInt())
        }
    }
}

internal class SubscriptionBilling(private val config: BillingConfig?) {
    fun read(c: Connection, student: String, now: Instant): JsonObject {
        val subscription = c.document("SELECT to_jsonb(s) FROM subscription s WHERE student_id=? AND provider='mock' ORDER BY valid_until DESC NULLS LAST,id LIMIT 1", student)
        val active = c.activeEntitlement(student, now)
        return json("configured" to (config != null), "mock" to true,
            "terms" to config?.let { json("amount" to it.amount, "currency" to it.currency, "periodDays" to it.periodDays,
                "autoRenew" to true, "startsImmediately" to true, "maxLessonsPerWeek" to 7, "minMinutes" to 15, "maxMinutes" to 60) },
            "status" to if (active != null) "active" else if (subscription?.optionalText("valid_until") != null) "expired" else "inactive",
            "subscription" to subscription, "entitlement" to active,
            "lastPayment" to c.document("SELECT jsonb_build_object('status',status,'createdAt',created_at) FROM billing_checkout WHERE student_id=? ORDER BY created_at DESC,id DESC LIMIT 1", student))
    }

    fun checkout(c: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "text", "operator")
        args.only("sourceId", "evidence")
        if (scope.role == "text") evidence(c, scope, args, now)
        val tariff = config ?: reject("billing_not_configured")
        if (c.activeEntitlement(scope.studentId, now) != null) reject("subscription_already_active")
        c.document("SELECT to_jsonb(p) FROM billing_checkout p WHERE student_id=? AND status='pending' AND expires_at>? AND data_epoch=? ORDER BY created_at DESC LIMIT 1",
            scope.studentId, now, scope.epoch)?.let { return projection(it) }
        val subscription = c.document("SELECT to_jsonb(s) FROM subscription s WHERE student_id=? AND provider='mock' ORDER BY valid_until DESC NULLS LAST,id LIMIT 1", scope.studentId)
        val subscriptionId = subscription?.text("id") ?: newId().also {
            c.update("INSERT INTO subscription(id,student_id,status,provider) VALUES (?,?,'inactive','mock')", it, scope.studentId)
        }
        val id = newId()
        c.update("""INSERT INTO billing_checkout(id,student_id,subscription_id,request_id,data_epoch,provider,amount,currency,period_days,status,created_at,expires_at)
            VALUES (?,?,?,?,?,'mock',?,?,?,'pending',?,?)""",
            id, scope.studentId, subscriptionId, scope.requestId, scope.epoch, tariff.amount, tariff.currency, tariff.periodDays, now, now.plusSeconds(86400))
        return projection(c.document("SELECT to_jsonb(p) FROM billing_checkout p WHERE id=?", id)!!)
    }

    fun cancelRenewal(c: Connection, scope: ExecutionScope, args: JsonObject, now: Instant): JsonObject {
        requireRole(scope, "text", "operator")
        args.only("sourceId", "evidence", "subscriptionId", "expectedVersion")
        if (scope.role == "text") evidence(c, scope, args, now)
        if (c.update("UPDATE subscription SET auto_renew=FALSE,version=version+1 WHERE student_id=? AND id=? AND version=? AND provider='mock'",
                scope.studentId, args.text("subscriptionId"), args.number("expectedVersion")) != 1) reject("version_conflict")
        return read(c, scope.studentId, now)
    }

    fun projection(checkout: JsonObject): JsonObject = json("id" to checkout.text("id"), "mock" to true,
        "terms" to "Тестовая оплата Speaky: ${checkout.number("amount")} ${checkout.text("currency")} за ${checkout.number("period_days")} дней. До 7 занятий в неделю, по 15–60 минут — стоимость одна. Период начинается сразу после успешной оплаты, даже если первый урок позже. Автопродление включается при успешной тестовой оплате; отключить его можно у Саши в чате. Это мок провайдера: реальные деньги не списываются.",
        "status" to checkout.text("status"))

    /** The authenticated provider adapter supplies this envelope, never a model tool. */
    fun apply(c: Connection, event: JsonObject, now: Instant): JsonObject {
        if (config == null) reject("billing_not_configured")
        event.only("eventId", "chargeId", "checkoutId", "studentId", "amount", "currency", "outcome")
        val student = event.text("studentId")
        c.lockStudent(student)
        val p = c.document("SELECT to_jsonb(p) FROM billing_checkout p WHERE id=? AND student_id=?", event.text("checkoutId"), student) ?: reject("not_owned")
        if (event.number("amount") != p.number("amount") || event.text("currency") != p.text("currency")) reject("payment_terms_mismatch")
        if (event.text("outcome") !in setOf("succeeded", "failed")) reject("invalid_payment_outcome")
        for (key in listOf("eventId", "chargeId")) if (event.text(key).length !in 1..200) reject("invalid_payment_id")
        val hash = digest(canonical(event))
        val old = c.document("SELECT to_jsonb(e) FROM billing_event e WHERE provider='mock' AND event_id=?", event.text("eventId"))
        if (old != null) {
            if (old.text("input_digest") != hash) reject("operation_conflict")
            return read(c, student, now)
        }
        if (c.document("SELECT to_jsonb(e) FROM billing_event e WHERE provider='mock' AND charge_id=?", event.text("chargeId")) != null) reject("operation_conflict")
        val epoch = c.rows("SELECT data_epoch FROM student WHERE id=? AND deleted_at IS NULL", student) { it.getLong(1) }.singleOrNull()
        if (epoch != p.number("data_epoch")) reject("source_expired")
        if (p.text("status") != "pending") reject("checkout_completed")
        if (Instant.parse(p.text("expires_at")) <= now) reject("checkout_expired")
        if (event.text("outcome") == "succeeded" && c.activeEntitlement(student, now) != null) reject("subscription_already_active")
        c.update("INSERT INTO billing_event(provider,event_id,charge_id,student_id,checkout_id,input_digest,outcome,received_at) VALUES ('mock',?,?,?,?,?,?,?)",
            event.text("eventId"), event.text("chargeId"), student, p.text("id"), hash, event.text("outcome"), now)
        c.update("UPDATE billing_checkout SET status=? WHERE id=?", event.text("outcome"), p.text("id"))
        if (event.text("outcome") == "succeeded") {
            val until = now.plusSeconds(p.number("period_days") * 86400)
            val subscription = p.text("subscription_id")
            c.update("INSERT INTO payment_receipt(provider,external_id,student_id,subscription_id,input_digest,confirmed_at) VALUES ('mock',?,?,?,?,?)",
                event.text("chargeId"), student, subscription, hash, now)
            c.update("INSERT INTO entitlement(id,student_id,subscription_id,valid_from,valid_until,source_provider,source_external_id) VALUES (?,?,?,?,?,'mock',?)",
                newId(), student, subscription, now, until, event.text("chargeId"))
            c.update("""UPDATE subscription SET status='active',valid_from=?,valid_until=?,auto_renew=TRUE,
                amount=?,currency=?,period_days=?,version=version+1 WHERE id=? AND student_id=?""",
                now, until, p.number("amount"), p.text("currency"), p.number("period_days"), subscription, student)
        }
        return read(c, student, now)
    }
}

internal fun Connection.activeEntitlement(student: String, now: Instant): JsonObject? = document(
    "SELECT to_jsonb(e) FROM entitlement e JOIN subscription s ON s.id=e.subscription_id WHERE e.student_id=? AND e.valid_from<=? AND e.valid_until>? AND s.status='active' ORDER BY e.valid_until DESC LIMIT 1", student, now, now)
