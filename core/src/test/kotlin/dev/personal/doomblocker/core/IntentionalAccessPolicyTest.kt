package dev.personal.doomblocker.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentionalAccessPolicyTest {
    @Test fun `requires an allowed duration a concrete reason and completed wait`() {
        assertTrue(IntentionalAccessPolicy.canStart(1, "Mesaja yanıt ver", 0))
        assertFalse(IntentionalAccessPolicy.canStart(1, "   ", 0))
        assertFalse(IntentionalAccessPolicy.canStart(1, "Mesaja yanıt ver", 1))
        assertTrue(IntentionalAccessPolicy.canStart(5, "Mesaja yanıt ver", 0))
        assertTrue(IntentionalAccessPolicy.canStart(10, "Haritayı kontrol et", 0))
        assertTrue(IntentionalAccessPolicy.canStart(15, "Bileti göster", 0))
        assertFalse(IntentionalAccessPolicy.canStart(20, "Mesaja yanıt ver", 0))
        assertFalse(IntentionalAccessPolicy.canStart(5, "   ", 0))
        assertFalse(IntentionalAccessPolicy.canStart(5, "Mesaja yanıt ver", 1))
    }

    @Test fun `rejects a reason longer than the persisted engine limit`() {
        assertFalse(
            IntentionalAccessPolicy.canStart(
                5,
                "a".repeat(IntentionalAccessPolicy.maxReasonLength + 1),
                0,
            ),
        )
    }
}
