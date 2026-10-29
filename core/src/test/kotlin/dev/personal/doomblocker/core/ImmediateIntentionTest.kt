package dev.personal.doomblocker.core

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ImmediateIntentionTest {
    private val wall = Instant.parse("2026-09-22T10:00:00Z").toEpochMilli()
    private fun at(ms: Long = 0) = ClockReading(wall + ms, 100_000 + ms, 1)
    private fun state() = UsageEngine.addApp(UsageEngine.newSnapshot(at(), "UTC"),
        "social", "Social", Limits(30, 5, 10), 300_000, at())

    @Test fun `changing modes immediately updates entry and preserves accrued usage`() {
        var s = UsageEngine.setIntentionalOpen(state(), "social", true, at())
        assertEquals(BlockReason.INTENTION_REQUIRED, UsageEngine.onForeground(s, "social", true, at()).decision?.reason)
        s = UsageEngine.startPlannedAccess(s, "social", 600_000, "Mesaj", at())
        s = UsageEngine.onForeground(s, "social", true, at()).snapshot
        s = UsageEngine.setIntentionalOpen(s, "social", false, at(120_000))
        val app = s.apps.single()
        assertFalse(app.intentionalOpenEnabled)
        assertNull(app.intentionalDisableAtWallMillis)
        assertNull(app.plannedUsageRemainingMillis)
        assertNull(app.plannedReason)
        assertEquals(420_000L, app.usedTodayMillis)
        assertEquals(120_000L, app.sessionUsedMillis)
        assertNull(UsageEngine.onForeground(s, "social", true, at(120_000)).decision)
        s = UsageEngine.setIntentionalOpen(s, "social", true, at(120_000))
        assertEquals(BlockReason.INTENTION_REQUIRED, UsageEngine.onForeground(s, "social", true, at(120_000)).decision?.reason)
        assertEquals(420_000L, s.apps.single().usedTodayMillis)
    }

    @Test fun `saving an unchanged mode keeps the running planned budget`() {
        var s = UsageEngine.setIntentionalOpen(state(), "social", true, at())
        s = UsageEngine.startPlannedAccess(s, "social", 600_000, "Mesaj", at())
        s = UsageEngine.onForeground(s, "social", true, at()).snapshot
        s = UsageEngine.setIntentionalOpen(s, "social", true, at(120_000))
        assertEquals(480_000L, s.apps.single().plannedUsageRemainingMillis)
        assertEquals(420_000L, s.apps.single().usedTodayMillis)
    }

    @Test fun `toggling never erases an active cooldown or daily usage`() {
        var s = UsageEngine.onForeground(state(), "social", true, at()).snapshot
        s = UsageEngine.tick(s, at(300_000)).snapshot
        val before = s.apps.single()
        assertNotNull(before.cooldown)
        s = UsageEngine.setIntentionalOpen(s, "social", true, at(300_000))
        s = UsageEngine.setIntentionalOpen(s, "social", false, at(300_000))
        assertEquals(before.cooldown, s.apps.single().cooldown)
        assertEquals(before.usedTodayMillis, s.apps.single().usedTodayMillis)
        assertEquals(BlockReason.COOLDOWN, UsageEngine.onForeground(s, "social", true, at(300_000)).decision?.reason)
    }

    @Test fun `restoring an old delayed disable honors the request now without clearing history`() {
        var s = UsageEngine.setIntentionalOpen(state(), "social", true, at())
        s = s.copy(apps = s.apps.map { it.copy(intentionalDisableAtWallMillis = wall + 86_400_000) })
        val restored = UsageEngine.restore(s, at(1_000))
        assertFalse(restored.apps.single().intentionalOpenEnabled)
        assertNull(restored.apps.single().intentionalDisableAtWallMillis)
        assertEquals(300_000L, restored.apps.single().usedTodayMillis)
        assertEquals(s.history, restored.history)
    }
}
