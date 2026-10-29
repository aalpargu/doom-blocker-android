package dev.personal.doomblocker.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class SelectedSessionTest {
    private val wall = Instant.parse("2026-09-17T10:00:00Z").toEpochMilli()
    private fun at(ms: Long = 0) = ClockReading(wall + ms, 100_000 + ms, 1)
    private fun state(used: Long = 0, enabled: Boolean = true): ProtectionSnapshot {
        val s = UsageEngine.addApp(UsageEngine.newSnapshot(at(), "UTC"), "tiktok", "TikTok", Limits(30, 5, 10), used, at())
        return if (enabled) UsageEngine.setIntentionalOpen(s, "tiktok", true, at()) else s
    }
    private fun grant(s: ProtectionSnapshot = state(), ms: Long = 600_000) =
        UsageEngine.startPlannedAccess(s, "tiktok", ms, "Mesaja yanıt", at())
    private fun enter(s: ProtectionSnapshot, ms: Long = 0) = UsageEngine.onForeground(s, "tiktok", true, at(ms))

    @Test fun `ten selected minutes survive five and require a break at ten`() {
        val five = UsageEngine.tick(enter(grant()).snapshot, at(300_000))
        assertNull(five.decision)
        assertEquals(300_000L, five.snapshot.apps.single().plannedUsageRemainingMillis)
        assertEquals(5_000L, five.nextWakeupMillis)
        val ended = UsageEngine.tick(five.snapshot, at(600_000))
        assertEquals(BlockReason.PLANNED_SESSION_END, ended.decision?.reason)
        assertEquals(600_000L, ended.decision?.cooldownRemainingMillis)
        assertNull(ended.snapshot.apps.single().plannedUsageRemainingMillis)
        assertEquals(1, ended.snapshot.apps.single().limitEventsToday)
        assertEquals(BlockReason.COOLDOWN, enter(ended.snapshot, 601_000).decision?.reason)
        assertThrows(IllegalArgumentException::class.java) {
            UsageEngine.startPlannedAccess(ended.snapshot, "tiktok", 600_000, "Tekrar", at(601_000))
        }
        val ready = enter(ended.snapshot, 1_200_000)
        assertEquals(BlockReason.INTENTION_REQUIRED, ready.decision?.reason)
        val newGrant = UsageEngine.startPlannedAccess(ready.snapshot, "tiktok", 60_000, "Yeni iş", at(1_200_000))
        assertEquals(60_000L, newGrant.apps.single().plannedUsageRemainingMillis)
        assertEquals(600_000L, newGrant.apps.single().usedTodayMillis)
    }

    @Test fun `fixed five minute limit still works when intentional entry is off`() {
        val ended = UsageEngine.tick(enter(state(enabled = false)).snapshot, at(300_000))
        assertEquals(BlockReason.SESSION_LIMIT, ended.decision?.reason)
        assertEquals(600_000L, ended.snapshot.apps.single().cooldown?.remainingMillis(at(300_000)))
    }

    @Test fun `six minutes daily balance caps a ten minute request and enforces daily first`() {
        val s = state(used = 24 * 60_000L)
        assertEquals(360_000L, UsageEngine.plannedEntry(s, "tiktok", at())?.availableMillis)
        val g = grant(s)
        assertEquals(360_000L, g.apps.single().plannedUsageRemainingMillis)
        val ended = UsageEngine.tick(enter(g).snapshot, at(360_000))
        assertEquals(BlockReason.DAILY_LIMIT, ended.decision?.reason)
        assertNotNull(ended.snapshot.apps.single().cooldown)
        assertEquals(1, ended.snapshot.apps.single().limitEventsToday)
    }

    @Test fun `shared quota is also used to cap the offer and the actual grant`() {
        var s = UsageEngine.addApp(state(), "x", "X", Limits(30, 5, 10), 25 * 60_000L + 30_000, at())
        s = UsageEngine.addGroup(s, "social", GroupPolicy("Sosyal", setOf("tiktok", "x"), 30), at())
        assertEquals(270_000L, UsageEngine.plannedEntry(s, "tiktok", at())?.availableMillis)
        val g = grant(s)
        assertEquals(270_000L, g.apps.first { it.packageName == "tiktok" }.plannedUsageRemainingMillis)
        assertEquals(BlockReason.GROUP_DAILY_LIMIT, UsageEngine.tick(enter(g).snapshot, at(270_000)).decision?.reason)
    }

    @Test fun `editing other rules after five minutes does not trigger the dormant fixed limit`() {
        val six = UsageEngine.tick(enter(grant()).snapshot, at(360_000)).snapshot
        val edited = UsageEngine.updatePolicy(six, "tiktok", AppPolicy(Limits(25, 5, 10)), at(360_000))
        assertNull(edited.apps.single().cooldown)
        assertNotNull(edited.apps.single().foregroundSince)
        assertEquals(240_000L, edited.apps.single().plannedUsageRemainingMillis)
    }

    @Test fun `recovered six minutes do not create a five minute break`() {
        val recovered = UsageEngine.recoverUsage(grant(), listOf(UsageInterval("tiktok", wall, wall + 360_000)),
            wall, "tiktok", "tiktok", at(360_000))
        assertEquals(240_000L, recovered.apps.single().plannedUsageRemainingMillis)
        assertEquals(0, recovered.apps.single().limitEventsToday)
        assertNull(recovered.apps.single().cooldown)
        assertNull(enter(recovered, 360_000).decision)
    }

    @Test fun `recovered completed selection starts one break and replay does not extend it`() {
        val spans = listOf(UsageInterval("tiktok", wall, wall + 600_000))
        val recovered = UsageEngine.recoverUsage(grant(), spans, wall, "tiktok", null, at(660_000))
        assertNull(recovered.apps.single().plannedUsageRemainingMillis)
        assertEquals(540_000L, recovered.apps.single().cooldown?.remainingMillis(at(660_000)))
        assertEquals(1, recovered.apps.single().limitEventsToday)
        val replay = UsageEngine.recoverUsage(recovered, spans, wall, null, null, at(720_000))
        assertEquals(480_000L, replay.apps.single().cooldown?.remainingMillis(at(720_000)))
        assertEquals(1, replay.apps.single().limitEventsToday)
        assertEquals(600_000L, replay.apps.single().usedTodayMillis)
    }

    @Test fun `old recovered completed grant does not impose another break now`() {
        val recovered = UsageEngine.recoverUsage(grant(), listOf(UsageInterval("tiktok", wall, wall + 600_000)),
            wall, "tiktok", null, at(1_800_000))
        assertNull(recovered.apps.single().cooldown)
        assertEquals(BlockReason.INTENTION_REQUIRED, enter(recovered, 1_800_000).decision?.reason)
    }

    @Test fun `app switches pause the selected budget even beyond the fixed limit`() {
        var s = enter(grant()).snapshot
        s = UsageEngine.onForeground(s, "messages", true, at(360_000)).snapshot
        val returned = enter(s, 960_000)
        assertNull(returned.decision)
        assertEquals(240_000L, returned.snapshot.apps.single().plannedUsageRemainingMillis)
        assertEquals(BlockReason.PLANNED_SESSION_END, UsageEngine.tick(returned.snapshot, at(1_200_000)).decision?.reason)
    }

    @Test fun `an existing grant cannot be replaced even after the fixed limit`() {
        val s = UsageEngine.tick(enter(grant()).snapshot, at(360_000)).snapshot
        assertThrows(IllegalArgumentException::class.java) {
            UsageEngine.startPlannedAccess(s, "tiktok", 900_000, "Uzat", at(360_000))
        }
        val entry = UsageEngine.plannedEntry(s, "tiktok", at(370_000))!!
        assertEquals(230_000L, entry.existingMillis)
        assertEquals(1_430_000L, entry.availableMillis)
    }

    @Test fun `schedule interrupts the chosen budget and remaining time resumes afterwards`() {
        var s = UsageEngine.addSchedule(state(), "study", SchedulePolicy("Çalışma", setOf("tiktok"), 127, 605, 610), at())
        val offer = UsageEngine.plannedEntry(s, "tiktok", at())!!
        assertEquals(wall + 300_000, offer.nextSchedule?.startsAtWallMillis)
        s = enter(grant(s)).snapshot
        val stopped = UsageEngine.tick(s, at(300_000))
        assertEquals(BlockReason.SCHEDULED_BLOCK, stopped.decision?.reason)
        assertEquals(300_000L, stopped.snapshot.apps.single().plannedUsageRemainingMillis)
        assertEquals(BlockReason.SCHEDULED_BLOCK, UsageEngine.plannedEntry(stopped.snapshot, "tiktok", at(301_000))?.block?.reason)
        val returned = enter(stopped.snapshot, 600_000)
        assertNull(returned.decision)
        assertEquals(BlockReason.PLANNED_SESSION_END, UsageEngine.tick(returned.snapshot, at(900_000)).decision?.reason)
    }

    @Test fun `upcoming overnight schedule belongs to its starting weekday`() {
        val schedule = SchedulePolicy("Gece", setOf("tiktok"), 1, 23 * 60, 60)
        val tuesday = Instant.parse("2026-09-15T00:30:00Z").toEpochMilli()
        assertTrue(schedule.isActive(tuesday, "UTC"))
        assertEquals(Instant.parse("2026-09-21T23:00:00Z").toEpochMilli(), schedule.nextStartWallMillis(tuesday, "UTC"))
    }

    @Test fun `schedule preview uses deferred edits and suppresses removed schedules`() {
        val original = SchedulePolicy("Eski", setOf("tiktok"), 127, 660, 720)
        val s = state()
        val changed = s.copy(schedules = listOf(ProtectedSchedule("study", original,
            pendingPolicy = PendingSchedulePolicy(original.copy(label = "Yeni", startMinuteOfDay = 690),
                s.activeEpochDay, wall + 1_800_000))))
        assertEquals(UpcomingSchedule("Yeni", wall + 5_400_000), UsageEngine.plannedEntry(changed, "tiktok", at())?.nextSchedule)
        val removed = changed.copy(schedules = changed.schedules.map {
            it.copy(pendingRemoval = PendingRemoval(s.activeEpochDay, wall + 900_000))
        })
        assertNull(UsageEngine.plannedEntry(removed, "tiktok", at())?.nextSchedule)
    }

    @Test fun `pending rule becoming active mid-window is advertised at its activation`() {
        val s = state()
        val current = SchedulePolicy("Eski", setOf("tiktok"), 127, 660, 720)
        val future = current.copy(label = "Yeni", startMinuteOfDay = 600)
        val changed = s.copy(schedules = listOf(ProtectedSchedule("study", current,
            pendingPolicy = PendingSchedulePolicy(future, s.activeEpochDay, wall + 1_800_000))))
        assertEquals(UpcomingSchedule("Yeni", wall + 1_800_000), UsageEngine.plannedEntry(changed, "tiktok", at())?.nextSchedule)
    }

    @Test fun `recovered grant spent before midnight leaves its real break after midnight`() {
        val start = 14 * 3_600_000L - 120_000
        var s = UsageEngine.addApp(UsageEngine.newSnapshot(at(start), "UTC"), "tiktok", "TikTok", Limits(30, 5, 10), 0, at(start))
        s = UsageEngine.setIntentionalOpen(s, "tiktok", true, at(start))
        s = UsageEngine.startPlannedAccess(s, "tiktok", 60_000, "Mesaj", at(start))
        val now = at(start + 180_000)
        s = UsageEngine.restore(s, now)
        s = UsageEngine.recoverUsage(s, listOf(UsageInterval("tiktok", wall + start, wall + start + 60_000)),
            wall + start, "tiktok", null, now)
        assertEquals(0L, s.apps.single().usedTodayMillis)
        assertEquals(60_000L, s.history.last().apps.single().usedMillis)
        assertEquals(480_000L, s.apps.single().cooldown?.remainingMillis(now))
        assertNull(s.apps.single().plannedUsageRemainingMillis)
        assertEquals(0, s.apps.single().limitEventsToday)
    }
}
