package dev.personal.doomblocker.platform

import dev.personal.doomblocker.core.ClockReading
import dev.personal.doomblocker.core.Limits
import dev.personal.doomblocker.core.UsageEngine
import dev.personal.doomblocker.service.RuntimeStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class QuickSurfaceModelsTest {
    @Test fun `disconnected service overrides an active focus label`() {
        var s = UsageEngine.newSnapshot(at(), "UTC")
        s = UsageEngine.addApp(s, "x", "X", Limits(), 0, at())
        s = UsageEngine.startFocusSession(s, setOf("x"), 1_800_000, at())
        assertEquals("Koruma servisi kapalı", QuickSurfaceModels.widget(s, RuntimeStatus(connected = false), at()).status)
        assertEquals(FocusTileState.UNAVAILABLE, QuickSurfaceModels.focusTile(s, at(), connected = false).state)
    }
    private val wall = Instant.parse("2026-09-13T10:00:00Z").toEpochMilli()
    private fun at(afterMillis: Long = 0) = ClockReading(wall + afterMillis, 50_000 + afterMillis, 7)

    @Test fun `focus tile exposes empty inactive and active states`() {
        assertEquals(FocusTileState.UNAVAILABLE, QuickSurfaceModels.focusTile(null, at()).state)

        var state = UsageEngine.newSnapshot(at(), "UTC")
        state = UsageEngine.addApp(state, "social.app", "Sosyal", Limits(30, 5, 10), 0, at())
        assertEquals(FocusTileState.INACTIVE, QuickSurfaceModels.focusTile(state, at()).state)

        state = UsageEngine.startFocusSession(state, setOf("social.app"), 30 * 60_000L, at())
        val active = QuickSurfaceModels.focusTile(state, at(1_000))
        assertEquals(FocusTileState.ACTIVE, active.state)
        assertEquals("30 dk kaldı", active.subtitle)
    }

    @Test fun `widget prioritizes blocked apps and reports focus status`() {
        var state = UsageEngine.newSnapshot(at(), "UTC")
        state = UsageEngine.addApp(state, "music.app", "Müzik", Limits(30, 5, 10), 0, at())
        state = UsageEngine.addApp(state, "social.app", "Sosyal", Limits(30, 5, 10), 0, at())
        state = UsageEngine.startFocusSession(state, setOf("social.app"), 30 * 60_000L, at())

        val model = QuickSurfaceModels.widget(state, RuntimeStatus(connected = true), at(1_000))

        assertEquals("Odak açık · 30 dk kaldı", model.status)
        assertEquals("social.app", model.targets.first().packageName)
        assertEquals("Odakta", model.targets.first().status)
    }

    @Test fun `widget reports intention gate without hiding remaining targets`() {
        var state = UsageEngine.newSnapshot(at(), "UTC")
        state = UsageEngine.addApp(state, "social.app", "Sosyal", Limits(30, 5, 10), 0, at())
        state = UsageEngine.setIntentionalOpen(state, "social.app", true, at())

        val model = QuickSurfaceModels.widget(state, RuntimeStatus(connected = true), at())

        assertEquals("Koruma aktif · 1 uygulama", model.status)
        assertEquals("Niyet bekliyor", model.targets.single().status)
    }

    @Test fun `widget caps its compact list at three targets`() {
        var state = UsageEngine.newSnapshot(at(), "UTC")
        repeat(5) { index ->
            state = UsageEngine.addApp(state, "app.$index", "Uygulama $index", Limits(30, 5, 10), 0, at())
        }

        assertEquals(3, QuickSurfaceModels.widget(state, RuntimeStatus(connected = true), at()).targets.size)
    }
}
