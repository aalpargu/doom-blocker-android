package dev.personal.doomblocker.service

import dev.personal.doomblocker.core.*
import org.junit.Assert.*
import org.junit.Test

class NotificationPolicyTest {
    @Test fun `warning uses selected remaining time instead of the dormant five minute rule`() {
        val now = ClockReading(1_800_000_000_000L, 100_000, 1)
        var s = UsageEngine.addApp(UsageEngine.newSnapshot(now, "UTC"), "x", "X", Limits(30, 5, 10), 0, now)
        s = UsageEngine.setIntentionalOpen(s, "x", true, now)
        s = UsageEngine.startPlannedAccess(s, "x", 600_000, "Mesaj", now)
        s = UsageEngine.onForeground(s, "x", true, now).snapshot
        val later = now.copy(wallMillis = now.wallMillis + 270_000, elapsedMillis = now.elapsedMillis + 270_000)
        assertEquals(330_000L, NotificationPolicy.sessionRemaining(s.apps.single(), UsageEngine.view(s, s.apps.single(), later)))
        val nearEnd = later.copy(wallMillis = now.wallMillis + 570_000, elapsedMillis = now.elapsedMillis + 570_000)
        assertEquals(30_000L, NotificationPolicy.sessionRemaining(s.apps.single(), UsageEngine.view(s, s.apps.single(), nearEnd)))
    }

    @Test fun `warning identity survives checkpoints short pauses and restore but changes for a fresh session`() {
        fun at(ms: Long) = ClockReading(1_800_000_000_000L + ms, 100_000 + ms, 1)
        var s = UsageEngine.addApp(UsageEngine.newSnapshot(at(0), "UTC"), "x", "X", Limits(), 0, at(0))
        s = UsageEngine.onForeground(s, "x", true, at(0)).snapshot
        val key = NotificationPolicy.sessionKey(s.apps.single(), s.quotaZoneId)
        repeat(12) { n ->
            s = UsageEngine.tick(s, at((n + 1) * 5_000L)).snapshot
            assertEquals(key, NotificationPolicy.sessionKey(s.apps.single(), s.quotaZoneId))
        }
        s = UsageEngine.onForeground(s, null, false, at(60_000)).snapshot
        s = UsageEngine.onForeground(s, "x", true, at(119_000)).snapshot
        assertEquals(key, NotificationPolicy.sessionKey(s.apps.single(), s.quotaZoneId))
        s = UsageEngine.restore(s, at(120_000))
        s = UsageEngine.onForeground(s, "x", true, at(121_000)).snapshot
        assertEquals(key, NotificationPolicy.sessionKey(s.apps.single(), s.quotaZoneId))
        s = UsageEngine.onForeground(s, null, false, at(121_000)).snapshot
        s = UsageEngine.onForeground(s, "x", true, at(181_000)).snapshot
        assertNotEquals(key, NotificationPolicy.sessionKey(s.apps.single(), s.quotaZoneId))
    }

    @Test fun `warning identity survives midnight in one active session`() {
        val start = java.time.Instant.parse("2026-09-17T23:58:00Z").toEpochMilli()
        fun at(ms: Long) = ClockReading(start + ms, 100_000 + ms, 1)
        var s = UsageEngine.addApp(UsageEngine.newSnapshot(at(0), "UTC"), "x", "X", Limits(30, 20, 10), 0, at(0))
        s = UsageEngine.onForeground(s, "x", true, at(0)).snapshot
        val day = s.activeEpochDay
        val key = NotificationPolicy.sessionKey(s.apps.single(), s.quotaZoneId)
        s = UsageEngine.tick(s, at(180_000)).snapshot
        assertEquals(day + 1, s.activeEpochDay)
        assertEquals(key, NotificationPolicy.sessionKey(s.apps.single(), s.quotaZoneId))
    }

