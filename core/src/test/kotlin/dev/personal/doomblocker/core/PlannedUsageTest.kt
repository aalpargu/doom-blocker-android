package dev.personal.doomblocker.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PlannedUsageTest {
    private val wall = Instant.parse("2026-09-15T10:00:00Z").toEpochMilli()
    private fun at(ms: Long = 0, boot: Int = 4, elapsed: Long = 200_000 + ms) = ClockReading(wall + ms, elapsed, boot)
    private fun state(limits: Limits = Limits(30, 10, 1)): ProtectionSnapshot {
        val added = UsageEngine.addApp(UsageEngine.newSnapshot(at(), "UTC"), "tiktok", "TikTok", limits, 0, at())
        val enabled = UsageEngine.setIntentionalOpen(added, "tiktok", true, at())
        return UsageEngine.startPlannedAccess(enabled, "tiktok", 300_000, "Mesaja yanıt", at())
    }
    private fun remaining(s: ProtectionSnapshot, now: ClockReading) = UsageEngine.view(s, s.apps.first(), now).plannedAccessRemainingMillis

    @Test fun `30 seconds use then 4 minutes locked leaves 4 minutes 30 seconds`() {
        var s = UsageEngine.onForeground(state(), "tiktok", true, at()).snapshot
        s = UsageEngine.onForeground(s, "tiktok", false, at(30_000)).snapshot
        s = UsageEngine.tick(s, at(270_000)).snapshot
        assertEquals(270_000L, remaining(s, at(270_000)))
        s = UsageEngine.onForeground(s, "tiktok", true, at(270_000)).snapshot
        val thirtyMore = UsageEngine.tick(s, at(300_000))
        assertNull(thirtyMore.decision)
        assertEquals(240_000L, remaining(thirtyMore.snapshot, at(300_000)))
        assertEquals(60_000L, thirtyMore.snapshot.apps.first().usedTodayMillis)
        val expired = UsageEngine.tick(thirtyMore.snapshot, at(540_000))
        assertEquals(BlockReason.PLANNED_SESSION_END, expired.decision?.reason)
        assertEquals(300_000L, expired.snapshot.apps.first().usedTodayMillis)
    }

    @Test fun `other app and repeated grace breaks do not consume or replenish the grant`() {
        var s = state()
        var t = 0L
        repeat(10) {
            s = UsageEngine.onForeground(s, "tiktok", true, at(t)).snapshot
            t += 10_000
            s = UsageEngine.onForeground(s, "messages", true, at(t)).snapshot
            t += 20_000
            s = UsageEngine.tick(s, at(t)).snapshot
        }
        assertEquals(200_000L, remaining(s, at(t)))
        assertEquals(100_000L, s.apps.first().usedTodayMillis)
        assertThrows(IllegalArgumentException::class.java) {
            UsageEngine.startPlannedAccess(s, "tiktok", 300_000, "Tekrar", at(t))
        }
    }

    @Test fun `grant does not age before target is launched`() {
        val s = UsageEngine.tick(state(), at(600_000)).snapshot
        assertEquals(300_000L, remaining(s, at(600_000)))
        assertNull(UsageEngine.onForeground(s, "tiktok", true, at(600_000)).decision)
    }

    @Test fun `view subtracts live usage without mutating stored budget`() {
        val s = UsageEngine.onForeground(state(), "tiktok", true, at()).snapshot
        assertEquals(280_000L, remaining(s, at(20_000)))
        assertEquals(300_000L, s.apps.first().plannedUsageRemainingMillis)
        val persisted = UsageEngine.tick(s, at(20_000)).snapshot
        assertEquals(280_000L, remaining(persisted, at(20_000)))
    }

    @Test fun `existing cooldown from an older version retains unused planned usage`() {
        val opened = UsageEngine.onForeground(state(Limits(30, 1, 1)), "tiktok", true, at()).snapshot
        val saved = UsageEngine.tick(opened, at(60_000)).snapshot.let { s -> s.copy(apps = s.apps.map {
            it.copy(foregroundSince = null, sessionUsedMillis = 0, cooldown = TimedBlock(TimeAnchor.from(at(60_000)), 60_000))
        }) }
        val restored = UsageEngine.restore(saved, at(90_000))
        assertEquals(240_000L, remaining(restored, at(90_000)))
        assertEquals(BlockReason.COOLDOWN, UsageEngine.onForeground(restored, "tiktok", true, at(90_000)).decision?.reason)
        assertNull(UsageEngine.onForeground(restored, "tiktok", true, at(120_000)).decision)
    }

    @Test fun `reboot and recovered measured usage consume budget once`() {
        val opened = UsageEngine.onForeground(state(), "tiktok", true, at()).snapshot
        val saved = UsageEngine.tick(opened, at(30_000)).snapshot
        val now = at(600_000, boot = 5, elapsed = 10_000)
        var restored = UsageEngine.restore(saved, now)
        assertEquals(270_000L, remaining(restored, now))
        val intervals = listOf(UsageInterval("tiktok", wall + 30_000, wall + 50_000))
        restored = UsageEngine.recoverUsage(restored, intervals, wall + 30_000, "tiktok", null, now)
        assertEquals(250_000L, remaining(restored, now))
        assertEquals(50_000L, restored.apps.first().usedTodayMillis)
        val replayed = UsageEngine.recoverUsage(restored, intervals, wall + 30_000, "tiktok", null, now)
        assertEquals(250_000L, remaining(replayed, now))
        assertEquals(50_000L, replayed.apps.first().usedTodayMillis)
    }

    @Test fun `2 point 8 upgrade keeps 10 and 5 minutes daily balance and all other state`() {
        var stored = UsageEngine.newSnapshot(at(), "UTC")
        stored = UsageEngine.addApp(stored, "tiktok", "TikTok", Limits(30, 5, 10), 1_200_000, at())
        stored = UsageEngine.addApp(stored, "instagram", "Instagram", Limits(30, 5, 10), 1_500_000, at())
        stored = UsageEngine.enableStrictMode(stored, at())
        stored = UsageEngine.startFocusSession(stored, setOf("instagram"), 1_800_000, at())
        stored = stored.copy(clockAnchor = TimeAnchor.from(at(30_000)), apps = stored.apps.map {
            if (it.packageName == "tiktok") it.copy(intentionalOpenEnabled = true,
                plannedAccess = TimedBlock(TimeAnchor.from(at()), 300_000), plannedReason = "Mesaj") else it
        })
        val migrated = UsageEngine.migratePlannedUsage(stored)
        assertEquals(stored.copy(apps = stored.apps.map {
            if (it.packageName == "tiktok") it.copy(plannedAccess = null, plannedUsageRemainingMillis = 270_000) else it
        }), migrated)
        val now = at(270_000)
        val restored = UsageEngine.restore(stored, now)
        assertEquals(listOf(600_000L, 300_000L), restored.apps.map { it.limits.dailyMillis - it.usedTodayMillis })
        assertEquals(270_000L, remaining(restored, now))
        assertEquals(1_530_000L, restored.focusSession?.block?.remainingMillis(now))
        assertEquals(migrated, UsageEngine.migratePlannedUsage(migrated))
    }

    @Test fun `legacy expired grant is not refilled on upgrade`() {
        val legacy = state().let { s -> s.copy(clockAnchor = TimeAnchor.from(at(600_000)), apps = s.apps.map {
            it.copy(plannedUsageRemainingMillis = null, plannedAccess = TimedBlock(TimeAnchor.from(at()), 300_000))
        }) }
        val restored = UsageEngine.restore(legacy, at(700_000))
        assertEquals(0L, remaining(restored, at(700_000)))
        assertEquals(BlockReason.INTENTION_REQUIRED, UsageEngine.onForeground(restored, "tiktok", true, at(700_000)).decision?.reason)
    }

    @Test fun `midnight splits daily counters without refilling planned usage`() {
        val start = 14 * 3_600_000L - 30_000
        var s = UsageEngine.startPlannedAccess(UsageEngine.setIntentionalOpen(
            UsageEngine.addApp(UsageEngine.newSnapshot(at(start), "UTC"), "tiktok", "TikTok", Limits(30, 10, 1), 0, at(start)),
            "tiktok", true, at(start)), "tiktok", 300_000, "Mesaj", at(start))
        s = UsageEngine.onForeground(s, "tiktok", true, at(start)).snapshot
        s = UsageEngine.tick(s, at(start + 60_000)).snapshot
        assertEquals(240_000L, remaining(s, at(start + 60_000)))
        assertEquals(30_000L, s.apps.first().usedTodayMillis)
        assertEquals(30_000L, s.history.last().apps.first().usedMillis)
    }
}
