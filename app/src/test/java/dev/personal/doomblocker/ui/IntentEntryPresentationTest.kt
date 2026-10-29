package dev.personal.doomblocker.ui

import dev.personal.doomblocker.core.*
import org.junit.Assert.*
import org.junit.Test

class IntentEntryPresentationTest {
    private fun entry(budget: Long) = PlannedEntry(budget, 0, 60_000, 600_000, null, null)

    @Test fun `six minute balance offers one five and exactly six with matching requests`() {
        val choices = entryChoices(entry(360_000))
        assertEquals(listOf("1 dk", "5 dk", "6 dk kaldı"), choices.map { it.label })
        assertEquals(listOf(1, 5, 10), choices.map { it.requestedMinutes })
        assertEquals(listOf(60_000L, 300_000L, 360_000L), choices.map { it.usageMillis })
    }

    @Test fun `sub minute balance gives one exact usable option`() {
        assertEquals(listOf("30 sn kaldı"), entryChoices(entry(30_000)).map { it.label })
        assertEquals("4 dk 30 sn", entryDuration(270_000))
        assertTrue(entryChoices(entry(0)).isEmpty())
        assertNotNull(entryBlockMessage(entry(0)))
        assertTrue(entryChoices(null).isEmpty())
    }

    @Test fun `full quota retains all four familiar choices`() {
        assertEquals(listOf("1 dk", "5 dk", "10 dk", "15 dk"), entryChoices(entry(1_800_000)).map { it.label })
        assertNull(entryBlockMessage(entry(1_800_000)))
    }

    @Test fun `a running break explains remaining seconds and prevents starting`() {
        val blocked = entry(600_000).copy(block = BlockDecision("x", "X", BlockReason.COOLDOWN,
            0, 1_800_000, 300_000, cooldownRemainingMillis = 32_000))
        assertEquals("Molan devam ediyor · 32 sn kaldı.", entryBlockMessage(blocked))
    }
}
