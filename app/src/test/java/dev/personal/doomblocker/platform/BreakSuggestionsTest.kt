package dev.personal.doomblocker.platform

import dev.personal.doomblocker.core.ClockReading
import dev.personal.doomblocker.core.Limits
import dev.personal.doomblocker.core.UsageEngine
import org.junit.Assert.*
import org.junit.Test

class BreakSuggestionsTest {
    @Test fun `ten minute planned session keeps its suggestion across cooldown ticks and reopening`() {
        fun at(ms: Long) = ClockReading(1_800_000_000_000L + ms, 100_000L + ms, 1)
        var snapshot = UsageEngine.addApp(UsageEngine.newSnapshot(at(0), "UTC"),
            "target", "Target", Limits(30, 5, 10), 0, at(0))
        snapshot = UsageEngine.setIntentionalOpen(snapshot, "target", true, at(0))
        snapshot = UsageEngine.startPlannedAccess(snapshot, "target", 600_000L, "Mesaj", at(0))
        snapshot = UsageEngine.onForeground(snapshot, "target", true, at(0)).snapshot
        snapshot = UsageEngine.tick(snapshot, at(600_000)).snapshot
        val app = snapshot.apps.single()
        val started = requireNotNull(app.cooldown).startedAt.wallMillis
        val suggestion = BreakSuggestions.forCooldown(app.packageName, started)
        snapshot = UsageEngine.onForeground(snapshot, null, true, at(605_000)).snapshot
        snapshot = UsageEngine.onForeground(snapshot, "target", true, at(610_000)).snapshot
        assertEquals(suggestion, BreakSuggestions.forCooldown(app.packageName,
            requireNotNull(snapshot.apps.single().cooldown).startedAt.wallMillis))
        assertEquals(app.usedTodayMillis, snapshot.apps.single().usedTodayMillis)
        assertEquals(app.limitEventsToday, snapshot.apps.single().limitEventsToday)
    }

    @Test fun `different start instants offer multiple suggestions without invalid indices`() {
        val variants = (0L..63L).map {
            BreakSuggestions.forCooldown("target", 1_800_000_000_000L + it * 60_000L)
        }.toSet()
        assertTrue(variants.size >= 4)
        for (timestamp in listOf(Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE)) {
            val suggestion = BreakSuggestions.forCooldown("negative-hash-package", timestamp)
            assertTrue(suggestion.title.isNotBlank())
            assertTrue(suggestion.message.isNotBlank())
        }
    }
}