    @Test fun `each selected planned session has a new warning identity`() {
        fun at(ms: Long) = ClockReading(1_800_000_000_000L + ms, 100_000 + ms, 1)
        var s = UsageEngine.addApp(UsageEngine.newSnapshot(at(0), "UTC"), "x", "X", Limits(30, 5, 10), 0, at(0))
        s = UsageEngine.setIntentionalOpen(s, "x", true, at(0))
        s = UsageEngine.startPlannedAccess(s, "x", 300_000, "İlk", at(0))
        val firstKey = NotificationPolicy.sessionKey(s.apps.single(), s.quotaZoneId)
        s = UsageEngine.onForeground(s, "x", true, at(0)).snapshot
        assertEquals(firstKey, NotificationPolicy.sessionKey(s.apps.single(), s.quotaZoneId))
        s = UsageEngine.tick(s, at(300_000)).snapshot
        s = UsageEngine.startPlannedAccess(s, "x", 300_000, "İkinci", at(900_000))
        assertNotEquals(firstKey, NotificationPolicy.sessionKey(s.apps.single(), s.quotaZoneId))
    }

    @Test fun `selected session keeps one warning identity across a long pause`() {
        fun at(ms: Long) = ClockReading(1_800_000_000_000L + ms, 100_000 + ms, 1)
        var s = UsageEngine.addApp(UsageEngine.newSnapshot(at(0), "UTC"), "x", "X", Limits(30, 5, 10), 0, at(0))
        s = UsageEngine.setIntentionalOpen(s, "x", true, at(0))
        s = UsageEngine.startPlannedAccess(s, "x", 300_000, "Mesaj", at(0))
        s = UsageEngine.onForeground(s, "x", true, at(0)).snapshot
        s = UsageEngine.onForeground(s, null, false, at(240_000)).snapshot
        val key = NotificationPolicy.sessionKey(s.apps.single(), s.quotaZoneId)
        s = UsageEngine.onForeground(s, "x", true, at(840_000)).snapshot
        assertEquals(60_000L, s.apps.single().plannedUsageRemainingMillis)
        assertEquals(key, NotificationPolicy.sessionKey(s.apps.single(), s.quotaZoneId))
    }

    @Test fun `older saved session keeps its already sent warning key after upgrade`() {
        fun at(ms: Long) = ClockReading(1_800_000_000_000L + ms, 100_000 + ms, 1)
        var s = UsageEngine.addApp(UsageEngine.newSnapshot(at(0), "UTC"), "x", "X", Limits(), 0, at(0))
        s = UsageEngine.onForeground(s, "x", true, at(0)).snapshot
        s = UsageEngine.tick(s, at(245_000)).snapshot
        val app = s.apps.single()
        val foreground = app.foregroundSince!!
        val legacyKey = "${s.activeEpochDay}:${app.packageName}:session:" +
            "${foreground.bootCount}:${foreground.wallMillis - app.sessionUsedMillis}"
        val olderRecord = s.copy(apps = listOf(app.copy(sessionStartedAt = null)))
        val restored = UsageEngine.restore(olderRecord, at(245_000))
        assertEquals(legacyKey, NotificationPolicy.sessionKey(restored.apps.single(), restored.quotaZoneId))
    }

    @Test fun `recovery gives a new identity to an observed session after a long gap`() {
        fun at(ms: Long) = ClockReading(1_800_000_000_000L + ms, 100_000 + ms, 1)
        var s = UsageEngine.addApp(UsageEngine.newSnapshot(at(0), "UTC"), "x", "X", Limits(), 0, at(0))
        s = UsageEngine.onForeground(s, "x", true, at(0)).snapshot
        val firstKey = NotificationPolicy.sessionKey(s.apps.single(), s.quotaZoneId)
        s = UsageEngine.onForeground(s, null, false, at(60_000)).snapshot
        val recovered = UsageEngine.recoverUsage(s, listOf(
            UsageInterval("x", at(180_000).wallMillis, at(240_000).wallMillis),
        ), at(60_000).wallMillis, null, null, at(250_000))
        assertNotEquals(firstKey, NotificationPolicy.sessionKey(recovered.apps.single(), recovered.quotaZoneId))
    }
}
