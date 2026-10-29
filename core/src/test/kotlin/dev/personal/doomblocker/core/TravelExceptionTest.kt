package dev.personal.doomblocker.core

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TravelExceptionTest {
    private val start = Instant.parse("2026-09-26T19:00:00Z").toEpochMilli()
    private val end = Instant.parse("2026-09-27T21:00:00Z").toEpochMilli()
    private val base = start - 10 * 60_000L
    private fun at(offset: Long) = ClockReading(base + offset, 1_000_000L + offset, 7)
    private fun app(state: ProtectionSnapshot, packageName: String = "social.app") =
        state.apps.first { it.packageName == packageName }

    private fun snapshot(limits: Limits = Limits(20, 5, 10)): ProtectionSnapshot {
        var state = UsageEngine.newSnapshot(at(0), "Europe/Istanbul")
        state = UsageEngine.addApp(state, "social.app", "Social", limits, 0, at(0))
        return UsageEngine.addApp(state, "video.app", "Video", limits, 0, at(0))
    }

    @Test fun `window includes start and excludes end`() {
        val trip = TravelException(start, end)
        assertFalse(trip.isActive(start - 1))
        assertTrue(trip.isActive(start))
        assertTrue(trip.isActive(end - 1))
        assertFalse(trip.isActive(end))
        assertEquals(10, trip.overlapMillis(start - 5, start + 10))
    }

    @Test fun `all protected apps are available while travel exception is active`() {
        var state = snapshot(Limits(1, 1, 10))
        state = UsageEngine.lockAllForToday(state, at(0))
        state = UsageEngine.startFocusSession(state, state.apps.map { it.packageName }.toSet(),
            30 * 60 * 60_000L, at(0))
        state = UsageEngine.setTravelException(state, start, end, at(0))
        assertTrue(state.travelExceptionConfigured)
        for (target in state.apps) {
            val result = UsageEngine.onForeground(state, target.packageName, true, at(10 * 60_000L))
            assertNull(result.decision)
            assertEquals(AppAvailability.ACTIVE,
                UsageEngine.view(result.snapshot, app(result.snapshot, target.packageName), at(10 * 60_000L)).availability)
            state = UsageEngine.onForeground(result.snapshot, null, false, at(10 * 60_000L)).snapshot
        }
        assertEquals(BlockReason.FOCUS_SESSION,
            UsageEngine.onForeground(state, "social.app", true, at(end - base)).decision?.reason)
    }

    @Test fun `travel use counts daily across midnight and resets quota when trip ends`() {
        var state = UsageEngine.setTravelException(snapshot(Limits(20, 5, 10)), start, end, at(0))
        state = UsageEngine.onForeground(state, "social.app", true, at(10 * 60_000L)).snapshot
        val midway = UsageEngine.tick(state, at(3 * 60 * 60_000L))
        assertNull(midway.decision)
        assertEquals(0, app(midway.snapshot).sessionUsedMillis)
        assertNull(app(midway.snapshot).cooldown)
        assertEquals(0, app(midway.snapshot).limitEventsToday)
        assertEquals(AppAvailability.ACTIVE,
            UsageEngine.view(midway.snapshot, app(midway.snapshot), at(3 * 60 * 60_000L)).availability)
        val oldEnd = Instant.parse("2026-09-27T08:00:00Z").toEpochMilli()
        val oldEndState = UsageEngine.tick(midway.snapshot, at(oldEnd - base))
        assertNull(oldEndState.decision)
        assertEquals(AppAvailability.ACTIVE,
            UsageEngine.view(oldEndState.snapshot, app(oldEndState.snapshot), at(oldEnd - base)).availability)
        val nearEnd = UsageEngine.tick(oldEndState.snapshot, at(end - base - 1_000L))
        assertNull(nearEnd.decision)
        assertEquals(0, app(nearEnd.snapshot).sessionUsedMillis)
        assertEquals(0, app(nearEnd.snapshot).limitEventsToday)
        val midnight = Instant.parse("2026-09-26T21:00:00Z").toEpochMilli()
        assertEquals(end - 1_000L - midnight, app(nearEnd.snapshot).usedTodayMillis)
        val ended = UsageEngine.tick(nearEnd.snapshot, at(end - base))
        assertNull(ended.decision)
        assertEquals(0L, app(ended.snapshot).usedTodayMillis)
        assertFalse(app(ended.snapshot).dailyBlocked)
        assertNull(app(ended.snapshot).cooldown)
        assertEquals(0, app(ended.snapshot).limitEventsToday)
    }

    @Test fun `only post travel use contributes to next session`() {
        var state = UsageEngine.setTravelException(snapshot(Limits(1440, 5, 10)), start, end, at(0))
        state = UsageEngine.onForeground(state, "social.app", true, at(10 * 60_000L)).snapshot
        val after = UsageEngine.tick(state, at(end - base + 4 * 60_000L))
        assertNull(after.decision)
        assertEquals(4 * 60_000L, app(after.snapshot).sessionUsedMillis)
        val blocked = UsageEngine.tick(after.snapshot, at(end - base + 5 * 60_000L))
        assertEquals(BlockReason.SESSION_LIMIT, blocked.decision?.reason)
    }

    @Test fun `recovered travel use does not create a cooldown and preserves daily use`() {
        val initial = UsageEngine.setTravelException(snapshot(Limits(1440, 5, 10)), start, end, at(0))
        val recovered = UsageEngine.recoverUsage(
            UsageEngine.restore(initial, at(end - base + 4 * 60_000L)),
            listOf(UsageInterval("social.app", start, end + 4 * 60_000L)),
            start,
            "social.app",
            "social.app",
            at(end - base + 4 * 60_000L),
        )
        assertEquals(4 * 60_000L, app(recovered).sessionUsedMillis)
        assertNull(app(recovered).cooldown)
        assertEquals(0, app(recovered).limitEventsToday)
        assertEquals(4 * 60_000L, app(recovered).usedTodayMillis)
        assertEquals(2 * 60 * 60_000L,
            recovered.history.first().apps.first { it.packageName == "social.app" }.usedMillis)
        assertEquals(24 * 60 * 60_000L,
            recovered.history.last().apps.first { it.packageName == "social.app" }.usedMillis)
    }

    @Test fun `cancel leaves tombstone and clears active exception`() {
        val state = UsageEngine.setTravelException(snapshot(), start, end, at(0))
        val canceled = UsageEngine.clearTravelException(state, at(1_000))
        assertNull(canceled.travelException)
        assertTrue(canceled.travelExceptionConfigured)
    }
}
