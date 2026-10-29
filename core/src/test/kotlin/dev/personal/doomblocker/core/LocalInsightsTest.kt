package dev.personal.doomblocker.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LocalInsightsTest {
    @Test fun `three observed days do not pretend to be a full week for limit suggestions`() {
        val initial = base()
        val s = initial.copy(history = (1L..3L).map { distance ->
            DailyRecord(initial.activeEpochDay - distance, listOf(DailyAppRecord("social.app", "Sosyal", 600_000, 0)))
        })
        assertTrue(LocalInsights.candidates(s, wall).none { it.kind == InsightKind.LIMIT_SUGGESTION || it.kind == InsightKind.WEEKLY_TREND })
    }
    private val wall = Instant.parse("2026-09-06T22:15:00Z").toEpochMilli()
    private val now = ClockReading(wall, 500_000, 3)

    private fun base(): ProtectionSnapshot = UsageEngine.addApp(
        UsageEngine.newSnapshot(now, "UTC"),
        "social.app", "Sosyal", Limits(30, 5, 10), 10 * 60_000L, now,
    )

    @Test fun `finds frequent limits lower limit suggestion and weekly trend`() {
        val initial = base()
        val history = (1L..14L).map { distance ->
            val recentWeek = distance <= 7
            DailyRecord(
                initial.activeEpochDay - distance,
                listOf(DailyAppRecord(
                    "social.app", "Sosyal",
                    (if (recentWeek) 10 else 5) * 60_000L,
                    if (distance <= 3) 1 else 0,
                )),
            )
        }
        val state = initial.copy(
            apps = initial.apps.map { it.copy(limitEventsToday = 1) },
            history = history,
        )
        val kinds = LocalInsights.candidates(state, wall).map { it.kind }.toSet()
        assertTrue(InsightKind.LIMIT_FREQUENCY in kinds)
        assertTrue(InsightKind.LIMIT_SUGGESTION in kinds)
        assertTrue(InsightKind.WEEKLY_TREND in kinds)
    }

    @Test fun `finds busiest two hour opening window`() {
        val state = base().copy(openEvents = (0 until 8).map {
            OpenEvent("social.app", wall - it * 60_000L)
        })
        assertTrue(LocalInsights.candidates(state, wall).any { it.kind == InsightKind.PEAK_OPENING })
    }

    @Test fun `finds higher Sunday usage`() {
        val initial = base()
        val history = (1L..14L).map { distance ->
            val day = initial.activeEpochDay - distance
            val sunday = java.time.LocalDate.ofEpochDay(day).dayOfWeek == java.time.DayOfWeek.SUNDAY
            DailyRecord(day, listOf(DailyAppRecord("social.app", "Sosyal", (if (sunday) 30 else 5) * 60_000L, 0)))
        }
        assertTrue(LocalInsights.candidates(initial.copy(history = history), wall).any { it.kind == InsightKind.SUNDAY_PATTERN })
    }

    @Test fun `finds an unused schedule`() {
        var state = base()
        state = UsageEngine.addSchedule(
            state, "night", SchedulePolicy("Gece", setOf("social.app"), 127, 60, 120), now,
        )
        state = state.copy(history = (1L..4L).map { distance ->
            DailyRecord(state.activeEpochDay - distance, listOf(DailyAppRecord("social.app", "Sosyal", 0, 0)))
        }, apps = state.apps.map { it.copy(usedTodayMillis = 0) })
        assertTrue(LocalInsights.candidates(state, wall).any { it.kind == InsightKind.UNUSED_PLAN })
    }
}
