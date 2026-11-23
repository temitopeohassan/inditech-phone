package com.inditech.dpc.domain

import com.inditech.dpc.data.KeyValueStore

/** Source of time readings — injectable for tests. */
interface TimeSource {
    /** Monotonic, includes deep sleep, resets on reboot (SystemClock.elapsedRealtime). */
    fun elapsedRealtime(): Long
    /** Wall clock (System.currentTimeMillis) — untrusted, user-adjustable. */
    fun wallClock(): Long
    /** Settings.Global.BOOT_COUNT — increments on each boot. */
    fun bootCount(): Int
}

/**
 * Tamper-resistant time + offline-duration tracking.
 *
 *  - On every verified server response we persist `trustedServerTime - elapsedRealtime()`.
 *    Secure "now" = elapsedRealtime() + offset, immune to local clock changes.
 *  - A reboot resets elapsedRealtime (detected via boot count / elapsed going backwards).
 *    The offset is then invalid, so we fall back to max(wall clock, last trusted time) and
 *    keep accumulating offline time conservatively across reboots.
 */
class SecureClock(private val store: KeyValueStore, private val time: TimeSource) {

    fun onTrustedServerTime(serverEpochMillis: Long) {
        val elapsed = time.elapsedRealtime()
        store.put {
            putLong(K_OFFSET, serverEpochMillis - elapsed)
            putLong(K_BOOT, time.bootCount().toLong())
            putLong(K_LAST_TRUSTED, serverEpochMillis)
            putLong(K_LAST_ELAPSED, elapsed)
            putLong(K_LAST_WALL, time.wallClock())
            putLong(K_OFFLINE_ACC, 0L)
        }
    }

    private fun sameBoot(): Boolean {
        val savedBoot = store.getLong(K_BOOT, -1)
        val lastElapsed = store.getLong(K_LAST_ELAPSED, -1)
        return savedBoot == time.bootCount().toLong() && time.elapsedRealtime() >= lastElapsed
    }

    fun hasTrustedTime(): Boolean = store.getLong(K_LAST_TRUSTED, -1) > 0

    /** Best estimate of the real current time in epoch millis. */
    fun now(): Long {
        if (!hasTrustedTime()) return time.wallClock()
        return if (sameBoot()) {
            time.elapsedRealtime() + store.getLong(K_OFFSET, 0)
        } else {
            maxOf(time.wallClock(), store.getLong(K_LAST_TRUSTED, 0) + store.getLong(K_OFFLINE_ACC, 0))
        }
    }

    /**
     * Advances and returns the accumulated time spent without a verified server sync.
     * Call periodically (watchdog) — progress is persisted so it survives reboots.
     */
    fun tickOfflineDuration(): Long {
        if (!hasTrustedTime()) return 0
        val elapsed = time.elapsedRealtime()
        val wall = time.wallClock()
        val rebooted = !sameBoot()
        val lastElapsed = store.getLong(K_LAST_ELAPSED, elapsed)
        val lastWall = store.getLong(K_LAST_WALL, wall)
        val delta = if (!rebooted) {
            elapsed - lastElapsed
        } else {
            // Rebooted: uptime since boot is a guaranteed lower bound; the wall-clock delta is used
            // when larger (manual date/time changes are blocked by DISALLOW_CONFIG_DATE_TIME).
            maxOf(elapsed, wall - lastWall, 0L)
        }
        val acc = store.getLong(K_OFFLINE_ACC, 0) + maxOf(delta, 0L)
        val estimatedNow = if (!rebooted) {
            elapsed + store.getLong(K_OFFSET, 0)
        } else {
            maxOf(wall, store.getLong(K_LAST_TRUSTED, 0) + acc)
        }
        store.put {
            putLong(K_OFFLINE_ACC, acc)
            putLong(K_LAST_ELAPSED, elapsed)
            putLong(K_LAST_WALL, wall)
            putLong(K_BOOT, time.bootCount().toLong())
            putLong(K_OFFSET, estimatedNow - elapsed) // re-anchor for this boot
        }
        return acc
    }

    fun offlineDurationMillis(): Long = store.getLong(K_OFFLINE_ACC, 0)

    companion object {
        private const val K_OFFSET = "TRUSTED_TIME_OFFSET"
        private const val K_BOOT = "TRUSTED_BOOT_COUNT"
        private const val K_LAST_TRUSTED = "LAST_TRUSTED_SERVER_TIME"
        private const val K_LAST_ELAPSED = "LAST_ELAPSED_REALTIME"
        private const val K_LAST_WALL = "LAST_WALL_CLOCK"
        private const val K_OFFLINE_ACC = "OFFLINE_ACCUMULATED_MS"
    }
}
