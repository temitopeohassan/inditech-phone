package com.inditech.dpc.policy

import com.inditech.dpc.data.SecureStore
import com.inditech.dpc.data.model.IntegrityReport
import com.inditech.dpc.data.model.LoanSummary
import com.inditech.dpc.data.model.LockState
import com.inditech.dpc.data.model.TenantConfig
import com.inditech.dpc.domain.EffectiveState
import com.inditech.dpc.domain.EnforcementEngine
import com.inditech.dpc.domain.LockReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class EnrollmentStep { NOT_PROVISIONED, CONNECTING, FETCHING_BRANDING, APPLYING_POLICIES, DONE, FAILED }

/** Everything the UI renders. */
data class DeviceUiState(
    val enrollmentStep: EnrollmentStep = EnrollmentStep.NOT_PROVISIONED,
    val enrollmentError: String? = null,
    val config: TenantConfig? = null,
    val effective: EffectiveState = EffectiveState(LockState.PENDING_ENROLLMENT, LockReason.NONE, 0, null),
    val loan: LoanSummary? = null,
    val deviceId: String? = null,
    val imei: String? = null,
    val lastSyncAt: Long? = null,
    val syncing: Boolean = false,
    val isDeviceOwner: Boolean = false,
)

/**
 * Single place where the effective lock state is computed and pushed to both
 * the hardware (DevicePolicyManager) and the UI.
 */
class Enforcer(
    private val store: SecureStore,
    private val engine: EnforcementEngine,
    private val security: DeviceSecurityManager,
) {
    private val _state = MutableStateFlow(DeviceUiState())
    val state: StateFlow<DeviceUiState> = _state.asStateFlow()

    private var lastIntegrity: IntegrityReport? = null
    private var appliedState: LockState? = null

    fun updateIntegrity(report: IntegrityReport) {
        lastIntegrity = report
    }

    fun setEnrollment(step: EnrollmentStep, error: String? = null) = _state.update { it.copy(enrollmentStep = step, enrollmentError = error) }

    fun setSyncing(syncing: Boolean, at: Long? = null) = _state.update { it.copy(syncing = syncing, lastSyncAt = at ?: it.lastSyncAt) }

    /** Recomputes and enforces the effective state. Idempotent. */
    @Synchronized
    fun apply(): EffectiveState {
        val config = store.tenantConfig
        val effective = engine.evaluate(
            serverState = store.serverState,
            fallback = config?.enforcementPolicies?.offlineLockFallback,
            integrity = lastIntegrity,
        )
        if (appliedState != effective.state) {
            security.applyLockState(effective.state, config?.enforcementPolicies)
            appliedState = effective.state
        }
        val provisioned = store.provisioning != null
        _state.update {
            val step = when {
                store.isEnrolled -> EnrollmentStep.DONE
                it.enrollmentStep == EnrollmentStep.DONE -> if (provisioned) EnrollmentStep.CONNECTING else EnrollmentStep.NOT_PROVISIONED
                it.enrollmentStep == EnrollmentStep.NOT_PROVISIONED && provisioned -> EnrollmentStep.CONNECTING
                else -> it.enrollmentStep
            }
            it.copy(
                enrollmentStep = step,
                config = config,
                effective = effective,
                loan = store.loan,
                deviceId = store.deviceId,
                imei = store.imei,
                isDeviceOwner = security.isDeviceOwner,
            )
        }
        return effective
    }

    /** Direct-boot path: only DE storage may be readable. */
    fun reapplyFromStorage() {
        appliedState = null
        apply()
    }
}
