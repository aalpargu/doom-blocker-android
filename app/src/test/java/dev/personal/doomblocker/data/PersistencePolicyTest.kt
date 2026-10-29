package dev.personal.doomblocker.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistencePolicyTest {
    @Test fun `steady foreground checkpoints can be batched`() {
        assertFalse(PersistencePolicy.shouldPersistImmediately("target", "target", false))
    }

    @Test fun `foreground boundaries are persisted immediately`() {
        assertTrue(PersistencePolicy.shouldPersistImmediately(null, "target", false))
        assertTrue(PersistencePolicy.shouldPersistImmediately("target", null, false))
    }

    @Test fun `blocking decisions are always persisted immediately`() {
        assertTrue(PersistencePolicy.shouldPersistImmediately("target", "target", true))
    }
}
