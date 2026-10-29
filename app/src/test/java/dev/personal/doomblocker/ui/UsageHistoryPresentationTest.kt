package dev.personal.doomblocker.ui

import dev.personal.doomblocker.core.ClockReading
import dev.personal.doomblocker.core.DailyAppRecord
import dev.personal.doomblocker.core.DailyRecord
import dev.personal.doomblocker.core.Limits
import dev.personal.doomblocker.core.ProtectedApp
import dev.personal.doomblocker.core.ProtectionSnapshot
import dev.personal.doomblocker.core.TimeAnchor
import dev.personal.doomblocker.core.UsageEngine
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageHistoryPresentationTest {
    private val minute = 60_000L
    private val day = LocalDate.of(2026, 9, 22).toEpochDay()
    private val now = ClockReading(
        LocalDate.ofEpochDay(day).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
        120_000L,
        1,
    )

    private fun snapshot(
        history: List<DailyRecord> = emptyList(),
        apps: List<ProtectedApp> = emptyList(),
    ): ProtectionSnapshot = UsageEngine.newSnapshot(now, "UTC").copy(history = history, apps = apps)

    private fun app(
        packageName: String,
        label: String = packageName,
        usedMillis: Long = 0,
        limitEvents: Int = 0,
        longestSessionMillis: Long = 0,
    ) = ProtectedApp(
        packageName = packageName,
        label = label,
        limits = Limits(),
        usedTodayMillis = usedMillis,
        limitEventsToday = limitEvents,
        longestSessionTodayMillis = longestSessionMillis,
    )

    private fun record(
        epochDay: Long,
        packageName: String,
        label: String = packageName,
        usedMillis: Long,
        limitEvents: Int = 0,
        longestSessionMillis: Long = 0,
    ) = DailyRecord(epochDay, listOf(DailyAppRecord(
        packageName, label, usedMillis, limitEvents, longestSessionMillis,
    )))

    @Test fun `live day and removed historical app contribute without inventing missing days`() {
        val live = app("current", "Current", minute, 1, minute).copy(
            sessionUsedMillis = 2 * minute,
            foregroundSince = TimeAnchor(now.wallMillis - 30_000L, now.elapsedMillis - 30_000L, now.bootCount),
        )
        val dashboard = buildHistoryDashboard(snapshot(
            history = listOf(
                record(day - 1, "removed", "Removed", 30 * minute, 2, 7 * minute),
                DailyRecord(day - 3, emptyList()),
            ),
            apps = listOf(live),
        ), now, 7)

        assertEquals((31 * minute) + 30_000L, dashboard.totalMillis)
        assertEquals(dashboard.totalMillis / 3, dashboard.averageMillis)
        assertEquals(3, dashboard.limitEvents)
        assertEquals(7 * minute, dashboard.longestSessionMillis)
        assertEquals((day - 6..day).toList(), dashboard.days.map { it.epochDay })
        assertFalse(dashboard.days.first().recorded)
        assertTrue(dashboard.days.first { it.epochDay == day - 3 }.recorded)
        assertEquals(0L, dashboard.days.first { it.epochDay == day - 3 }.usedMillis)
        assertEquals(90_000L, dashboard.days.last().usedMillis)
        assertEquals(2 * minute + 30_000L, dashboard.days.last().apps.single().longestSessionMillis)

        val removed = dashboard.apps.first { it.packageName == "removed" }
        assertEquals("Removed", removed.label)
        assertEquals(30 * minute, removed.usedMillis)
        assertEquals(1, removed.daysWithLimit)
        assertEquals(7, removed.dailyUsage.size)
        assertEquals(30 * minute, removed.dailyUsage.first { it.epochDay == day - 1 }.usedMillis)
        assertEquals(2, removed.dailyUsage.first { it.epochDay == day - 1 }.limitEvents)
        assertTrue(removed.dailyUsage.first { it.epochDay == day - 1 }.apps.all { it.packageName == "removed" })
        assertFalse(removed.dailyUsage.first().recorded)
        assertFalse(removed.dailyUsage.first { it.epochDay == day - 3 }.recorded)
        assertEquals(0L, removed.dailyUsage.last().usedMillis)
        val current = dashboard.apps.first { it.packageName == "current" }
        assertEquals(1, current.dailyUsage.count { it.recorded })
        assertFalse(current.dailyUsage.first { it.epochDay == day - 3 }.recorded)
        assertTrue(current.dailyUsage.last().recorded)
        assertNull(dashboard.comparison)
        assertNull(dashboard.insight)
    }

    @Test fun `thirty day model keeps every app and excludes older records`() {
        val manyApps = (1..25).map { index ->
            DailyAppRecord("pkg.$index", "App $index", index * minute, 5, index * 1_000L)
        }
        val dashboard = buildHistoryDashboard(snapshot(history = listOf(
            DailyRecord(day - 29, manyApps),
            record(day - 30, "outside", usedMillis = 999 * minute),
        )), now, 30)

        assertEquals(30, dashboard.days.size)
        assertEquals(25, dashboard.apps.size)
        assertEquals(325 * minute, dashboard.totalMillis)
        assertEquals(125, dashboard.limitEvents)
        assertEquals("pkg.25", dashboard.apps.first().packageName)
        assertEquals(30, dashboard.apps.first().dailyUsage.size)
        assertTrue(dashboard.apps.none { it.packageName == "outside" })
        assertNull(dashboard.comparison)
        assertNull(dashboard.insight)
    }

    @Test fun `comparison uses two fully recorded completed weeks only`() {
        val history = (-14L..-1L).map { offset ->
            record(day + offset, "app", usedMillis = if (offset >= -7L) 2 * minute else minute)
        }
        val full = buildHistoryDashboard(snapshot(history), now, 7)
        assertEquals(12 * minute, full.totalMillis) // Today is still in progress and has zero use.
        assertTrue(full.comparison.orEmpty().contains("7 tamamlanmış gün"))
        assertTrue(full.comparison.orEmpty().contains("%100 arttı"))

        val gap = buildHistoryDashboard(snapshot(history.filterNot { it.epochDay == day - 10 }), now, 7)
        assertNull(gap.comparison)
        assertNull(buildHistoryDashboard(snapshot(history), now, 30).comparison)
    }

    @Test fun `insight follows selected period and favors recurring limits`() {
        val history = (-29L..-1L).map { offset ->
            DailyRecord(day + offset, listOf(
                DailyAppRecord("top", "Top", 10 * minute, 0, 5 * minute),
                DailyAppRecord("limits", "Limits", minute, if (offset in -6L..-2L) 1 else 0, minute),
            ))
        }
        val state = snapshot(history, listOf(app("top", "Top"), app("limits", "Limits")))
        val week = buildHistoryDashboard(state, now, 7)
        val month = buildHistoryDashboard(state, now, 30)

        assertEquals("limits", week.insight?.packageName)
        assertTrue(week.insight?.body.orEmpty().contains("Son 7"))
        assertTrue(week.insight?.body.orEmpty().contains("5 gününde"))
        assertEquals("top", month.insight?.packageName)
        assertTrue(month.insight?.body.orEmpty().contains("Son 30"))
        assertEquals(5, month.apps.first { it.packageName == "limits" }.daysWithLimit)
    }

    @Test fun `new app cannot appear frequently limited from three of thirty observed days`() {
        val history = (-29L..-1L).map { offset ->
            DailyRecord(day + offset, if (offset >= -3L) listOf(
                DailyAppRecord("new", "New", minute, 1, minute),
            ) else emptyList())
        }
        val month = buildHistoryDashboard(snapshot(history), now, 30)

        assertEquals(3, month.apps.single().dailyUsage.count { it.recorded })
        assertEquals(3, month.apps.single().daysWithLimit)
        assertNull(month.insight)
    }

    @Test fun `recorded zero usage remains a known zero without an invented insight`() {
        val history = (-14L..-1L).map { DailyRecord(day + it, emptyList()) }
        val dashboard = buildHistoryDashboard(snapshot(history), now, 7)

        assertTrue(dashboard.days.all { it.recorded })
        assertEquals(0L, dashboard.totalMillis)
        assertEquals(0L, dashboard.averageMillis)
        assertEquals(0, dashboard.limitEvents)
        assertTrue(dashboard.apps.isEmpty())
        assertNull(dashboard.insight)
        assertTrue(dashboard.comparison.orEmpty().contains("aynı düzeyde"))
    }

    @Test fun `duration formatting rounds minutes and supports more than a day`() {
        assertEquals("0 sn", formatHistoryDuration(0))
        assertEquals("42 sn", formatHistoryDuration(42_999))
        assertEquals("1 dk", formatHistoryDuration(60_000))
        assertEquals("28 dk", formatHistoryDuration(27 * minute + 40_000))
        assertEquals("1 sa 33 dk", formatHistoryDuration(92 * minute + 43_000))
        assertEquals("1 sa", formatHistoryDuration(60 * minute))
        assertEquals("25 sa 7 dk", formatHistoryDuration((25 * 60 + 7) * minute))
    }
}
