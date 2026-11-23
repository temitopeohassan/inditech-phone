package com.inditech.dpc.domain

import com.inditech.dpc.data.InMemoryStore
import com.inditech.dpc.data.model.IntegrityReport
import com.inditech.dpc.data.model.LockState
import com.inditech.dpc.data.model.OfflineAction
import com.inditech.dpc.data.model.OfflineLockFallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeTime(var elapsed: Long = 1_000, var wall: Long = 1_700_000_000_000, var boots: Int = 1) : TimeSource {
    override fun elapsedRealtime() = elapsed
    override fun wallClock() = wall
    override fun bootCount() = boots
    fun advance(ms: Long) { elapsed += ms; wall += ms }
}

class EnforcementEngineTest {
    private val day = 86_400_000L
    private val store = InMemoryStore()
    private val time = FakeTime()
    private val clock = SecureClock(store, time)
    private val engine = EnforcementEngine(store, clock)
    private val fallback = OfflineLockFallback(3, OfflineAction.HARD_LOCK)

    @Test
    fun `secure clock ignores local wall clock manipulation`() {
        clock.onTrustedServerTime(1_700_000_000_000)
        time.advance(60_000)
        time.wall -= 10 * day // user winds the clock back
        assertEquals(1_700_000_060_000, clock.now())
    }

    @Test
    fun `offline duration survives reboots and triggers local hard lock`() {
        clock.onTrustedServerTime(time.wall)
        time.advance(day); clock.tickOfflineDuration()
        // reboot: elapsedRealtime resets, boot count increments
        time.boots++; time.elapsed = 5_000; time.wall += day
        clock.tickOfflineDuration()
        time.advance(day + 1); clock.tickOfflineDuration()
        assertTrue(clock.offlineDurationMillis() >= 3 * day)
        val effective = engine.evaluate(LockState.ACTIVE, fallback, null)
        assertEquals(LockState.HARD_LOCKED, effective.state)
        assertEquals(LockReason.OFFLINE, effective.reason)
    }

    @Test
    fun `server sync resets the offline counter`() {
        clock.onTrustedServerTime(time.wall)
        time.advance(4 * day); clock.tickOfflineDuration()
        clock.onTrustedServerTime(time.wall)
        assertEquals(LockState.ACTIVE, engine.evaluate(LockState.ACTIVE, fallback, null).state)
    }

    @Test
    fun `tamper detection latches a hard lock`() {
        val compromised = IntegrityReport(rooted = true)
        assertEquals(LockReason.TAMPER, engine.evaluate(LockState.ACTIVE, fallback, compromised).reason)
        // latch persists even if the next check looks clean
        assertEquals(LockState.HARD_LOCKED, engine.evaluate(LockState.ACTIVE, fallback, IntegrityReport()).state)
    }

    @Test
    fun `offline unlock code grants a single-use temporary unlock`() {
        val secret = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        clock.onTrustedServerTime(time.wall)
        val window = OfflineUnlockCode.window(clock.now() / 1000, 21_600)
        val code = OfflineUnlockCode.generate(secret, "dev-1", window)

        assertFalse(engine.redeemOfflineCode(secret, "dev-1", "00000000", 21_600))
        assertTrue(engine.redeemOfflineCode(secret, "dev-1", code, 21_600))
        assertFalse("codes are single use", engine.redeemOfflineCode(secret, "dev-1", code, 21_600))

        val effective = engine.evaluate(LockState.HARD_LOCKED, fallback, null)
        assertEquals(LockState.ACTIVE, effective.state)
        assertEquals(1, engine.drainRedemptions().size)

        time.advance(EnforcementEngine.TEMP_UNLOCK_MILLIS + 1)
        assertEquals(LockState.HARD_LOCKED, engine.evaluate(LockState.HARD_LOCKED, null, null).state)
    }

    @Test
    fun `released devices are never locked locally`() {
        assertEquals(LockState.RELEASED, engine.evaluate(LockState.RELEASED, fallback, IntegrityReport(rooted = true)).state)
    }
}
