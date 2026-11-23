package com.inditech.dpc.data

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.inditech.dpc.BuildConfig
import com.inditech.dpc.admin.EnterpriseAdminReceiver
import com.inditech.dpc.data.api.ApiClientFactory
import com.inditech.dpc.data.api.PlatformApi
import com.inditech.dpc.data.model.AckRequest
import com.inditech.dpc.data.model.CommandAck
import com.inditech.dpc.data.model.DeviceCommand
import com.inditech.dpc.data.model.DeviceMetadata
import com.inditech.dpc.data.model.EnrollmentRequest
import com.inditech.dpc.data.model.HardwareIdentifiers
import com.inditech.dpc.data.model.HeartbeatRequest
import com.inditech.dpc.data.model.LockState
import com.inditech.dpc.data.model.ProvisioningExtras
import com.inditech.dpc.domain.EnforcementEngine
import com.inditech.dpc.domain.IntegrityChecker
import com.inditech.dpc.domain.SecureClock
import com.inditech.dpc.policy.Enforcer
import com.inditech.dpc.policy.EnrollmentStep
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response
import java.security.MessageDigest

sealed interface SyncResult {
    data object Ok : SyncResult
    data object NotEnrolled : SyncResult
    data class Failed(val message: String, val retryable: Boolean = true) : SyncResult
}

/**
 * Orchestrates enrollment (steps 3–4 of the lifecycle), heartbeats, command
 * handling and acknowledgements against the platform API.
 */
