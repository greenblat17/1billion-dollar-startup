package com.eliteteam.speakingcoach.foundation

import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TrialIntegrationTestBilling {
    private class TestClock(var now: Instant = Instant.parse("2026-11-12T10:00:00Z")) : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
    private suspend fun store(block: suspend (FoundationService, TestClock, String) -> Unit) {
        val raw = requireNotNull(System.getenv("TEST_POSTGRES_URL"))
        val url = if (raw.startsWith("jdbc:")) raw else "jdbc:$raw"
        val schema = "sp05_${newId().replace("-", "")}"
        DriverManager.getConnection(url).use { it.createStatement().execute("CREATE SCHEMA $schema") }
        val clock = TestClock()
        try {
            FoundationService(FoundationDatabase(url + (if ('?' in url) "&" else "?") + "currentSchema=$schema", clock), BillingConfig(100, "TEST", 1)).use {
                block(it, clock, it.identity.resolve("telegram", "100"))
            }
        } finally { DriverManager.getConnection(url).use { it.createStatement().execute("DROP SCHEMA $schema CASCADE") } }
    }
    private suspend fun FoundationService.op(student: String, action: String, args: JsonObject = json(), id: String = newId()) =
        command(identity.issue(student, "operator"), json("version" to 1, "operationId" to id, "action" to action, "args" to args))
    private suspend fun checkout(s: FoundationService, student: String) = s.op(student, "subscription.checkout")
    private suspend fun pay(s: FoundationService, p: JsonObject, success: Boolean = true) = MockPaymentAdapter(s).callback("100", p.text("id"), success)
    private suspend fun read(s: FoundationService, student: String) = s.op(student, "subscription.get")

    @Test fun checkoutDoesNotGrantAccessAndSuccessStartsNowAndSurvivesDuplicateRace() = runTest {
        store { s, clock, student ->
            val p = checkout(s, student)
            assertTrue(p.text("terms").contains("даже если первый урок позже"))
            assertEquals(p, checkout(s, student))
            assertEquals("inactive", read(s, student).text("status"))
            clock.now = clock.now.plusSeconds(3600)
            kotlinx.coroutines.coroutineScope { val a = async { pay(s, p) }; val b = async { pay(s, p) }; a.await(); b.await() }
            val result = read(s, student)
            assertEquals("active", result.text("status"))
            assertEquals(clock.now, Instant.parse(result.obj("subscription").text("valid_from")))
            assertEquals(clock.now.plusSeconds(86400), Instant.parse(result.obj("subscription").text("valid_until")))
            s.database.transaction { c, _ ->
                assertEquals(1L, c.rows("SELECT count(*) FROM payment_receipt") { it.getLong(1) }.single())
                assertEquals(1L, c.rows("SELECT count(*) FROM entitlement") { it.getLong(1) }.single())
            }
            assertEquals("operation_conflict", assertFailsWith<FoundationFailure> { pay(s, p, false) }.code)
        }
    }

    @Test fun wrongOwnerAmountCurrencyAndEpochCannotActivate() = runTest {
        store { s, _, student ->
            val p = checkout(s, student)
            s.identity.resolve("telegram", "200")
            assertEquals("not_owned", assertFailsWith<FoundationFailure> { MockPaymentAdapter(s).callback("200", p.text("id"), true) }.code)
            for (bad in listOf(json("amount" to 101), json("currency" to "RUB"))) {
                val event = json("eventId" to newId(), "chargeId" to newId(), "checkoutId" to p.text("id"),
                    "studentId" to student, "amount" to 100, "currency" to "TEST", "outcome" to "succeeded")
                assertEquals("payment_terms_mismatch", assertFailsWith<FoundationFailure> {
                    s.database.transaction { c, now -> s.billing.apply(c, JsonObject(event + bad), now) }
                }.code)
            }
            s.database.transaction { c, _ -> c.update("UPDATE student SET data_epoch=data_epoch+1 WHERE id=?", student) }
            assertEquals("source_expired", assertFailsWith<FoundationFailure> { pay(s, p) }.code)
            assertEquals("inactive", read(s, student).text("status"))
        }
    }

    @Test fun failedPaymentCanRetryAndDisabledRenewalPreservesPaidPeriod() = runTest {
        store { s, clock, student ->
            val p = checkout(s, student)
            pay(s, p, false)
            pay(s, p, false)
            assertEquals("inactive", read(s, student).text("status"))
            val retry = checkout(s, student)
            assertTrue(retry.text("id") != p.text("id"))
            val paid = pay(s, retry).obj("subscription")
            s.op(student, "subscription.cancel_renewal", json("subscriptionId" to paid.text("id"), "expectedVersion" to paid.number("version")))
            assertEquals("active", read(s, student).text("status"))
            clock.now = Instant.parse(paid.text("valid_until"))
            MockPaymentAdapter(s).renewDue()
            assertEquals("expired", read(s, student).text("status"))
            assertEquals("expired", read(s, student).obj("subscription").text("status"))
        }
    }

    @Test fun automaticRenewalRunsOnceAndFailureDoesNotExtendPeriod() = runTest {
        store { s, clock, student ->
            val first = pay(s, checkout(s, student)).obj("subscription")
            clock.now = Instant.parse(first.text("valid_until"))
            kotlinx.coroutines.coroutineScope { val a = async { MockPaymentAdapter(s).renewDue() }; val b = async { MockPaymentAdapter(s).renewDue() }; a.await(); b.await() }
            val renewed = read(s, student).obj("subscription")
            assertEquals(clock.now, Instant.parse(renewed.text("valid_from")))
            s.database.transaction { c, _ -> assertEquals(2L, c.rows("SELECT count(*) FROM payment_receipt") { it.getLong(1) }.single()) }
            clock.now = Instant.parse(renewed.text("valid_until"))
            MockPaymentAdapter(s).renewDue(success = false)
            MockPaymentAdapter(s).renewDue()
            assertEquals("expired", read(s, student).text("status"))
            assertEquals(renewed.text("valid_until"), read(s, student).obj("subscription").text("valid_until"))
        }
    }

    @Test fun staleCheckoutAndDisabledBillingFailClosed() = runTest {
        store { s, clock, student ->
            val p = checkout(s, student)
            clock.now = clock.now.plusSeconds(86400)
            assertEquals("checkout_expired", assertFailsWith<FoundationFailure> { pay(s, p) }.code)
            assertEquals("inactive", read(s, student).text("status"))
            s.database.transaction { c, now ->
                assertEquals("billing_not_configured", assertFailsWith<FoundationFailure> {
                    SubscriptionBilling(null).checkout(c, ExecutionScope(student, "operator", 1), json(), now)
                }.code)
            }
        }
    }

    private fun auth(now: Instant): String {
        fun hmac(key: ByteArray, text: String) = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(text.toByteArray()) }
        val fields = sortedMapOf("auth_date" to now.epochSecond.toString(), "user" to "{\"id\":100}")
        val hash = hmac(hmac("WebAppData".toByteArray(), "123:test"), fields.entries.joinToString("\n") { "${it.key}=${it.value}" }).joinToString("") { "%02x".format(it) }
        return (fields + ("hash" to hash)).entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
    }
    private suspend fun open(s: FoundationService, id: String, now: Instant) = TrialAccess(s).access(
        json("action" to "open", "initData" to auth(now), "lessonId" to id, "attemptId" to newId()), "123:test")
    private suspend fun lesson(s: FoundationService, student: String, now: Instant, kind: String) = s.op(student, "lesson.create",
        json("kind" to kind, "launchMode" to "executable", "startsAt" to now.toString(), "timezone" to "UTC", "durationMinutes" to 30))

    @Test fun regularRequiresSubscriptionTrialIsFreeAndAdmittedLessonCanReconnectAfterExpiry() = runTest {
        store { s, clock, student ->
            val regular = lesson(s, student, clock.now, "regular")
            assertEquals("subscription_required", assertFailsWith<FoundationFailure> { open(s, regular.text("id"), clock.now) }.code)
            val trial = lesson(s, student, clock.now, "trial")
            val free = open(s, trial.text("id"), clock.now)
            s.command(free.text("scope"), json("version" to 1, "operationId" to newId(), "action" to "voice.end", "args" to
                json("lessonId" to trial.text("id"), "callSessionId" to free.text("callSessionId"), "reason" to "technical_disconnect")))
            val paid = pay(s, checkout(s, student)).obj("subscription")
            // Finish the old trial without an evaluation prerequisite for this admission fixture.
            s.database.transaction { c, _ -> c.update("UPDATE lesson SET status='cancelled' WHERE id=?", trial.text("id")) }
            repeat(2) {
                val job = s.op(student, "job.claim", json("kind" to "lesson.prepare")).obj("job")
                s.op(student, "job.apply", json("jobId" to job.text("id"), "fence" to job.number("fence"),
                    "result" to json("objective" to "Speak", "topic" to "Travel", "strategy" to "Discuss")))
            }
            val first = open(s, regular.text("id"), clock.now)
            s.command(first.text("scope"), json("version" to 1, "operationId" to newId(), "action" to "voice.end", "args" to
                json("lessonId" to regular.text("id"), "callSessionId" to first.text("callSessionId"), "reason" to "technical_disconnect")))
            clock.now = Instant.parse(paid.text("valid_until"))
            assertEquals("expired", read(s, student).text("status"))
            assertEquals("in_progress", open(s, regular.text("id"), clock.now).obj("lesson").text("status"))
            val next = lesson(s, student, clock.now, "regular")
            assertEquals("subscription_required", assertFailsWith<FoundationFailure> { open(s, next.text("id"), clock.now) }.code)
            assertEquals("scheduled", s.op(student, "lesson.get", json("lessonId" to next.text("id"))).text("status"))
        }
    }

    @Test fun textAndVoiceCannotConfirmPaymentAndDeletionRemovesBillingRows() = runTest {
        store { s, clock, student ->
            val request = "pay"
            val source = s.history.accept(student, request, "Пришли оплату", clock.now, false)
            val scope = s.identity.issue(student, "text", request)
            suspend fun cmd(action: String, args: JsonObject) = s.command(scope, json("version" to 1, "operationId" to newId(), "action" to action, "args" to args))
            cmd("run.claim", json())
            assertEquals("not_permitted", assertFailsWith<FoundationFailure> { cmd("access.confirm_payment", json()) }.code)
            assertEquals("invalid_evidence", assertFailsWith<FoundationFailure> { cmd("subscription.checkout", json("sourceId" to source.text("event_id"), "evidence" to "не существует")) }.code)
            val p = cmd("subscription.checkout", json("sourceId" to source.text("event_id"), "evidence" to "Пришли оплату"))
            assertTrue(FoundationChat(s).beginPayment("tg-100", request) != null)
            assertEquals(null, FoundationChat(s).beginPayment("tg-100", request))
            pay(s, p)
            s.op(student, "student.delete")
            s.database.transaction { c, _ -> assertEquals(0L, c.rows("SELECT count(*) FROM billing_event") { it.getLong(1) }.single()) }
        }
    }
}
