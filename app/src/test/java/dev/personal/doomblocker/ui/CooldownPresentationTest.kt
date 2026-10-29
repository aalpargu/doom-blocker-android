package dev.personal.doomblocker.ui

import dev.personal.doomblocker.core.AppAvailability
import dev.personal.doomblocker.core.BlockReason
import org.junit.Assert.*
import org.junit.Test

class CooldownPresentationTest {
    @Test fun `only session and cooldown decisions open the break screen`() {
        assertEquals(setOf(BlockReason.SESSION_LIMIT, BlockReason.COOLDOWN, BlockReason.PLANNED_SESSION_END),
            BlockReason.entries.filter(::showsCooldownScreen).toSet())
    }

    @Test fun `finished break never promises access through another lock`() {
        assertTrue(afterCooldownMessage(AppAvailability.DAILY_BLOCKED).contains("Günlük hakkın doldu"))
        assertTrue(afterCooldownMessage(AppAvailability.GROUP_BLOCKED).contains("Ortak günlük hakkın doldu"))
        assertTrue(afterCooldownMessage(AppAvailability.FOCUS_BLOCKED).contains("Odak süren devam"))
        assertTrue(afterCooldownMessage(AppAvailability.SCHEDULE_BLOCKED).contains("şu an kapalı"))
        assertTrue(afterCooldownMessage(AppAvailability.INTENTION_REQUIRED).contains("amacını"))
    }
}
