package dev.personal.doomblocker.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoomDesignSystemTest {
    @Test fun `amoled preference forces dark theme even when system is light`() {
        assertTrue(shouldUseDarkTheme(systemDark = false, amoledBlack = true))
    }

    @Test fun `system dark remains dark without amoled preference`() {
        assertTrue(shouldUseDarkTheme(systemDark = true, amoledBlack = false))
    }

    @Test fun `light theme remains available when both switches are off`() {
        assertFalse(shouldUseDarkTheme(systemDark = false, amoledBlack = false))
    }
}
