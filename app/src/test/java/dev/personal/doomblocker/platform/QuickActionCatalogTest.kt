package dev.personal.doomblocker.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickActionCatalogTest {
    @Test fun `launcher exposes exactly the three requested shortcuts with unique identities`() {
        assertEquals(3, QuickActionCatalog.specs.size)
        assertEquals(3, QuickActionCatalog.specs.map { it.id }.toSet().size)
        assertEquals(3, QuickActionCatalog.specs.map { it.action }.toSet().size)
        assertTrue(QuickActionCatalog.specs.any { it.action == QuickActionCatalog.ACTION_FOCUS_30 })
        assertTrue(QuickActionCatalog.specs.any { it.action == QuickActionCatalog.ACTION_LOCK_TODAY })
        assertTrue(QuickActionCatalog.specs.any { it.action == QuickActionCatalog.ACTION_ADD_APP })
    }
}
