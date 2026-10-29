package dev.personal.doomblocker.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ReliabilityAuditTest {
    private val wall = Instant.parse("2026-09-06T10:00:00Z").toEpochMilli()
    private fun at(ms: Long = 0) = ClockReading(wall + ms, 200_000 + ms, 4)
    private fun state(limits: Limits = Limits(20, 5, 10), used: Long = 0) = UsageEngine.addApp(
        UsageEngine.newSnapshot(at(), "UTC"), "social", "Social", limits, used, at())

    @Test fun `unused app cannot escape an active focus through removal`() {
        val focused = UsageEngine.startFocusSession(state(), setOf("social"), 3_600_000, at())
        val removed = UsageEngine.requestRemoval(focused, "social", at(1_000))
        assertEquals(1, removed.apps.size)
        assertEquals(BlockReason.FOCUS_SESSION, UsageEngine.onForeground(removed, "social", true, at(2_000)).decision?.reason)
    }

    @Test fun `emergency request cannot bypass focus`() {
        val focused = UsageEngine.startFocusSession(state(), setOf("social"), 3_600_000, at())
        assertThrows(IllegalArgumentException::class.java) {
            UsageEngine.requestEmergencyAccess(focused, "social", "Mesaj", at())
        }
    }

    @Test fun `existing emergency access cannot override newly started focus`() {
        var s = UsageEngine.requestEmergencyAccess(state(Limits(1, 1, 2), 60_000), "social", "Mesaj", at())
        s = UsageEngine.startFocusSession(s, setOf("social"), 3_600_000, at(60_000))
        assertEquals(BlockReason.FOCUS_SESSION, UsageEngine.onForeground(s, "social", true, at(61_000)).decision?.reason)
        assertEquals(AppAvailability.FOCUS_BLOCKED, UsageEngine.view(s, s.apps.single(), at(61_000)).availability)
    }

    @Test fun `emergency quota does not schedule a one millisecond busy loop`() {
        val s = UsageEngine.requestEmergencyAccess(state(Limits(1, 1, 2), 60_000), "social", "Mesaj", at())
        val opened = UsageEngine.onForeground(s, "social", true, at(60_000))
        assertNull(opened.decision)
        assertEquals(UsageEngine.CHECKPOINT_MILLIS, opened.nextWakeupMillis)
    }

    @Test fun `actual usage beyond quota is retained instead of being clipped`() {
        assertEquals(180_000L, state(Limits(1, 1, 2), 180_000).apps.single().usedTodayMillis)
        val recovered = UsageEngine.recoverUsage(state(Limits(1, 1, 2)),
            listOf(UsageInterval("social", wall, wall + 180_000)), wall, null, "social", at(180_000))
        assertEquals(180_000L, UsageEngine.view(recovered, recovered.apps.single(), at(180_000)).usedTodayMillis)
    }

    @Test fun `explicit planned duration replaces the shorter fixed session limit`() {
        var s = UsageEngine.setIntentionalOpen(state(Limits(20, 1, 3)), "social", true, at())
        s = UsageEngine.startPlannedAccess(s, "social", 600_000, "Mesaj", at())
        s = UsageEngine.onForeground(s, "social", true, at()).snapshot
        val continued = UsageEngine.tick(s, at(60_000))
        assertNull(continued.decision)
        assertEquals(BlockReason.PLANNED_SESSION_END, UsageEngine.tick(continued.snapshot, at(600_000)).decision?.reason)
    }

    @Test fun `recovery does not invent cooldown gaps inside observed continuous usage`() {
        val s = UsageEngine.recoverUsage(state(Limits(60, 1, 3)),
            listOf(UsageInterval("social", wall, wall + 600_000)), wall, null, "social", at(600_000))
        assertEquals(600_000L, s.apps.single().longestSessionTodayMillis)
        assertEquals(1, s.apps.single().limitEventsToday)
    }

    @Test fun `old recovered sessions do not populate todays longest session`() {
        val tomorrow = at(86_400_000)
        val s = UsageEngine.recoverUsage(UsageEngine.restore(state(), tomorrow),
            listOf(UsageInterval("social", wall, wall + 120_000)), wall, null, null, tomorrow)
        assertEquals(0L, s.apps.single().longestSessionTodayMillis)
        assertEquals(120_000L, s.history.first().apps.single().usedMillis)
    }

    @Test fun `undo preserves usage focus emergency history and delayed relaxations`() {
        val previous = state()
        var current = UsageEngine.updateLimits(previous, "social", Limits(10, 2, 15), at())
        current = UsageEngine.onForeground(current, "social", true, at()).snapshot
        current = UsageEngine.onForeground(current, null, false, at(90_000)).snapshot
        current = UsageEngine.startFocusSession(current, setOf("social"), 3_600_000, at(90_000))
        val restored = RuleRestore.apply(current, previous, at(91_000))
        assertEquals(90_000L, restored.apps.single().usedTodayMillis)
        assertEquals(10, restored.apps.single().limits.dailyMinutes)
        assertEquals(20, restored.apps.single().pendingPolicy?.policy?.weekdayLimits?.dailyMinutes)
        assertEquals(current.focusSession, restored.focusSession)
    }

    @Test fun `pending group removal does not allow overlapping active pools`() {
        var s = state()
        for (pkg in listOf("b", "c", "d")) s = UsageEngine.addApp(s, pkg, pkg, Limits(), 0, at())
        s = UsageEngine.addGroup(s, "first", GroupPolicy("First", setOf("social", "b", "c"), 20), at())
        s = UsageEngine.updateGroup(s, "first", GroupPolicy("First", setOf("b", "c"), 20), at())
        val current = s
        assertThrows(IllegalArgumentException::class.java) {
            UsageEngine.addGroup(current, "second", GroupPolicy("Second", setOf("social", "d"), 20), at())
        }
    }

    @Test fun `strict intention disabling is immediate and survives restore`() {
        var s = UsageEngine.setIntentionalOpen(state(), "social", true, at())
        s = UsageEngine.enableStrictMode(s, at())
        s = UsageEngine.setIntentionalOpen(s, "social", false, at())
        assertFalse(s.apps.single().intentionalOpenEnabled)
        assertNull(s.apps.single().intentionalDisableAtWallMillis)
        s = UsageEngine.restore(s, at(3_600_000))
        assertFalse(s.apps.single().intentionalOpenEnabled)
        assertFalse(UsageEngine.tick(s, at(86_400_000)).snapshot.apps.single().intentionalOpenEnabled)
    }

    @Test fun `planned access cannot renew an already running grant`() {
        var s = UsageEngine.setIntentionalOpen(state(), "social", true, at())
        s = UsageEngine.startPlannedAccess(s, "social", 300_000, "Mesaj", at())
        val current = s
        assertThrows(IllegalArgumentException::class.java) { UsageEngine.startPlannedAccess(current, "social", 300_000, "Mesaj", at(1_000)) }
    }

    @Test fun `replaying the same recovery window twice does not charge twice`() {
        val intervals = listOf(UsageInterval("social", wall, wall + 30_000))
        val once = UsageEngine.recoverUsage(state(), intervals, wall, null, "social", at(30_000))
        val twice = UsageEngine.recoverUsage(once, intervals, wall, null, "social", at(30_000))
        assertEquals(30_000L, twice.apps.single().usedTodayMillis)
    }

    @Test fun `pending removal cannot cut short a focus session spanning midnight`() {
        val midnight = Instant.parse("2026-09-07T00:00:00Z").toEpochMilli() - wall
        var s = UsageEngine.requestRemoval(state(used = 1), "social", at())
        s = UsageEngine.startFocusSession(s, setOf("social"), 3_600_000, at(midnight - 60_000))
        s = UsageEngine.tick(s, at(midnight + 1_000)).snapshot
        assertEquals(1, s.apps.size)
        assertEquals(BlockReason.FOCUS_SESSION, UsageEngine.onForeground(s, "social", true, at(midnight + 2_000)).decision?.reason)
        assertTrue(UsageEngine.tick(s, at(midnight + 3_600_000)).snapshot.apps.isEmpty())
    }

    @Test fun `180 days of study schedules quotas app switching and weekly reboots remain consistent`() {
        val start = Instant.parse("2026-09-06T00:00:00Z").toEpochMilli()
        fun clock(day: Int, ms: Long) = ClockReading(start + day * 86_400_000L + ms,
            (day % 7) * 86_400_000L + 200_000 + ms, day / 7 + 1)
        val packages = listOf("x", "instagram", "tiktok")
        var s = UsageEngine.newSnapshot(clock(0, 0), "UTC")
        packages.forEach { s = UsageEngine.addApp(s, it, it, Limits(20, 5, 10), 0, clock(0, 0)) }
        s = UsageEngine.addGroup(s, "social", GroupPolicy("Sosyal", packages.toSet(), 30), clock(0, 0))
        s = UsageEngine.addSchedule(s, "study", SchedulePolicy("Çalışma", packages.toSet(), 127, 9 * 60, 18 * 60), clock(0, 0))
        repeat(180) { day ->
            s = UsageEngine.restore(s, clock(day, 8 * 3_600_000L))
            assertEquals("day $day", 0L, s.apps.sumOf { it.usedTodayMillis })
            assertEquals(BlockReason.SCHEDULED_BLOCK, UsageEngine.onForeground(s, "x", true, clock(day, 10 * 3_600_000L)).decision?.reason)
            repeat(6) { session ->
                val opening = 18 * 3_600_000L + session * 900_000L
                val opened = UsageEngine.onForeground(s, packages[session % 3], true, clock(day, opening))
                assertNull("day $day session $session", opened.decision)
                val ended = UsageEngine.tick(opened.snapshot, clock(day, opening + 300_000))
                assertEquals(if (session == 5) BlockReason.GROUP_DAILY_LIMIT else BlockReason.SESSION_LIMIT, ended.decision?.reason)
                s = UsageEngine.onForeground(ended.snapshot, null, false, clock(day, opening + 300_000)).snapshot
            }
            assertEquals(1_800_000L, s.apps.sumOf { it.usedTodayMillis })
            assertEquals(BlockReason.GROUP_DAILY_LIMIT, UsageEngine.onForeground(s, "x", true, clock(day, 23 * 3_600_000L)).decision?.reason)
            assertTrue(s.history.size <= UsageEngine.HISTORY_DAYS)
            assertTrue(s.history.all { it.apps.sumOf { app -> app.usedMillis } == 1_800_000L })
        }
        assertEquals(30, s.history.size)
    }
}
