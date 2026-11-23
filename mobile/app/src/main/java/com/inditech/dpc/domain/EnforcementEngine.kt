package com.inditech.dpc.domain

import com.inditech.dpc.data.KeyValueStore
import com.inditech.dpc.data.model.IntegrityReport
import com.inditech.dpc.data.model.LockState
import com.inditech.dpc.data.model.OfflineAction
import com.inditech.dpc.data.model.OfflineLockFallback
import com.inditech.dpc.data.model.OfflineUnlockUse
import java.time.Instant

/** Why the device is in its effective state — drives copy on the lock screens. */
enum class LockReason { NONE, PAYMENT, TAMPER, OFFLINE }

data class EffectiveState(
    val state: LockState,
    val reason: LockReason,
    val offlineMillis: Long,
    val temporaryUnlockUntil: Long?,
)

/**
 * Local policy engine: combines the server-authoritative state with on-device
 * safeguards (offline heartbeat lockout, tamper detection, offline unlock tokens).
 */
class EnforcementEngine(
    private val store: KeyValueStore,
    private val clock: SecureClock,
) {
    fun evaluate(serverState: LockState, fallback: OfflineLockFallback?, integrity: IntegrityReport?): EffectiveState {
        val offline = clock.offlineDurationMillis()
        val now = clock.now()
        val tempUntil = store.getLong(K_TEMP_UNLOCK_UNTIL, 0).takeIf { it > now }

        if (serverState == LockState.RELEASED || serverState == LockState.PENDING_ENROLLMENT) {
            return EffectiveState(serverState, LockReason.NONE, offline, null)
        }
        if (integrity?.isCompromised == true || store.getBoolean(K_TAMPERED, false)) {
            if (integrity?.isCompromised == true) store.put { putBoolean(K_TAMPERED, true) }
            return EffectiveState(LockState.HARD_LOCKED, LockReason.TAMPER, offline, null)
        }
        if (fallback != null && fallback.maxOfflineDays > 0) {
            val limit = fallback.maxOfflineDays * 86_400_000L
            if (offline >= limit && tempUntil == null) {
                return when (fallback.actionIfExceeded) {
                    OfflineAction.HARD_LOCK -> EffectiveState(LockState.HARD_LOCKED, LockReason.OFFLINE, offline, null)
                    OfflineAction.WARN -> EffectiveState(
                        if (serverState == LockState.ACTIVE) LockState.GRACE else serverState, LockReason.OFFLINE, offline, null,
                    )
                    OfflineAction.NONE -> EffectiveState(serverState, LockReason.PAYMENT, offline, null)
                }
            }
        }
        if (tempUntil != null && (serverState == LockState.HARD_LOCKED || serverState == LockState.SOFT_LOCKED)) {
            return EffectiveState(LockState.ACTIVE, LockReason.NONE, offline, tempUntil)
        }
        val reason = if (serverState == LockState.ACTIVE) LockReason.NONE else LockReason.PAYMENT
        return EffectiveState(serverState, reason, offline, null)
    }

    /**
     * Redeems a support-issued offline unlock code. Each window can be used once.
     * Grants a temporary unlock and resets the offline counter's lockout.
     */
    fun redeemOfflineCode(secretHex: String, deviceId: String, code: String, windowSeconds: Long): Boolean {
        val nowSec = clock.now() / 1000
        val window = OfflineUnlockCode.verify(secretHex, deviceId, code, nowSec, windowSeconds) ?: return false
        val used = usedWindows()
        if (window in used) return false
        val until = clock.now() + TEMP_UNLOCK_MILLIS
        store.put {
            putString(K_USED_WINDOWS, (used + window).takeLast(20).joinToString(","))
            putLong(K_TEMP_UNLOCK_UNTIL, until)
            putBoolean(K_TAMPERED, false)
            val pending = store.getString(K_PENDING_REDEMPTIONS).orEmpty()
            putString(K_PENDING_REDEMPTIONS, "$pending;$window@${Instant.ofEpochMilli(clock.now())}".trimStart(';'))
        }
        return true
    }

    /** Redemptions to report on the next heartbeat. */
    fun drainRedemptions(): List<OfflineUnlockUse> {
        val raw = store.getString(K_PENDING_REDEMPTIONS).orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split(';').mapNotNull {
            val parts = it.split('@')
            if (parts.size == 2) OfflineUnlockUse(parts[0].toLong(), parts[1]) else null
        }
    }

    fun clearRedemptions() = store.put { remove(K_PENDING_REDEMPTIONS) }

    /** Server confirmed the device is healthy: clear local tamper latch & temporary unlock. */
    fun onServerSync(serverState: LockState) {
        store.put {
            if (serverState == LockState.ACTIVE || serverState == LockState.GRACE) remove(K_TEMP_UNLOCK_UNTIL)
        }
    }

    fun clearTamperLatch() = store.put { putBoolean(K_TAMPERED, false) }

    private fun usedWindows(): List<Long> =
        store.getString(K_USED_WINDOWS)?.split(',')?.mapNotNull { it.toLongOrNull() } ?: emptyList()

    companion object {
        const val TEMP_UNLOCK_MILLIS = 24 * 60 * 60 * 1000L
        private const val K_TEMP_UNLOCK_UNTIL = "temp_unlock_until"
        private const val K_USED_WINDOWS = "offline_used_windows"
        private const val K_TAMPERED = "tamper_latch"
        private const val K_PENDING_REDEMPTIONS = "pending_offline_redemptions"
    }
}
