package dev.personal.doomblocker.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ProtectionTest {
    private val wall = Instant.parse("2026-09-06T10:00:00Z").toEpochMilli()
    private fun at(afterMillis: Long = 0, wallShift: Long = afterMillis, boot: Int = 4) =
        ClockReading(wall + wallShift, 200_000 + afterMillis, boot)
    private fun snapshot(limits: Limits = Limits(20, 5, 10), used: Long = 0): ProtectionSnapshot =
        UsageEngine.addApp(
            UsageEngine.newSnapshot(at(), "UTC"), "social.app", "Sosyal", limits, used, at(),
        )
    private fun app(state: ProtectionSnapshot) = state.apps.single()
    private fun multiSnapshot(vararg packages: Pair<String, Long>): ProtectionSnapshot {
        var state = UsageEngine.newSnapshot(at(), "UTC")
        packages.forEach { (packageName, used) ->
            state = UsageEngine.addApp(state, packageName, packageName, Limits(120, 60, 10), used, at())
        }
        return state
    }

    @Test fun `limits reject impossible sessions`() {
        assertThrows(IllegalArgumentException::class.java) { Limits(10, 11, 5) }
        assertThrows(IllegalArgumentException::class.java) { Limits(0, 0, 0) }
    }

    @Test fun `adding current usage can immediately latch the daily limit`() {
        val state = snapshot(Limits(10, 5, 10), used = 10 * 60_000L)
        assertTrue(app(state).dailyBlocked)
    }

    @Test fun `timed focus blocks only selected apps and expires`() {
        var state = multiSnapshot("social.app" to 0L, "music.app" to 0L)
        state = UsageEngine.startFocusSession(state, setOf("social.app"), 30 * 60_000L, at())

        val blocked = UsageEngine.onForeground(state, "social.app", true, at(1_000))
        assertEquals(BlockReason.FOCUS_SESSION, blocked.decision?.reason)
        assertEquals(30 * 60_000L - 1_000, UsageEngine.view(blocked.snapshot, blocked.snapshot.apps.first(), at(1_000)).focusRemainingMillis)

        val music = UsageEngine.onForeground(blocked.snapshot, "music.app", true, at(2_000))
        assertNull(music.decision)
        val expired = UsageEngine.onForeground(music.snapshot, "social.app", true, at(30 * 60_000L))
        assertNull(expired.decision)
        assertNull(expired.snapshot.focusSession)
    }

    @Test fun `active focus cannot be shortened or lose protected apps`() {
        var state = multiSnapshot("social.app" to 0L, "music.app" to 0L)
        state = UsageEngine.startFocusSession(state, setOf("social.app", "music.app"), 60 * 60_000L, at())

        state = UsageEngine.startFocusSession(state, setOf("social.app"), 15 * 60_000L, at(10 * 60_000L))

        assertEquals(setOf("social.app", "music.app"), state.focusSession?.packageNames)
        assertEquals(50 * 60_000L, state.focusSession?.block?.remainingMillis(at(10 * 60_000L)))
        assertEquals(
            BlockReason.FOCUS_SESSION,
            UsageEngine.onForeground(state, "music.app", true, at(10 * 60_000L + 1)).decision?.reason,
        )
    }

    @Test fun `active focus can add apps and extend from the current moment`() {
        var state = multiSnapshot("social.app" to 0L, "music.app" to 0L)
        state = UsageEngine.startFocusSession(state, setOf("social.app"), 15 * 60_000L, at())

        state = UsageEngine.startFocusSession(state, setOf("music.app"), 60 * 60_000L, at(5 * 60_000L))

        assertEquals(setOf("social.app", "music.app"), state.focusSession?.packageNames)
        assertEquals(60 * 60_000L, state.focusSession?.block?.remainingMillis(at(5 * 60_000L)))
    }

    @Test fun `intentional opening requires a reason and planned duration`() {
        var state = UsageEngine.setIntentionalOpen(snapshot(Limits(20, 10, 10)), "social.app", true, at())
        val gated = UsageEngine.onForeground(state, "social.app", true, at(1_000))
        assertEquals(BlockReason.INTENTION_REQUIRED, gated.decision?.reason)

        state = UsageEngine.startPlannedAccess(gated.snapshot, "social.app", 5 * 60_000L, "Mesaj yanıtla", at(2_000))
        val opened = UsageEngine.onForeground(state, "social.app", true, at(3_000))
        assertNull(opened.decision)
        val beyondNormalSession = UsageEngine.tick(opened.snapshot, at(2 * 60_000L))
        assertNull(beyondNormalSession.decision)

        val ended = UsageEngine.tick(beyondNormalSession.snapshot, at(5 * 60_000L + 3_000))
        assertEquals(BlockReason.PLANNED_SESSION_END, ended.decision?.reason)
        assertNull(app(ended.snapshot).foregroundSince)
        assertNull(app(ended.snapshot).plannedAccess)
        assertNull(app(ended.snapshot).plannedUsageRemainingMillis)
    }

    @Test fun `daily limit still wins during planned access`() {
        var state = UsageEngine.setIntentionalOpen(snapshot(Limits(1, 1, 3)), "social.app", true, at())
        state = UsageEngine.startPlannedAccess(state, "social.app", 5 * 60_000L, "Kısa iş", at())
        state = UsageEngine.onForeground(state, "social.app", true, at()).snapshot
        val result = UsageEngine.tick(state, at(60_000))
        assertEquals(BlockReason.DAILY_LIMIT, result.decision?.reason)
    }

    @Test fun `foreground time is checkpointed and schedules another check`() {
        val started = UsageEngine.onForeground(snapshot(), "social.app", true, at()).snapshot
        val result = UsageEngine.tick(started, at(4_000))
        assertEquals(4_000, app(result.snapshot).usedTodayMillis)
        assertEquals(4_000, app(result.snapshot).sessionUsedMillis)
        assertEquals(5_000L, result.nextWakeupMillis)
    }

    @Test fun `screen off closes usage instead of counting locked time`() {
        val started = UsageEngine.onForeground(snapshot(), "social.app", true, at()).snapshot
        val stopped = UsageEngine.onForeground(started, "social.app", false, at(15_000)).snapshot
        val later = UsageEngine.tick(stopped, at(120_000)).snapshot
        assertEquals(15_000, app(later).usedTodayMillis)
        assertNull(app(later).foregroundSince)
    }

    @Test fun `brief app switch keeps the current session`() {
        val started = UsageEngine.onForeground(snapshot(), "social.app", true, at()).snapshot
        val home = UsageEngine.onForeground(started, "launcher", true, at(30_000)).snapshot
        val returned = UsageEngine.onForeground(home, "social.app", true, at(89_999)).snapshot
        assertEquals(30_000, app(returned).sessionUsedMillis)
    }

    @Test fun `sixty seconds away starts a fresh session`() {
        val started = UsageEngine.onForeground(snapshot(), "social.app", true, at()).snapshot
        val home = UsageEngine.onForeground(started, "launcher", true, at(30_000)).snapshot
        val returned = UsageEngine.onForeground(home, "social.app", true, at(90_000)).snapshot
        assertEquals(0, app(returned).sessionUsedMillis)
    }

    @Test fun `session limit starts cooldown and asks Android to leave`() {
        val state = snapshot(Limits(10, 1, 3))
        val started = UsageEngine.onForeground(state, "social.app", true, at()).snapshot
        val result = UsageEngine.tick(started, at(60_000))
        assertEquals(BlockReason.SESSION_LIMIT, result.decision?.reason)
        assertEquals(3 * 60_000L, result.decision?.cooldownRemainingMillis)
        assertNull(app(result.snapshot).foregroundSince)
        assertNotNull(app(result.snapshot).cooldown)
    }

    @Test fun `opening during cooldown is rejected with a live remaining time`() {
        val started = UsageEngine.onForeground(snapshot(Limits(10, 1, 3)), "social.app", true, at()).snapshot
        val cooling = UsageEngine.tick(started, at(60_000)).snapshot
        val result = UsageEngine.onForeground(cooling, "social.app", true, at(90_000))
        assertEquals(BlockReason.COOLDOWN, result.decision?.reason)
        assertEquals(150_000L, result.decision?.cooldownRemainingMillis)
    }

    @Test fun `an expired cooldown permits a clean new session`() {
        val started = UsageEngine.onForeground(snapshot(Limits(10, 1, 1)), "social.app", true, at()).snapshot
        val cooling = UsageEngine.tick(started, at(60_000)).snapshot
        val result = UsageEngine.onForeground(cooling, "social.app", true, at(120_000))
        assertNull(result.decision)
        assertNotNull(app(result.snapshot).foregroundSince)
        assertNull(app(result.snapshot).cooldown)
    }

    @Test fun `daily limit takes precedence and stays latched`() {
        val state = snapshot(Limits(1, 1, 3))
        val started = UsageEngine.onForeground(state, "social.app", true, at()).snapshot
        val result = UsageEngine.tick(started, at(60_000))
        assertEquals(BlockReason.DAILY_LIMIT, result.decision?.reason)
        assertTrue(app(result.snapshot).dailyBlocked)
        val reopening = UsageEngine.onForeground(result.snapshot, "social.app", true, at(70_000))
        assertEquals(BlockReason.DAILY_LIMIT, reopening.decision?.reason)
    }

    @Test fun `stricter daily and session limits apply immediately`() {
        var state = snapshot(Limits(30, 10, 5), used = 12 * 60_000L)
        state = UsageEngine.updateLimits(state, "social.app", Limits(10, 4, 5), at())
        assertEquals(Limits(10, 4, 5), app(state).limits)
        assertTrue(app(state).dailyBlocked)
        assertNull(app(state).pendingPolicy)
    }

    @Test fun `longer session applies now while looser daily limit waits for tomorrow`() {
        var state = snapshot(Limits(20, 5, 10))
        state = UsageEngine.updateLimits(state, "social.app", Limits(30, 8, 10), at())
        assertEquals(Limits(20, 8, 10), app(state).limits)
        assertEquals(Limits(30, 8, 10), app(state).pendingPolicy?.policy?.weekdayLimits)
    }

    @Test fun `lengthening an active session retains elapsed use and extends its end`() {
        var state = UsageEngine.onForeground(snapshot(Limits(20, 5, 10)), "social.app", true, at()).snapshot
        state = UsageEngine.updateLimits(state, "social.app", Limits(20, 10, 10), at(4 * 60_000L))
        assertEquals(4 * 60_000L, app(state).sessionUsedMillis)
        assertEquals(10, app(state).limits.sessionMinutes)
        assertNull(app(state).pendingPolicy)

        val atOldEnd = UsageEngine.tick(state, at(5 * 60_000L))
        assertNull(atOldEnd.decision)
        assertEquals(5 * 60_000L, app(atOldEnd.snapshot).sessionUsedMillis)

        val atNewEnd = UsageEngine.tick(atOldEnd.snapshot, at(10 * 60_000L))
        assertEquals(BlockReason.SESSION_LIMIT, atNewEnd.decision?.reason)
        assertEquals(10 * 60_000L, app(atNewEnd.snapshot).usedTodayMillis)
        assertEquals(10 * 60_000L, atNewEnd.decision?.cooldownRemainingMillis)
    }

    @Test fun `session increase does not undo an already started cooldown`() {
        var state = UsageEngine.onForeground(snapshot(Limits(20, 1, 3)), "social.app", true, at()).snapshot
        state = UsageEngine.tick(state, at(60_000L)).snapshot
        state = UsageEngine.updateLimits(state, "social.app", Limits(20, 5, 3), at(90_000L))
        assertEquals(5, app(state).limits.sessionMinutes)
        assertEquals(150_000L, app(state).cooldown?.remainingMillis(at(90_000L)))
        assertEquals(BlockReason.COOLDOWN,
            UsageEngine.onForeground(state, "social.app", true, at(90_000L)).decision?.reason)
    }

    @Test fun `session increase is capped until a pending daily increase takes effect`() {
        val state = UsageEngine.updateLimits(snapshot(Limits(5, 2, 10)), "social.app", Limits(20, 10, 10), at())
        assertEquals(Limits(5, 5, 10), app(state).limits)
        assertEquals(Limits(20, 10, 10), app(state).pendingPolicy?.policy?.weekdayLimits)
    }

    @Test fun `longer cooldown applies immediately while a shorter one waits`() {
        var state = snapshot(Limits(20, 5, 10))
        state = UsageEngine.updateLimits(state, "social.app", Limits(20, 5, 20), at())
        assertEquals(20, app(state).limits.cooldownMinutes)
        state = UsageEngine.updateLimits(state, "social.app", Limits(20, 5, 4), at())
        assertEquals(20, app(state).limits.cooldownMinutes)
        assertEquals(4, app(state).pendingPolicy?.policy?.weekdayLimits?.cooldownMinutes)
    }

    @Test fun `pending limits activate at the next local day`() {
        val state = UsageEngine.updateLimits(snapshot(), "social.app", Limits(30, 8, 5), at())
        val tomorrow = UsageEngine.tick(state, at(14 * 60 * 60_000L)).snapshot
        assertEquals(Limits(30, 8, 5), app(tomorrow).limits)
        assertNull(app(tomorrow).pendingPolicy)
        assertEquals(0, app(tomorrow).usedTodayMillis)
    }

    @Test fun `unused target is removed immediately`() {
        val removed = UsageEngine.requestRemoval(snapshot(), "social.app", at())
        assertTrue(removed.apps.isEmpty())
    }

    @Test fun `used target removal is delayed until tomorrow and can be cancelled`() {
        val used = snapshot(used = 1_000)
        val pending = UsageEngine.requestRemoval(used, "social.app", at())
        assertNotNull(app(pending).pendingRemoval)
        val cancelled = UsageEngine.cancelRemoval(pending, "social.app", at())
        assertNull(app(cancelled).pendingRemoval)
        val tomorrow = UsageEngine.tick(pending, at(14 * 60 * 60_000L)).snapshot
        assertTrue(tomorrow.apps.isEmpty())
    }

    @Test fun `active use is split at midnight while the session continues`() {
        val startWall = Instant.parse("2026-09-06T23:59:30Z").toEpochMilli()
        fun clock(delta: Long) = ClockReading(startWall + delta, 500_000 + delta, 8)
        var state = UsageEngine.newSnapshot(clock(0), "UTC")
        state = UsageEngine.addApp(state, "social.app", "Sosyal", Limits(20, 5, 10), 0, clock(0))
        state = UsageEngine.onForeground(state, "social.app", true, clock(0)).snapshot
        state = UsageEngine.tick(state, clock(40_000)).snapshot
        assertEquals(10_000, app(state).usedTodayMillis)
        assertEquals(40_000, app(state).sessionUsedMillis)
        assertEquals(40_000, app(state).longestSessionTodayMillis)
        assertNotNull(app(state).foregroundSince)
        assertEquals(30_000L, state.history.single().apps.single().usedMillis)
        assertEquals(30_000L, state.history.single().apps.single().longestSessionMillis)
    }

    @Test fun `longest session is retained in history and resets for the new day`() {
        var state = UsageEngine.onForeground(snapshot(), "social.app", true, at()).snapshot
        state = UsageEngine.onForeground(state, "launcher", true, at(30_000)).snapshot
        state = UsageEngine.onForeground(state, "social.app", true, at(90_000)).snapshot
        state = UsageEngine.onForeground(state, "launcher", true, at(135_000)).snapshot

        assertEquals(45_000, app(state).longestSessionTodayMillis)

        state = UsageEngine.tick(state, at(14 * 60 * 60_000L)).snapshot
        assertEquals(45_000, state.history.single().apps.single().longestSessionMillis)
        assertEquals(0, app(state).longestSessionTodayMillis)
    }

    @Test fun `weekend and weekday policies switch at the local day boundary`() {
        val policy = AppPolicy(Limits(20, 5, 10), Limits(40, 10, 5))
        var state = UsageEngine.addApp(
            UsageEngine.newSnapshot(at(), "UTC"), "social.app", "Sosyal", policy, 0, at(),
        )
        assertEquals(40, app(state).limits.dailyMinutes) // 6 September 2026 is Sunday.
        state = UsageEngine.tick(state, at(14 * 60 * 60_000L)).snapshot
        assertEquals(20, app(state).limits.dailyMinutes)
    }

    @Test fun `strict mode keeps daily and cooldown relaxations pending but changes session now`() {
        var state = UsageEngine.enableStrictMode(snapshot(), at())
        state = UsageEngine.updateLimits(state, "social.app", Limits(60, 10, 5), at())
        assertEquals(Limits(20, 10, 10), app(state).limits)
        assertNotNull(app(state).pendingPolicy)
        state = UsageEngine.tick(state, at(14 * 60 * 60_000L)).snapshot
        assertEquals(Limits(20, 10, 10), app(state).limits)
        state = UsageEngine.tick(state, at(24 * 60 * 60_000L)).snapshot
        assertEquals(Limits(60, 10, 5), app(state).limits)
        assertNull(app(state).pendingPolicy)
    }

    @Test fun `strict mode itself takes a full day to disable`() {
        var state = UsageEngine.enableStrictMode(snapshot(), at())
        state = UsageEngine.requestStrictModeDisable(state, at())
        assertTrue(state.strictModeEnabled)
        state = UsageEngine.tick(state, at(23 * 60 * 60_000L)).snapshot
        assertTrue(state.strictModeEnabled)
        state = UsageEngine.tick(state, at(24 * 60 * 60_000L)).snapshot
        assertFalse(state.strictModeEnabled)
        assertNull(state.strictModeDisableAtWallMillis)
    }

    @Test fun `strict mode delays removal for a full day`() {
        var state = UsageEngine.enableStrictMode(snapshot(used = 1_000), at())
        state = UsageEngine.requestRemoval(state, "social.app", at())
        state = UsageEngine.tick(state, at(14 * 60 * 60_000L)).snapshot
        assertEquals(1, state.apps.size)
        state = UsageEngine.tick(state, at(24 * 60 * 60_000L)).snapshot
        assertTrue(state.apps.isEmpty())
    }

    @Test fun `strict mode also delays removal of an unused target`() {
        var state = UsageEngine.enableStrictMode(snapshot(), at())
        state = UsageEngine.requestRemoval(state, "social.app", at())
        assertNotNull(app(state).pendingRemoval)
        state = UsageEngine.tick(state, at(24 * 60 * 60_000L)).snapshot
        assertTrue(state.apps.isEmpty())
    }

    @Test fun `history retains only the last thirty completed days`() {
        var state = snapshot()
        state = UsageEngine.tick(state, at(45 * 24 * 60 * 60_000L)).snapshot
        assertEquals(UsageEngine.HISTORY_DAYS, state.history.size)
        assertEquals(state.activeEpochDay - 1, state.history.last().epochDay)
    }

    @Test fun `lab time unit can exercise a ten second session`() {
        val fast = Limits(6, 1, 1, unitMillis = 10_000)
        val started = UsageEngine.onForeground(snapshot(fast), "social.app", true, at()).snapshot
        val result = UsageEngine.tick(started, at(10_000))
        assertEquals(BlockReason.SESSION_LIMIT, result.decision?.reason)
        assertEquals(10_000L, result.decision?.cooldownRemainingMillis)
    }

    @Test fun `shared daily limit blocks when combined usage reaches the pool`() {
        var state = multiSnapshot("social.app" to 60_000, "video.app" to 30_000)
        state = UsageEngine.addGroup(
            state, "social", GroupPolicy("Sosyal medya", setOf("social.app", "video.app"), 2), at(),
        )
        state = UsageEngine.onForeground(state, "video.app", true, at()).snapshot
        val result = UsageEngine.tick(state, at(30_000))
        assertEquals(BlockReason.GROUP_DAILY_LIMIT, result.decision?.reason)
        assertEquals("Sosyal medya", result.decision?.groupLabel)
        assertEquals(120_000L, result.decision?.groupUsedMillis)
    }

    @Test fun `shared daily limit blocks every member after the pool is exhausted`() {
        var state = multiSnapshot("social.app" to 120_000, "video.app" to 0)
        state = UsageEngine.addGroup(
            state, "social", GroupPolicy("Sosyal medya", setOf("social.app", "video.app"), 2), at(),
        )
        val result = UsageEngine.onForeground(state, "video.app", true, at())
        assertEquals(BlockReason.GROUP_DAILY_LIMIT, result.decision?.reason)
        assertEquals(AppAvailability.GROUP_BLOCKED, UsageEngine.view(result.snapshot, result.snapshot.apps.last(), at()).availability)
    }

    @Test fun `raising a shared limit waits until the next day`() {
        var state = multiSnapshot("social.app" to 0, "video.app" to 0)
        state = UsageEngine.addGroup(
            state, "social", GroupPolicy("Sosyal medya", setOf("social.app", "video.app"), 20), at(),
        )
        state = UsageEngine.updateGroup(
            state, "social", GroupPolicy("Sosyal medya", setOf("social.app", "video.app"), 60), at(),
        )
        assertEquals(20, state.groups.single().policy.dailyMinutes)
        assertEquals(60, state.groups.single().pendingPolicy?.policy?.dailyMinutes)
    }

    @Test fun `removing an app from a shared pool is delayed`() {
        var state = multiSnapshot("social.app" to 0, "video.app" to 0, "news.app" to 0)
        state = UsageEngine.addGroup(
            state, "social", GroupPolicy("Sosyal medya", setOf("social.app", "video.app", "news.app"), 20), at(),
        )
        state = UsageEngine.updateGroup(
            state, "social", GroupPolicy("Sosyal medya", setOf("social.app", "video.app"), 20), at(),
        )
        assertTrue("news.app" in state.groups.single().policy.packageNames)
        assertEquals(setOf("social.app", "video.app"), state.groups.single().pendingPolicy?.policy?.packageNames)
    }

    @Test fun `strict mode delays removing an unused shared pool`() {
        var state = multiSnapshot("social.app" to 0, "video.app" to 0)
        state = UsageEngine.addGroup(
            state, "social", GroupPolicy("Sosyal medya", setOf("social.app", "video.app"), 20), at(),
        )
        state = UsageEngine.enableStrictMode(state, at())
        state = UsageEngine.requestGroupRemoval(state, "social", at())
        assertNotNull(state.groups.single().pendingRemoval)
        state = UsageEngine.tick(state, at(24 * 60 * 60_000L)).snapshot
        assertTrue(state.groups.isEmpty())
    }

    @Test fun `an app cannot belong to two shared pools`() {
        var state = multiSnapshot("social.app" to 0, "video.app" to 0, "news.app" to 0)
        state = UsageEngine.addGroup(
            state, "social", GroupPolicy("Sosyal medya", setOf("social.app", "video.app"), 20), at(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            UsageEngine.addGroup(
                state, "other", GroupPolicy("Diğer", setOf("social.app", "news.app"), 20), at(),
            )
        }
    }

    @Test fun `immediate app removal also removes an invalid shared pool`() {
        var state = multiSnapshot("social.app" to 0, "video.app" to 0)
        state = UsageEngine.addGroup(
            state, "social", GroupPolicy("Sosyal medya", setOf("social.app", "video.app"), 20), at(),
        )
        state = UsageEngine.requestRemoval(state, "video.app", at())
        assertEquals(listOf("social.app"), state.apps.map { it.packageName })
        assertTrue(state.groups.isEmpty())
    }

    @Test fun `focus lock blocks every protected app without changing used time`() {
        var state = multiSnapshot("social.app" to 12_000, "video.app" to 34_000)
        state = UsageEngine.lockAllForToday(state, at())
        assertTrue(state.apps.all { it.manualBlocked })
        val result = UsageEngine.onForeground(state, "video.app", true, at())
        assertEquals(BlockReason.MANUAL_LOCK, result.decision?.reason)
        assertEquals(34_000L, result.snapshot.apps.first { it.packageName == "video.app" }.usedTodayMillis)
    }

    @Test fun `focus lock clears only at the next local day`() {
        var state = UsageEngine.lockAllForToday(snapshot(), at())
        assertEquals(AppAvailability.MANUAL_BLOCKED, UsageEngine.view(state, app(state), at()).availability)
        state = UsageEngine.tick(state, at(14 * 60 * 60_000L)).snapshot
        assertFalse(app(state).manualBlocked)
        assertEquals(AppAvailability.AVAILABLE, UsageEngine.view(state, app(state), at(14 * 60 * 60_000L)).availability)
    }

    @Test fun `focus locked target cannot be removed to undo the lock`() {
        var state = UsageEngine.lockAllForToday(snapshot(), at())
        state = UsageEngine.requestRemoval(state, "social.app", at())
        assertNotNull(app(state).pendingRemoval)
    }

    @Test fun `one target can be focus locked without locking the others`() {
        var state = multiSnapshot("social.app" to 12_000, "video.app" to 34_000)
        state = UsageEngine.lockAppForToday(state, "social.app", at())
        assertTrue(state.apps.first { it.packageName == "social.app" }.manualBlocked)
        assertFalse(state.apps.first { it.packageName == "video.app" }.manualBlocked)
        assertNull(UsageEngine.onForeground(state, "video.app", true, at()).decision)
    }

    @Test fun `emergency access waits then temporarily bypasses a block`() {
        var state = UsageEngine.lockAppForToday(snapshot(), "social.app", at())
        state = UsageEngine.requestEmergencyAccess(state, "social.app", "Ulaşım", at())
        var view = UsageEngine.view(state, app(state), at())
        assertEquals(AppAvailability.EMERGENCY_WAIT, view.availability)
        assertNotNull(UsageEngine.onForeground(state, "social.app", true, at(30_000)).decision)

        state = UsageEngine.tick(state, at(60_000)).snapshot
        view = UsageEngine.view(state, app(state), at(60_000))
        assertEquals(AppAvailability.EMERGENCY_ACCESS, view.availability)
        assertNull(UsageEngine.onForeground(state, "social.app", true, at(60_001)).decision)

        state = UsageEngine.tick(state, at(UsageEngine.EMERGENCY_WAIT_MILLIS + UsageEngine.EMERGENCY_ACCESS_MILLIS + 1)).snapshot
        assertEquals(AppAvailability.MANUAL_BLOCKED, UsageEngine.view(state, app(state), at(360_001)).availability)
    }

    @Test fun `emergency access can only be requested once per day`() {
        var state = UsageEngine.lockAppForToday(snapshot(), "social.app", at())
        state = UsageEngine.requestEmergencyAccess(state, "social.app", "Ulaşım", at())
        assertThrows(IllegalArgumentException::class.java) {
            UsageEngine.requestEmergencyAccess(state, "social.app", "Arama", at(1_000))
        }
    }

    @Test fun `active schedule blocks a selected app and reports its remaining time`() {
        var state = snapshot()
        state = UsageEngine.addSchedule(
            state, "sleep", SchedulePolicy("Sabah odağı", setOf("social.app"), 64, 9 * 60, 11 * 60), at(),
        )
        val result = UsageEngine.onForeground(state, "social.app", true, at())
        assertEquals(BlockReason.SCHEDULED_BLOCK, result.decision?.reason)
        assertEquals("Sabah odağı", result.decision?.scheduleLabel)
        assertEquals(60 * 60_000L, result.decision?.scheduleRemainingMillis)
        val view = UsageEngine.view(result.snapshot, app(result.snapshot), at())
        assertEquals(AppAvailability.SCHEDULE_BLOCKED, view.availability)
        assertEquals("Sabah odağı", view.scheduleLabel)
    }

    @Test fun `inactive schedule permits the selected app`() {
        var state = snapshot()
        state = UsageEngine.addSchedule(
            state, "later", SchedulePolicy("Öğlen", setOf("social.app"), 64, 11 * 60, 12 * 60), at(),
        )
        assertNull(UsageEngine.onForeground(state, "social.app", true, at()).decision)
    }

    @Test fun `overnight schedule remains active after midnight`() {
        val start = Instant.parse("2026-09-06T23:30:00Z").toEpochMilli()
        fun nightClock(delta: Long) = ClockReading(start + delta, 900_000 + delta, 12)
        var state = UsageEngine.newSnapshot(nightClock(0), "UTC")
        state = UsageEngine.addApp(state, "social.app", "Sosyal", Limits(), 0, nightClock(0))
        state = UsageEngine.addSchedule(
            state, "night", SchedulePolicy("Gece", setOf("social.app"), 64, 23 * 60, 60), nightClock(0),
        )
        val afterMidnight = nightClock(60 * 60_000L)
        val result = UsageEngine.onForeground(state, "social.app", true, afterMidnight)
        assertEquals(BlockReason.SCHEDULED_BLOCK, result.decision?.reason)
        assertEquals(30 * 60_000L, result.decision?.scheduleRemainingMillis)
    }

    @Test fun `schedule edits activate at the next local day`() {
        var state = UsageEngine.addSchedule(
            snapshot(), "focus", SchedulePolicy("Eski", setOf("social.app"), 64, 9 * 60, 11 * 60), at(),
        )
        val desired = SchedulePolicy("Yeni", setOf("social.app"), 1, 12 * 60, 13 * 60)
        state = UsageEngine.updateSchedule(state, "focus", desired, at())
        assertEquals("Eski", state.schedules.single().policy.label)
        assertEquals("Yeni", state.schedules.single().pendingPolicy?.policy?.label)
        state = UsageEngine.tick(state, at(14 * 60 * 60_000L)).snapshot
        assertEquals(desired, state.schedules.single().policy)
        assertNull(state.schedules.single().pendingPolicy)
    }

    @Test fun `strict mode keeps a schedule edit pending for a full day`() {
        var state = UsageEngine.addSchedule(
            snapshot(), "focus", SchedulePolicy("Eski", setOf("social.app"), 64, 9 * 60, 11 * 60), at(),
        )
        state = UsageEngine.enableStrictMode(state, at())
        state = UsageEngine.updateSchedule(
            state, "focus", SchedulePolicy("Yeni", setOf("social.app"), 1, 12 * 60, 13 * 60), at(),
        )
        state = UsageEngine.tick(state, at(14 * 60 * 60_000L)).snapshot
        assertEquals("Eski", state.schedules.single().policy.label)
        state = UsageEngine.tick(state, at(24 * 60 * 60_000L)).snapshot
        assertEquals("Yeni", state.schedules.single().policy.label)
    }

    @Test fun `schedule removal waits until tomorrow and can be cancelled`() {
        var state = UsageEngine.addSchedule(
            snapshot(), "focus", SchedulePolicy("Odak", setOf("social.app"), 64, 9 * 60, 11 * 60), at(),
        )
        val pending = UsageEngine.requestScheduleRemoval(state, "focus", at())
        assertNotNull(pending.schedules.single().pendingRemoval)
        val cancelled = UsageEngine.cancelScheduleRemoval(pending, "focus", at())
        assertNull(cancelled.schedules.single().pendingRemoval)
        state = UsageEngine.tick(pending, at(14 * 60 * 60_000L)).snapshot
        assertTrue(state.schedules.isEmpty())
    }

    @Test fun `removing the last scheduled app removes the schedule`() {
        var state = UsageEngine.addSchedule(
            snapshot(), "focus", SchedulePolicy("Odak", setOf("social.app"), 64, 11 * 60, 12 * 60), at(),
        )
        state = UsageEngine.requestRemoval(state, "social.app", at())
        assertTrue(state.apps.isEmpty())
        assertTrue(state.schedules.isEmpty())
    }

    @Test fun `backwards wall clock cannot move the local day backwards on one boot`() {
        var state = snapshot()
        state = UsageEngine.onForeground(state, "social.app", true, at()).snapshot
        state = UsageEngine.tick(state, at(10_000, wallShift = -86_400_000)).snapshot
        assertEquals(10_000, app(state).usedTodayMillis)
        assertEquals(UsageEngine.newSnapshot(at(), "UTC").activeEpochDay, state.activeEpochDay)
    }

    @Test fun `a persisted write advances the trusted reboot clock anchor`() {
        val firstWall = Instant.parse("2026-09-01T10:00:00Z").toEpochMilli()
        val first = ClockReading(firstWall, 10_000, 1)
        var state = UsageEngine.newSnapshot(first, "UTC")
        state = UsageEngine.addApp(state, "social.app", "Sosyal", Limits(), 0, first)
        val fiveDaysLater = ClockReading(firstWall + 5 * 86_400_000L, 10_000 + 5 * 86_400_000L, 1)
        state = UsageEngine.updateLimits(state, "social.app", Limits(10, 5, 10), fiveDaysLater)
        assertEquals(fiveDaysLater.wallMillis, state.clockAnchor.wallMillis)

        val rebootWithOldWall = ClockReading(firstWall + 86_400_000L, 1_000, 2)
        state = UsageEngine.restore(state, rebootWithOldWall)
        assertEquals(fiveDaysLater.wallMillis, UsageEngine.trustedWall(state, rebootWithOldWall))
    }

    @Test fun `restore does not guess that foreground continued through process death`() {
        var state = UsageEngine.onForeground(snapshot(), "social.app", true, at()).snapshot
        state = UsageEngine.restore(state, at(120_000))
        assertEquals(0, app(state).usedTodayMillis)
        assertNull(app(state).foregroundSince)
        assertNotNull(app(state).backgroundSince)
    }

    @Test fun `usage recovery adds only supplied intervals and can latch daily limit`() {
        val state = UsageEngine.recoverUsage(
            snapshot(Limits(1, 1, 2)),
            listOf(UsageInterval("social.app", wall, wall + 90_000)),
            wall, "social.app", "social.app", at(90_000),
        )
        assertEquals(90_000, app(state).usedTodayMillis)
        assertTrue(app(state).dailyBlocked)
    }

    @Test fun `recovery adds a process gap to the existing session`() {
        var state = UsageEngine.onForeground(snapshot(Limits(10, 2, 3)), "social.app", true, at()).snapshot
        state = UsageEngine.tick(state, at(30_000)).snapshot
        state = UsageEngine.restore(state, at(90_000))
        state = UsageEngine.recoverUsage(
            state,
            listOf(UsageInterval("social.app", wall + 30_000, wall + 90_000)),
            wall + 30_000, "social.app", "social.app", at(90_000),
        )
        assertEquals(90_000, app(state).usedTodayMillis)
        assertEquals(90_000, app(state).sessionUsedMillis)
    }

    @Test fun `recovery starts cooldown when the session crossed while service was absent`() {
        val state = UsageEngine.recoverUsage(
            snapshot(Limits(10, 1, 3)),
            listOf(UsageInterval("social.app", wall, wall + 70_000)),
            wall, "social.app", "social.app", at(70_000),
        )
        assertEquals(70_000, app(state).usedTodayMillis)
        assertEquals(0, app(state).sessionUsedMillis)
        assertEquals(180_000L, app(state).cooldown?.remainingMillis(at(70_000)))
    }

    @Test fun `recovery respects a full minute away as a new session`() {
        val state = UsageEngine.recoverUsage(
            snapshot(Limits(10, 2, 3)),
            listOf(
                UsageInterval("social.app", wall, wall + 40_000),
                UsageInterval("social.app", wall + 100_000, wall + 120_000),
            ),
            wall, null, null, at(180_000),
        )
        assertEquals(60_000, app(state).usedTodayMillis)
        assertEquals(0, app(state).sessionUsedMillis)
    }

    @Test fun `recovery clips a cross-midnight interval to the active day`() {
        val midnight = Instant.parse("2026-09-07T00:00:00Z").toEpochMilli()
        val state = UsageEngine.recoverUsage(
            snapshot(Limits(20, 5, 10)),
            listOf(UsageInterval("social.app", midnight - 30_000, midnight + 20_000)),
            midnight, "social.app", "social.app",
            ClockReading(midnight + 20_000, 50_000_000, 9),
        )
        assertEquals(20_000L, app(state).usedTodayMillis)
    }

    @Test fun `same boot timed block ignores wall clock changes`() {
        val block = TimedBlock(TimeAnchor(wall, 200_000, 4), 60_000)
        assertEquals(50_000, block.remainingMillis(at(10_000, wallShift = -999_999)))
        assertEquals(0, block.remainingMillis(at(60_000, wallShift = 9_999_999)))
    }

    @Test fun `self system settings and device roles cannot be protected`() {
        for (pkg in listOf("self", "com.android.settings", "com.android.systemui", "launcher", "keyboard", "dialer")) {
            assertFalse(pkg, TargetPolicy.mayProtect(pkg, "self", false, setOf("launcher", "keyboard", "dialer")))
        }
        assertTrue(TargetPolicy.mayProtect("com.instagram.android", "self", false, emptySet()))
        assertTrue(TargetPolicy.mayProtect("com.google.android.youtube", "self", true, emptySet()))
        assertFalse(TargetPolicy.mayProtect("com.samsung.essential", "self", true, emptySet()))
    }
}
