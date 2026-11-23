package com.inditech.dpc.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------- Dynamic tenant configuration

@Serializable
data class TenantConfig(
    @SerialName("tenant_id") val tenantId: String,
    val version: Int,
    val identity: Identity,
    val support: Support,
    @SerialName("repayment_settings") val repaymentSettings: RepaymentSettings,
    @SerialName("enforcement_policies") val enforcementPolicies: EnforcementPolicies,
)

@Serializable
data class Identity(
    val name: String,
    @SerialName("logo_url") val logoUrl: String,
    @SerialName("wallpaper_url") val wallpaperUrl: String? = null,
    val theme: Theme,
)

@Serializable
data class Theme(
    @SerialName("primary_color") val primaryColor: String,
    @SerialName("secondary_color") val secondaryColor: String,
    @SerialName("background_color") val backgroundColor: String,
    @SerialName("surface_color") val surfaceColor: String,
    @SerialName("text_color") val textColor: String,
    @SerialName("button_corner_radius") val buttonCornerRadius: Float,
)

@Serializable
data class Support(
    @SerialName("phone_number") val phoneNumber: String,
    @SerialName("whatsapp_number") val whatsappNumber: String,
    val email: String,
    @SerialName("allow_emergency_calls") val allowEmergencyCalls: Boolean,
    @SerialName("allow_direct_support_call") val allowDirectSupportCall: Boolean,
    @SerialName("emergency_numbers") val emergencyNumbers: List<String> = listOf("112", "911"),
)

@Serializable
enum class PaymentModeType { VIRTUAL_ACCOUNT, USSD, WEB_CHECKOUT }

@Serializable
data class PaymentMode(
    val type: PaymentModeType,
    val enabled: Boolean,
    val instructions: String,
    @SerialName("ussd_code") val ussdCode: String? = null,
    @SerialName("checkout_url") val checkoutUrl: String? = null,
    @SerialName("bank_name") val bankName: String? = null,
    @SerialName("account_number") val accountNumber: String? = null,
)

@Serializable
data class RepaymentSettings(
    @SerialName("payment_modes") val paymentModes: List<PaymentMode>,
    @SerialName("whitelisted_domains") val whitelistedDomains: List<String>,
)

@Serializable
enum class OfflineAction { HARD_LOCK, WARN, NONE }

@Serializable
data class OfflineLockFallback(
    @SerialName("max_offline_days") val maxOfflineDays: Int,
    @SerialName("action_if_exceeded") val actionIfExceeded: OfflineAction,
)

@Serializable
data class EnforcementPolicies(
    @SerialName("grace_period_hours") val gracePeriodHours: Int,
    @SerialName("warning_overlay_hours_before_lock") val warningOverlayHoursBeforeLock: Int,
    @SerialName("warning_message") val warningMessage: String,
    @SerialName("lock_message") val lockMessage: String,
    @SerialName("whitelisted_packages_when_locked") val whitelistedPackagesWhenLocked: List<String>,
    @SerialName("offline_lock_fallback") val offlineLockFallback: OfflineLockFallback,
    @SerialName("soft_lock_hours") val softLockHours: Int? = null,
    @SerialName("disable_usb_file_transfer") val disableUsbFileTransfer: Boolean? = null,
    @SerialName("block_outgoing_calls_when_soft_locked") val blockOutgoingCallsWhenSoftLocked: Boolean? = null,
)

// ---------------------------------------------------------------- Enrollment

/** Parsed from EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE (QR / ZTE). */
@Serializable
data class ProvisioningExtras(
    @SerialName("tenant_id") val tenantId: String,
    @SerialName("provisioning_token") val provisioningToken: String,
    @SerialName("api_gateway_url") val apiGatewayUrl: String,
)

@Serializable
data class HardwareIdentifiers(
    @SerialName("imei_1") val imei1: String,
    @SerialName("imei_2") val imei2: String? = null,
    @SerialName("serial_number") val serialNumber: String? = null,
    @SerialName("mac_address") val macAddress: String? = null,
)

@Serializable
data class DeviceMetadata(
    val manufacturer: String,
    val model: String,
    @SerialName("android_version") val androidVersion: String,
    @SerialName("api_level") val apiLevel: Int,
    @SerialName("build_fingerprint") val buildFingerprint: String? = null,
    @SerialName("knox_sdk_version") val knoxSdkVersion: String? = null,
    @SerialName("security_patch") val securityPatch: String? = null,
)

@Serializable
data class EnrollmentRequest(
    @SerialName("provisioning_token") val provisioningToken: String,
    @SerialName("tenant_id") val tenantId: String,
    @SerialName("hardware_identifiers") val hardwareIdentifiers: HardwareIdentifiers,
    @SerialName("device_metadata") val deviceMetadata: DeviceMetadata,
    @SerialName("fcm_token") val fcmToken: String? = null,
)

