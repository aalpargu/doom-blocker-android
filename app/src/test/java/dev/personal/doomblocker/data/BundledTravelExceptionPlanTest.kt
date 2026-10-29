package dev.personal.doomblocker.data

import dev.personal.doomblocker.core.ClockReading
import dev.personal.doomblocker.core.TravelException
import dev.personal.doomblocker.core.UsageEngine
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledTravelExceptionPlanTest {
    private val start = Instant.parse("2026-09-26T19:00:00Z").toEpochMilli()
    private val oldEnd = Instant.parse("2026-09-27T08:00:00Z").toEpochMilli()
    private val newEnd = Instant.parse("2026-09-27T21:00:00Z").toEpochMilli()
    private val beforeTrip = ClockReading(start - 60_000L, 1_000_000L, 7)
    private fun snapshot() = UsageEngine.newSnapshot(beforeTrip, "Europe/Istanbul")

    @Test fun `new install seeds inclusive full September 27 window`() {
        val seeded = BundledTravelExceptionPlan.apply(snapshot(), beforeTrip)
        assertTrue(seeded.travelExceptionConfigured)
        assertEquals(TravelException(start, newEnd), seeded.travelException)
        assertTrue(seeded.travelException!!.isActive(oldEnd))
        assertTrue(seeded.travelException!!.isActive(newEnd - 1))
        assertFalse(seeded.travelException!!.isActive(newEnd))
    }

    @Test fun `stored 3_1_2 window is extended before recovery and only once`() {
        val saved = snapshot().copy(travelExceptionConfigured = true,
            travelException = TravelException(start, oldEnd))
        val upgraded = BundledTravelExceptionPlan.apply(saved, beforeTrip)
        assertEquals(TravelException(start, newEnd), upgraded.travelException)
        assertSame(upgraded, BundledTravelExceptionPlan.apply(upgraded, beforeTrip))
    }

    @Test fun `canceled tombstone and custom window are preserved`() {
        val canceled = snapshot().copy(travelExceptionConfigured = true, travelException = null)
        assertSame(canceled, BundledTravelExceptionPlan.apply(canceled, beforeTrip))
        assertNull(BundledTravelExceptionPlan.apply(canceled, beforeTrip).travelException)
        val custom = snapshot().copy(travelExceptionConfigured = true,
            travelException = TravelException(start + 60_000L, oldEnd))
        assertSame(custom, BundledTravelExceptionPlan.apply(custom, beforeTrip))
    }
}