class DeviceRepository(
    private val context: Context,
    private val store: SecureStore,
    private val clock: SecureClock,
    private val engine: EnforcementEngine,
    private val integrity: IntegrityChecker,
    private val enforcer: Enforcer,
) {
    private val mutex = Mutex()
    @Volatile private var cachedApi: Pair<String, PlatformApi>? = null

    private fun api(): PlatformApi {
        val base = ApiClientFactory.normalizeBaseUrl(store.provisioning?.apiGatewayUrl ?: BuildConfig.API_BASE_URL)
        cachedApi?.let { if (it.first == base) return it.second }
        return ApiClientFactory.create(base, BuildConfig.DEBUG).also { cachedApi = base to it }
    }

    private fun trustServerTime(response: Response<*>) {
        response.headers().getDate("Date")?.time?.let { clock.onTrustedServerTime(it) }
    }

    /** Manual / fallback provisioning (6-tap QR unavailable, or agent-assisted setup). */
    fun saveManualProvisioning(extras: ProvisioningExtras) {
        store.provisioning = extras
        enforcer.setEnrollment(EnrollmentStep.CONNECTING)
    }

    suspend fun enroll(): SyncResult = mutex.withLock {
        if (store.isEnrolled) return SyncResult.Ok
        val extras = store.provisioning ?: run {
            enforcer.setEnrollment(EnrollmentStep.NOT_PROVISIONED)
            return SyncResult.NotEnrolled
        }
        try {
            enforcer.setEnrollment(EnrollmentStep.CONNECTING)
            val hw = hardwareIdentifiers()
            store.imei = hw.imei1
            val response = api().enroll(
                EnrollmentRequest(
                    provisioningToken = extras.provisioningToken,
                    tenantId = extras.tenantId,
                    hardwareIdentifiers = hw,
                    deviceMetadata = DeviceMetadata(
                        manufacturer = Build.MANUFACTURER,
                        model = Build.MODEL,
                        androidVersion = Build.VERSION.RELEASE,
                        apiLevel = Build.VERSION.SDK_INT,
                        buildFingerprint = Build.FINGERPRINT.take(255),
                        securityPatch = Build.VERSION.SECURITY_PATCH,
                    ),
                    fcmToken = store.fcmToken,
                ),
            )
            val body = response.body()
            if (!response.isSuccessful || body == null) {
                val retryable = response.code() >= 500 || response.code() == 429
                val msg = "Enrollment rejected (${response.code()})"
                enforcer.setEnrollment(EnrollmentStep.FAILED, msg)
                return SyncResult.Failed(msg, retryable)
            }
            trustServerTime(response)
            store.deviceId = body.deviceId
            store.deviceJwt = body.deviceJwt
            store.offlineSecret = body.offlineUnlockSecret
            store.offlineWindowSeconds = body.offlineUnlockWindowSeconds
            store.mqtt = body.mqtt
            store.serverState = body.currentState

            enforcer.setEnrollment(EnrollmentStep.FETCHING_BRANDING)
            fetchConfig()

            enforcer.setEnrollment(EnrollmentStep.APPLYING_POLICIES)
            enforcer.apply()
            enforcer.setEnrollment(EnrollmentStep.DONE)
            SyncResult.Ok
        } catch (e: Exception) {
            Log.w(TAG, "enrollment failed", e)
            enforcer.setEnrollment(EnrollmentStep.FAILED, e.message ?: "Network error")
            SyncResult.Failed(e.message ?: "Network error")
        }
    }

    /** Step 3: dynamic branding + policy fetch, then aggressive asset pre-caching. */
    suspend fun fetchConfig(): Boolean {
        val tenantId = store.provisioning?.tenantId ?: store.tenantConfig?.tenantId ?: return false
        val bearer = "Bearer " + (store.deviceJwt ?: store.provisioning?.provisioningToken ?: return false)
        val response = api().tenantConfig(tenantId, bearer)
        if (response.code() == 304) return true
        val cfg = response.body() ?: return false
        store.tenantConfig = cfg
        precacheTenantAssets(listOfNotNull(cfg.identity.logoUrl, cfg.identity.wallpaperUrl))
        return true
    }

    private fun precacheTenantAssets(urls: List<String>) {
        val loader = ImageLoader.Builder(context)
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(context.cacheDir.resolve("tenant_assets"))
                    .minimumMaxSizeBytes(50L * 1024 * 1024)
                    .build()
            }
            .build()
        urls.forEach { url ->
            loader.enqueue(
                ImageRequest.Builder(context)
                    .data(url)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .listener(
                        onSuccess = { _, _ -> Log.d("Cache", "Tenant asset cached: $url") },
                        onError = { _, result -> Log.e("Cache", "Failed to cache asset: ${result.throwable}") },
                    )
                    .build(),
            )
        }
    }

    /** Telemetry check-in + policy override reception. */
    suspend fun heartbeat(): SyncResult = mutex.withLock {
        val deviceId = store.deviceId ?: return SyncResult.NotEnrolled
        val jwt = store.deviceJwt ?: return SyncResult.NotEnrolled
        enforcer.setSyncing(true)
        try {
            val report = integrity.check()
            enforcer.updateIntegrity(report)
            val acks = store.pendingAcks
            val redemptions = engine.drainRedemptions()
            val effective = enforcer.apply()
            val response = api().heartbeat(
                deviceId,
                "Bearer $jwt",
                HeartbeatRequest(
                    reportedState = effective.state,
                    batteryLevel = batteryLevel(),
                    networkType = networkType(),
                    integrity = report,
                    fcmToken = store.fcmToken,
                    configVersion = store.tenantConfig?.version,
                    appVersion = BuildConfig.VERSION_NAME,
                    ackedCommands = acks.map { CommandAck(it.first, it.second) },
                    offlineUnlocks = redemptions,
                ),
            )
            val body = response.body()
            if (!response.isSuccessful || body == null) {
                enforcer.setSyncing(false)
                return SyncResult.Failed("Heartbeat rejected (${response.code()})", response.code() >= 500)
            }
            trustServerTime(response)
            store.pendingAcks = store.pendingAcks - acks.toSet()
            if (redemptions.isNotEmpty()) engine.clearRedemptions()
            store.serverState = body.desiredState
            store.loan = body.loan
            engine.onServerSync(body.desiredState)
            if (body.configUrl != null) runCatching { fetchConfig() }
            val applied = enforcer.apply()
            body.pendingCommand?.let { cmd ->
                if (cmd.targetState == body.desiredState) acknowledge(cmd.commandId, applied.state)
            }
            enforcer.setSyncing(false, clock.now())
            SyncResult.Ok
        } catch (e: Exception) {
            Log.w(TAG, "heartbeat failed", e)
            clock.tickOfflineDuration()
            enforcer.apply()
            enforcer.setSyncing(false)
            SyncResult.Failed(e.message ?: "Network error")
        }
    }

    /** FCM / MQTT command: enforce immediately (sub-3s target), then acknowledge. */
    suspend fun handleCommand(command: DeviceCommand) {
        if (!store.isEnrolled) return
        store.serverState = command.targetState
        val applied = enforcer.apply()
        acknowledge(command.commandId, applied.state)
    }

    private suspend fun acknowledge(commandId: String, applied: LockState) {
        val deviceId = store.deviceId ?: return
        val jwt = store.deviceJwt ?: return
        val ok = runCatching { api().ack(deviceId, commandId, "Bearer $jwt", AckRequest(applied)).isSuccessful }.getOrDefault(false)
        if (!ok) store.pendingAcks = (store.pendingAcks.filterNot { it.first == commandId } + (commandId to applied))
    }

    /** Offline unlock token entry on the lock screen. */
    fun redeemOfflineCode(code: String): Boolean {
        val secret = store.offlineSecret ?: return false
        val deviceId = store.deviceId ?: return false
        val ok = engine.redeemOfflineCode(secret, deviceId, code, store.offlineWindowSeconds)
        if (ok) enforcer.apply()
        return ok
    }

    /** Watchdog tick (no network needed). */
    fun watchdog() {
        if (!store.isEnrolled) return
        clock.tickOfflineDuration()
        enforcer.updateIntegrity(integrity.check())
        enforcer.apply()
    }

    fun onNewFcmToken(token: String) {
        store.fcmToken = token
    }

    // ------------------------------------------------------------------ device identity

    @SuppressLint("HardwareIds", "MissingPermission")
    private fun hardwareIdentifiers(): HardwareIdentifiers {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val imei1 = runCatching { if (Build.VERSION.SDK_INT >= 26) tm.getImei(0) else null }.getOrNull()
        val imei2 = runCatching { if (Build.VERSION.SDK_INT >= 26 && tm.phoneCount > 1) tm.getImei(1) else null }.getOrNull()
        val serial = runCatching { Build.getSerial() }.getOrNull()?.takeIf { it != Build.UNKNOWN }
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val mac = runCatching { dpm.getWifiMacAddress(ComponentName(context, EnterpriseAdminReceiver::class.java)) }.getOrNull()
        return HardwareIdentifiers(
            imei1 = imei1?.takeIf { it.length in 14..17 } ?: pseudoImei(),
            imei2 = imei2?.takeIf { it.length in 14..17 },
            serialNumber = serial,
            macAddress = mac,
        )
    }

    /** Devices without telephony (tablets / emulators): stable 15-digit identifier derived from ANDROID_ID. */
    @SuppressLint("HardwareIds")
    private fun pseudoImei(): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        val digest = MessageDigest.getInstance("SHA-256").digest(androidId.toByteArray())
        val digits = digest.joinToString("") { ((it.toInt() and 0xff) % 10).toString() }
        return "99" + digits.take(13)
    }

    private fun batteryLevel(): Int? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) level * 100 / scale else null
    }

    private fun networkType(): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "NONE"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            else -> "OTHER"
        }
    }

    companion object {
        private const val TAG = "DeviceRepository"
    }
}
