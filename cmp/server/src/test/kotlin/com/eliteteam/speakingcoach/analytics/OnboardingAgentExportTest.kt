package com.eliteteam.speakingcoach.analytics

import com.eliteteam.speakingcoach.ai.ReminderClockSummary
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class OnboardingAgentExportTest {
    @Test
    fun exportKeepsExactConversionDenominatorsAndNoUserIdentifiers() {
        val start = Instant.parse("2026-09-28T07:00:00Z")
        val now = Instant.parse("2026-10-01T00:00:00Z")
        val attempts = listOf(
            OnboardingAttemptRow("secret-run-1", "secret-user-1", "start", true, start,
                letsChatAt = start.plusSeconds(10), firstVoiceAt = start.plusSeconds(20),
                completedAt = start.plusSeconds(40), cefr = "secret-level"),
            OnboardingAttemptRow("secret-run-2", "secret-user-2", "start", true, start,
                firstVoiceAt = start.plusSeconds(30)),
        )
        val voiceRow = OnboardingVoiceRow("secret-run-1", "secret-user-1", false, "secret-outcome",
            start.plusSeconds(50), outcome = "secret-outcome")
        val report = onboardingReport(attempts, listOf(voiceRow), now)
        val text = onboardingAgentJson(report, now,
            ReminderClockSummary("Europe/Moscow", 2, mapOf("08" to 1, "13" to 1)))
        val root = Json.parseToJsonElement(text).jsonObject
        val cohort = root.getValue("cohorts").jsonObject.getValue("closed_primary").jsonArray.single().jsonObject
        val voice = cohort.getValue("steps").jsonArray.first {
            it.jsonObject.getValue("id").jsonPrimitive.content == "first_voice"
        }.jsonObject
        assertEquals("onboarding-analytics.v5", root.getValue("schema_version").jsonPrimitive.content)
        assertEquals(JsonNull, root.getValue("llm_requests_today"))
        assertEquals(JsonNull, root.getValue("llm_requests_period"))
        val reminders = root.getValue("current_reminders").jsonObject
        assertEquals("all_users_current", reminders.getValue("scope").jsonPrimitive.content)
        assertEquals("2", reminders.getValue("active").jsonPrimitive.content)
        assertEquals("1", reminders.getValue("by_hour").jsonObject.getValue("08").jsonPrimitive.content)
        assertEquals("2", voice.getValue("count").jsonPrimitive.content)
        assertEquals("1", voice.getValue("from_previous").jsonObject.getValue("numerator").jsonPrimitive.content)
        assertEquals("1", voice.getValue("from_previous").jsonObject.getValue("denominator").jsonPrimitive.content)
        assertEquals("100", voice.getValue("from_previous").jsonObject.getValue("percent").jsonPrimitive.content)
        assertEquals(JsonNull, cohort.getValue("d7_from_start"))
        assertEquals("1", cohort.getValue("levels_among_built_results").jsonObject.getValue("other").jsonPrimitive.content)
        assertFalse(text.contains("secret-run"))
        assertFalse(text.contains("secret-user"))
        assertFalse(text.contains("secret-level"))
        assertFalse(text.contains("secret-outcome"))
    }
}
