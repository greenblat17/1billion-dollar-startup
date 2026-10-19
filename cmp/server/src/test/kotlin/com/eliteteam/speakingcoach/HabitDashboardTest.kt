package com.eliteteam.speakingcoach

import com.eliteteam.speakingcoach.ai.AdminRetentionDay
import com.eliteteam.speakingcoach.ai.HabitBucket
import com.eliteteam.speakingcoach.ai.HabitCohort
import com.eliteteam.speakingcoach.ai.HabitDay
import com.eliteteam.speakingcoach.ai.HabitSnapshot
import com.eliteteam.speakingcoach.analytics.HabitDuration
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class HabitDashboardTest {
    @Test
    fun showsSampleSizesAndDistinguishesMissingFromZero() {
        val snapshot = HabitSnapshot(healthy = true, asOf = "2026-10-18", observedThrough = "2026-10-18",
            historySince = "2026-09-24", updatedAt = 1792328400.0,
            days = listOf(HabitDay("2026-10-18", 2, 4, 0.5, true, false,
                listOf(HabitBucket("1", 1), HabitBucket("2", 1), HabitBucket("3", 1), HabitBucket("4_7", 1)))),
            cohorts = listOf(HabitCohort("2026-10-12", 4, false,
                listOf(AdminRetentionDay(1, 3, 2), AdminRetentionDay(3, 0, 0)))))
        val html = adminOverviewHtml(LlmRange(java.time.LocalDate.parse("2026-10-12"),
            java.time.LocalDate.parse("2026-10-18")), com.eliteteam.speakingcoach.ai.AdminModelOverview(habits = snapshot),
            null, habitDuration = HabitDuration(calls = 2, speechSeconds = 180.0))
        assertContains(html, "50.0%")
        assertContains(html, "66.7% · 2/3 · частично зрелая")
        assertContains(html, "нет зрелой выборки")
        assertContains(html, "1.50 мин")
        assertContains(html, "2026-10-12 — 2026-10-18")
        System.getenv("HABIT_PREVIEW_FILE")?.let { Files.writeString(Path.of(it), html) }
        val unavailable = habitDashboardHtml(snapshot.copy(healthy = false))
        assertContains(unavailable, "Источник активности недоступен")
        assertFalse(unavailable.contains("50.0%"))
    }
}
