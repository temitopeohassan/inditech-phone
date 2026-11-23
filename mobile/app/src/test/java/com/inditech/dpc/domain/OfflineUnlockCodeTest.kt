package com.inditech.dpc.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineUnlockCodeTest {
    private val secret = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
    private val device = "dev_7f9c3a1b8e2d4f0"

    @Test
    fun `matches the backend cross-platform test vector`() {
        // backend/tests/offlineUnlock.test.ts asserts the same value
        assertEquals("64754689", OfflineUnlockCode.generate(secret, device, 123456))
    }

    @Test
    fun `accepts current and previous window only`() {
        val windowSeconds = 21_600L
        val now = 1_700_000_000L
        val w = OfflineUnlockCode.window(now, windowSeconds)
        assertEquals(w, OfflineUnlockCode.verify(secret, device, OfflineUnlockCode.generate(secret, device, w), now, windowSeconds))
        assertNotNull(OfflineUnlockCode.verify(secret, device, OfflineUnlockCode.generate(secret, device, w - 1), now, windowSeconds))
        assertNull(OfflineUnlockCode.verify(secret, device, OfflineUnlockCode.generate(secret, device, w - 2), now, windowSeconds))
        assertNull(OfflineUnlockCode.verify(secret, "other", OfflineUnlockCode.generate(secret, device, w), now, windowSeconds))
        assertNull(OfflineUnlockCode.verify(secret, device, "123", now, windowSeconds))
    }
}