@Serializable
data class Endpoints(
    @SerialName("config_url") val configUrl: String,
    @SerialName("heartbeat_url") val heartbeatUrl: String,
)

@Serializable
data class MqttEndpoint(val url: String, val topic: String)

@Serializable
data class EnrollmentResponse(
    val status: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("tenant_id") val tenantId: String,
    @SerialName("enrolled_at") val enrolledAt: String,
    @SerialName("device_jwt") val deviceJwt: String,
    @SerialName("current_state") val currentState: LockState = LockState.ACTIVE,
    @SerialName("offline_unlock_secret") val offlineUnlockSecret: String,
    @SerialName("offline_unlock_window_seconds") val offlineUnlockWindowSeconds: Long = 21_600,
    val endpoints: Endpoints,
    val mqtt: MqttEndpoint? = null,
)

// ---------------------------------------------------------------- Heartbeat

@Serializable
enum class LockState { PENDING_ENROLLMENT, ACTIVE, GRACE, SOFT_LOCKED, HARD_LOCKED, RELEASED }

@Serializable
data class Location(val lat: Double, val lng: Double, @SerialName("accuracy_m") val accuracyM: Float? = null)

@Serializable
data class IntegrityReport(
    val rooted: Boolean = false,
    @SerialName("bootloader_unlocked") val bootloaderUnlocked: Boolean = false,
    @SerialName("adb_enabled") val adbEnabled: Boolean = false,
    @SerialName("developer_options_enabled") val developerOptionsEnabled: Boolean = false,
    @SerialName("unauthorized_binaries") val unauthorizedBinaries: List<String> = emptyList(),
    @SerialName("verified_boot_state") val verifiedBootState: String? = null,
) {
    val isCompromised: Boolean get() = rooted || bootloaderUnlocked || adbEnabled || unauthorizedBinaries.isNotEmpty()
}

@Serializable
data class CommandAck(@SerialName("command_id") val commandId: String, @SerialName("applied_state") val appliedState: LockState)

@Serializable
data class OfflineUnlockUse(val window: Long, @SerialName("used_at") val usedAt: String)

@Serializable
data class HeartbeatRequest(
    @SerialName("reported_state") val reportedState: LockState,
    @SerialName("battery_level") val batteryLevel: Int? = null,
    val charging: Boolean? = null,
    @SerialName("network_type") val networkType: String? = null,
    val location: Location? = null,
    val integrity: IntegrityReport? = null,
    @SerialName("fcm_token") val fcmToken: String? = null,
    @SerialName("config_version") val configVersion: Int? = null,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("acked_commands") val ackedCommands: List<CommandAck> = emptyList(),
    @SerialName("offline_unlocks") val offlineUnlocks: List<OfflineUnlockUse> = emptyList(),
)

@Serializable
data class PendingCommand(
    @SerialName("command_id") val commandId: String,
    val action: String? = null,
    @SerialName("target_state") val targetState: LockState,
)

@Serializable
data class LoanSummary(
    @SerialName("loan_id") val loanId: String,
    @SerialName("account_ref") val accountRef: String,
    val currency: String,
    @SerialName("principal_amount") val principalAmount: Double? = null,
    @SerialName("amount_due") val amountDue: Double,
    @SerialName("outstanding_balance") val outstandingBalance: Double,
    @SerialName("next_due_at") val nextDueAt: String,
    @SerialName("days_overdue") val daysOverdue: Int,
    val status: String,
)

@Serializable
data class PolicyOverrides(
    @SerialName("offline_lock_fallback") val offlineLockFallback: OfflineLockFallback? = null,
    @SerialName("whitelisted_packages_when_locked") val whitelistedPackagesWhenLocked: List<String>? = null,
)

@Serializable
data class HeartbeatResponse(
    @SerialName("server_time") val serverTime: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("desired_state") val desiredState: LockState,
    @SerialName("lock_status") val lockStatus: String,
    @SerialName("pending_command") val pendingCommand: PendingCommand? = null,
    @SerialName("config_version") val configVersion: Int,
    @SerialName("config_url") val configUrl: String? = null,
    @SerialName("heartbeat_interval_seconds") val heartbeatIntervalSeconds: Long = 900,
    val loan: LoanSummary? = null,
    @SerialName("policy_overrides") val policyOverrides: PolicyOverrides? = null,
)

@Serializable
data class AckRequest(@SerialName("applied_state") val appliedState: LockState)

/** Command delivered by FCM data message or MQTT. */
@Serializable
data class DeviceCommand(
    val commandType: String,
    val commandId: String,
    val targetState: LockState,
    val timestamp: String? = null,
)
