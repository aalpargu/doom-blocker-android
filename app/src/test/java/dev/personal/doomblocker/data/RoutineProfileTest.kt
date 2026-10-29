package dev.personal.doomblocker.data

import org.junit.Assert.assertThrows
import org.junit.Test

class RoutineProfileTest {
    @Test fun `profile requires at least one target`() {
        assertThrows(IllegalArgumentException::class.java) {
            RoutineProfile("id", "Çalışma", emptySet(), 20, 5, 10)
        }
    }

    @Test fun `profile reuses domain limit validation`() {
        assertThrows(IllegalArgumentException::class.java) {
            RoutineProfile("id", "Çalışma", setOf("app"), 5, 10, 10)
        }
    }
}
