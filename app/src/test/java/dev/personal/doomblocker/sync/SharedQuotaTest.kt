package dev.personal.doomblocker.sync

import dev.personal.doomblocker.core.*
import org.junit.Assert.*
import org.junit.Test

class SharedQuotaTest {
    @Test fun `fifteen minutes on phone plus fifteen on tablet exhaust thirty minutes`() {
        val app = SharedApp("x", "X", 1_800_000, 1_800_000, 900_000, 0)
        assertEquals(0L, SharedQuota.remaining(app, 900_000))
    }
    @Test fun `local usage since last upload is added once and stale local counters cannot refund shared usage`() {
        val app = SharedApp("x", "X", 1_800_000, 900_000, 300_000, 0)
        assertEquals(895_000L, SharedQuota.remaining(app, 305_000))
        assertEquals(900_000L, SharedQuota.remaining(app, 200_000))
    }
    @Test fun `shared freshness cannot cross midnight reboot or clock rewind`() {
        val now = ClockReading(1_800_000_000_000, 100_000, 1)
        val s = UsageEngine.newSnapshot(now, "UTC")
        val shared = SharedQuotaState(linked = true, day = s.activeEpochDay, zone = "UTC",
            receivedElapsed = 100_000, receivedBoot = 1)
        assertTrue(SharedQuota.fresh(shared, s, now))
        assertFalse(SharedQuota.fresh(shared, s, now.copy(bootCount = 2)))
        assertFalse(SharedQuota.fresh(shared, s, now.copy(elapsedMillis = 99_999)))
        assertFalse(SharedQuota.fresh(shared, s, now.copy(elapsedMillis = 115_001)))
        assertFalse(SharedQuota.fresh(shared, s.copy(activeEpochDay = s.activeEpochDay + 1), now))
    }
    @Test fun `remote cooldown counts down without restarting on local clock change`() {
        val state = SharedQuotaState(receivedElapsed = 100_000)
        val app = SharedApp("x", "X", 1_800_000, 0, 0, 600_000)
        assertEquals(540_000L, SharedQuota.cooldown(app, state, ClockReading(0, 160_000, 1)))
    }

    @Test fun `network transit time never shortens shared cooldown`() {
        val state = SharedQuotaState(receivedElapsed = 100_000, cooldownReceivedElapsed = 104_000)
        val app = SharedApp("x", "X", 1_800_000, 0, 0, 600_000)
        assertEquals(599_000L, SharedQuota.cooldown(app, state, ClockReading(0, 105_000, 1)))
    }

    @Test fun `gate choices and existing grants are capped to combined remaining quota`() {
        val now = ClockReading(1_800_000_000_000, 100_000, 1)
        val snapshot = UsageEngine.addApp(UsageEngine.newSnapshot(now, "UTC"), "test.app", "Test",
            AppPolicy(Limits(30, 5, 10)), 600_000, now)
        val state = SharedQuotaState(linked = true, day = snapshot.activeEpochDay, zone = "UTC",
            receivedElapsed = now.elapsedMillis, receivedBoot = 1,
            apps = listOf(SharedApp("test.app", "Test", 1_800_000, 1_680_000, 600_000, 0)))
        val entry = PlannedEntry(1_200_000, 300_000, 60_000, 0, null, null)
        val capped = SharedQuota.capEntry(entry, state, snapshot, "test.app", now)!!
        assertEquals(120_000L, capped.availableMillis)
        assertEquals(120_000L, capped.existingMillis)
        assertEquals(0L, SharedQuota.capEntry(entry, state, snapshot, "test.app",
            now.copy(elapsedMillis = 120_000))!!.availableMillis)
        assertEquals(entry, SharedQuota.capEntry(entry, state.copy(linked = false), snapshot, "test.app", now))
    }

    @Test fun `travel window bypasses linked quota checks only until its exact end`() {
        val before = ClockReading(1_800_000_000_000L, 100_000L, 1)
        val start = before.wallMillis + 1_000L
        val end = start + 5_000L
        val snapshot = UsageEngine.addApp(UsageEngine.newSnapshot(before, "UTC"), "test.app", "Test",
            Limits(30, 5, 10), 0, before).copy(travelException = TravelException(start, end))
        val offline = SharedQuotaState(linked = true)
        val entry = PlannedEntry(1_200_000L, 300_000L, 60_000L, 0, null, null)
        fun at(wallMillis: Long) = before.copy(wallMillis = wallMillis,
            elapsedMillis = before.elapsedMillis + wallMillis - before.wallMillis)

        assertNotNull(SharedQuota.blockMessage(offline, snapshot, "test.app", at(start - 1)))
        assertNull(SharedQuota.blockMessage(offline, snapshot, "test.app", at(start)))
        assertEquals(entry, SharedQuota.capEntry(entry, offline, snapshot, "test.app", at(start)))
        assertNull(SharedQuota.blockMessage(offline, snapshot, "test.app", at(end - 1)))
        assertNotNull(SharedQuota.blockMessage(offline, snapshot, "test.app", at(end)))
        assertEquals(0L, SharedQuota.capEntry(entry, offline, snapshot, "test.app", at(end))!!.availableMillis)
    }
}
