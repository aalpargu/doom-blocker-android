package dev.personal.doomblocker.ui

import dev.personal.doomblocker.core.DailyAppRecord
import dev.personal.doomblocker.core.DailyRecord
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayPresentationTest {
    @Test fun `shared pool is counted once in the dashboard balance`() {
        val now = dev.personal.doomblocker.core.ClockReading(1_800_000_000_000L, 100_000, 1)
        var s = dev.personal.doomblocker.core.UsageEngine.newSnapshot(now, "UTC")
        listOf("x", "instagram", "tiktok").forEach {
            s = dev.personal.doomblocker.core.UsageEngine.addApp(s, it, it, dev.personal.doomblocker.core.Limits(20, 5, 10), 5 * 60_000, now)
        }
        s = dev.personal.doomblocker.core.UsageEngine.addGroup(s, "social",
            dev.personal.doomblocker.core.GroupPolicy("Sosyal", setOf("x", "instagram", "tiktok"), 30), now)
        assertEquals(15 * 60_000L, remainingDailyBudget(s, now))
        assertEquals(30 * 60_000L, totalDailyBudget(s))
    }

    @Test fun `calendar ranges exclude stale records instead of taking the last seven entries`() {
        val records = listOf(1L, 2L, 10L, 11L, 30L).map { DailyRecord(it, emptyList()) }
        assertEquals(listOf(30L), calendarRecords(records, 30, 7).map { it.epochDay })
    }
    @Test
    fun `seven day heatmap ending sunday fills one calendar row`() {
        val activeDay = LocalDate.of(2026, 9, 13).toEpochDay()
        val cells = usageHeatmapCells(emptyMap(), activeDay, 7)

        assertEquals(7, cells.size)
        assertEquals(activeDay - 6, cells.first()?.epochDay)
        assertEquals(activeDay, cells.last()?.epochDay)
    }

    @Test
    fun `thirty day heatmap aligns dates to monday columns`() {
        val activeDay = LocalDate.of(2026, 9, 13).toEpochDay()
        val cells = usageHeatmapCells(emptyMap(), activeDay, 30)

        assertEquals(35, cells.size)
        repeat(5) { assertNull(cells[it]) }
        assertEquals(activeDay - 29, cells[5]?.epochDay)
        assertEquals(activeDay, cells.last()?.epochDay)
    }

    @Test
    fun `recorded usage ignores empty records and detects positive usage`() {
        val empty = DailyRecord(1, listOf(DailyAppRecord("app", "App", 0, 0, 0)))
        val used = DailyRecord(2, listOf(DailyAppRecord("app", "App", 1_000, 0, 1_000)))

        assertFalse(hasRecordedUsage(listOf(empty)))
        assertTrue(hasRecordedUsage(listOf(empty, used)))
    }
}
