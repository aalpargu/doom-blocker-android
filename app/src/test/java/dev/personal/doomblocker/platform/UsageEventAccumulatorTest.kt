package dev.personal.doomblocker.platform

import org.junit.Assert.*
import org.junit.Test

class UsageEventAccumulatorTest {
    @Test fun `lookback seeds a session already active at midnight without counting yesterday`() {
        val parser = UsageEventAccumulator(setOf("x"), 100_000, 160_000)
        parser.accept(90_000, UsageEventKind.RESUMED, "x", "Main")
        assertEquals(60_000L, parser.finish().usageByPackage["x"])
    }
    @Test fun `locked and screen off time is never charged`() {
        val parser = UsageEventAccumulator(setOf("x"), 100_000, 200_000)
        parser.accept(90_000, UsageEventKind.RESUMED, "x")
        parser.accept(95_000, UsageEventKind.LOCKED)
        parser.accept(140_000, UsageEventKind.UNLOCKED)
        parser.accept(160_000, UsageEventKind.SCREEN_OFF)
        parser.accept(190_000, UsageEventKind.SCREEN_ON)
        assertEquals(30_000L, parser.finish().usageByPackage["x"])
    }
    @Test fun `shutdown does not carry the previous app through a reboot`() {
        val parser = UsageEventAccumulator(setOf("x"), 0, 200_000)
        parser.accept(0, UsageEventKind.RESUMED, "x")
        parser.accept(10_000, UsageEventKind.SHUTDOWN)
        parser.accept(100_000, UsageEventKind.STARTUP)
        parser.accept(110_000, UsageEventKind.SCREEN_ON)
        parser.accept(120_000, UsageEventKind.UNLOCKED)
        assertEquals(10_000L, parser.finish().usageByPackage["x"])
        assertNull(parser.finish().finalForegroundPackage)
    }
    @Test fun `late pause of old activity does not stop new activity in same package`() {
        val parser = UsageEventAccumulator(setOf("x"), 0, 60_000)
        parser.accept(0, UsageEventKind.RESUMED, "x", "A")
        parser.accept(10_000, UsageEventKind.RESUMED, "x", "B")
        parser.accept(10_001, UsageEventKind.PAUSED, "x", "A")
        assertEquals(60_000L, parser.finish().usageByPackage["x"])
    }
    @Test fun `empty history cannot turn a saved foreground into invented usage`() {
        val result = UsageEventAccumulator(setOf("x"), 0, 86_400_000).finish()
        assertTrue(result.intervals.isEmpty())
        assertNotNull(result.coverageWarning)
    }
    @Test fun `switching to other apps and returning counts only target intervals`() {
        val parser = UsageEventAccumulator(setOf("x"), 0, 60_000)
        parser.accept(0, UsageEventKind.RESUMED, "x")
        parser.accept(10_000, UsageEventKind.RESUMED, "other")
        parser.accept(40_000, UsageEventKind.RESUMED, "x")
        parser.accept(50_000, UsageEventKind.PAUSED, "x")
        assertEquals(20_000L, parser.finish().usageByPackage["x"])
    }
}
