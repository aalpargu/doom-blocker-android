package dev.personal.doomblocker.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnforcementReliabilityTest {
    @Test fun `matching foreground window uses event package without root lookup`() {
        assertEquals("target", ForegroundWindowPolicy.eventPackage("target", 42, 42))
    }

    @Test fun `background pip and system window events cannot replace foreground`() {
        assertNull(ForegroundWindowPolicy.eventPackage("background", 7, 42))
        assertNull(ForegroundWindowPolicy.eventPackage("system", -1, -1))
        assertNull(ForegroundWindowPolicy.eventPackage("target", 42, null))
        assertNull(ForegroundWindowPolicy.eventPackage(null, 42, 42))
    }

    @Test fun `late frame from old gate cannot remove new cover`() {
        val handoff = GateHandoff()
        handoff.begin("first", 1L)
        handoff.begin("second", 2L)
        assertFalse(handoff.acceptFrame(1L))
        assertTrue(handoff.acceptFrame(2L))
        assertFalse(handoff.isWaitingFor("first"))
        assertTrue(handoff.isWaitingFor("second"))
    }

    @Test fun `cleared or disconnected cover ignores frame callbacks`() {
        val handoff = GateHandoff()
        handoff.begin("target", 1L)
        handoff.clear()
        assertFalse(handoff.acceptFrame(1L))
        assertFalse(handoff.isWaitingFor("target"))
    }

    @Test fun `cover stays through target to gate handoff but yields to another app`() {
        val handoff = GateHandoff()
        handoff.begin("target", 1L)
        assertFalse(handoff.shouldDismissFor("target", "blocker"))
        assertFalse(handoff.shouldDismissFor("blocker", "blocker"))
        assertFalse(handoff.shouldDismissFor(null, "blocker"))
        assertTrue(handoff.shouldDismissFor("launcher", "blocker"))
        assertTrue(handoff.shouldDismissFor("unprotected", "blocker"))
    }

    @Test fun `retry delay grows and stays bounded`() {
        assertEquals(600L, EnforcementRetryPolicy.delayAfterAttempt(1))
        assertEquals(1_000L, EnforcementRetryPolicy.delayAfterAttempt(2))
        assertEquals(2_000L, EnforcementRetryPolicy.delayAfterAttempt(3))
        assertEquals(5_000L, EnforcementRetryPolicy.delayAfterAttempt(4))
        assertEquals(10_000L, EnforcementRetryPolicy.delayAfterAttempt(50))
    }

    @Test fun `temporary null keeps last reliable foreground`() {
        val stabilizer = ForegroundStabilizer(1_500L)
        assertEquals("target", stabilizer.resolve("target", 1_000L).packageName)
        val transient = stabilizer.resolve(null, 2_000L)
        assertEquals("target", transient.packageName)
        assertTrue(transient.provisional)
    }

    @Test fun `expired null clears last foreground`() {
        val stabilizer = ForegroundStabilizer(1_500L)
        stabilizer.resolve("target", 1_000L)
        val expired = stabilizer.resolve(null, 2_501L)
        assertNull(expired.packageName)
        assertFalse(expired.provisional)
    }
}
