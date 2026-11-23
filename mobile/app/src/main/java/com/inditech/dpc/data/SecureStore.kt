package com.inditech.dpc.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.inditech.dpc.data.api.PlatformJson
import com.inditech.dpc.data.model.LoanSummary
import com.inditech.dpc.data.model.LockState
import com.inditech.dpc.data.model.MqttEndpoint
import com.inditech.dpc.data.model.ProvisioningExtras
import com.inditech.dpc.data.model.TenantConfig

/** Abstraction so domain logic can be unit-tested without Android KeyStore. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun getLong(key: String, default: Long): Long
    fun getBoolean(key: String, default: Boolean): Boolean
    fun put(block: KeyValueStore.Editor.() -> Unit)

    interface Editor {
        fun putString(key: String, value: String?)
        fun putLong(key: String, value: Long)
        fun putBoolean(key: String, value: Boolean)
        fun remove(key: String)
    }
}

class SharedPrefsStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String) = prefs.getString(key, null)
    override fun getLong(key: String, default: Long) = prefs.getLong(key, default)
    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
    override fun put(block: KeyValueStore.Editor.() -> Unit) {
        val editor = prefs.edit()
        object : KeyValueStore.Editor {
            override fun putString(key: String, value: String?) { editor.putString(key, value) }
            override fun putLong(key: String, value: Long) { editor.putLong(key, value) }
            override fun putBoolean(key: String, value: Boolean) { editor.putBoolean(key, value) }
            override fun remove(key: String) { editor.remove(key) }
        }.block()
        editor.commit()
    }
}

class InMemoryStore : KeyValueStore {
    private val map = mutableMapOf<String, Any?>()
    override fun getString(key: String) = map[key] as String?
    override fun getLong(key: String, default: Long) = map[key] as Long? ?: default
    override fun getBoolean(key: String, default: Boolean) = map[key] as Boolean? ?: default
    override fun put(block: KeyValueStore.Editor.() -> Unit) {
        object : KeyValueStore.Editor {
            override fun putString(key: String, value: String?) { map[key] = value }
            override fun putLong(key: String, value: Long) { map[key] = value }
            override fun putBoolean(key: String, value: Boolean) { map[key] = value }
            override fun remove(key: String) { map.remove(key) }
        }.block()
    }
}

/**
 * Credential + configuration storage.
 *
 *  - Secrets (device JWT, provisioning token, offline unlock secret, tenant config) live in
 *    EncryptedSharedPreferences backed by the Android KeyStore (AES256_SIV keys / AES256_GCM values).
 *  - The minimal lock flag lives in device-protected (DE) storage so the lock screen can be
 *    enforced on LOCKED_BOOT_COMPLETED, before the user unlocks credential-encrypted storage.
 */
class SecureStore(private val secure: KeyValueStore, private val deviceProtected: KeyValueStore) {

    var provisioning: ProvisioningExtras?
        get() = secure.getString(K_PROVISIONING)?.let { PlatformJson.decodeFromString<ProvisioningExtras>(it) }
        set(value) = secure.put { putString(K_PROVISIONING, value?.let { PlatformJson.encodeToString(ProvisioningExtras.serializer(), it) }) }

    var deviceId: String?
        get() = secure.getString(K_DEVICE_ID)
        set(value) = secure.put { putString(K_DEVICE_ID, value) }

    var deviceJwt: String?
        get() = secure.getString(K_DEVICE_JWT)
        set(value) = secure.put { putString(K_DEVICE_JWT, value) }

    var offlineSecret: String?
        get() = secure.getString(K_OFFLINE_SECRET)
        set(value) = secure.put { putString(K_OFFLINE_SECRET, value) }

    var offlineWindowSeconds: Long
        get() = secure.getLong(K_OFFLINE_WINDOW, 21_600)
        set(value) = secure.put { putLong(K_OFFLINE_WINDOW, value) }

    var mqtt: MqttEndpoint?
        get() = secure.getString(K_MQTT)?.let { PlatformJson.decodeFromString<MqttEndpoint>(it) }
        set(value) = secure.put { putString(K_MQTT, value?.let { PlatformJson.encodeToString(MqttEndpoint.serializer(), it) }) }

    var tenantConfig: TenantConfig?
        get() = secure.getString(K_CONFIG)?.let { runCatching { PlatformJson.decodeFromString<TenantConfig>(it) }.getOrNull() }
        set(value) = secure.put { putString(K_CONFIG, value?.let { PlatformJson.encodeToString(TenantConfig.serializer(), it) }) }

    var loan: LoanSummary?
        get() = secure.getString(K_LOAN)?.let { runCatching { PlatformJson.decodeFromString<LoanSummary>(it) }.getOrNull() }
        set(value) = secure.put { putString(K_LOAN, value?.let { PlatformJson.encodeToString(LoanSummary.serializer(), it) }) }

    var fcmToken: String?
        get() = secure.getString(K_FCM)
        set(value) = secure.put { putString(K_FCM, value) }

    var imei: String?
        get() = secure.getString(K_IMEI)
        set(value) = secure.put { putString(K_IMEI, value) }

    /** Server-authoritative desired state, mirrored into DE storage for direct boot. */
    var serverState: LockState
        get() = (deviceProtected.getString(K_SERVER_STATE) ?: secure.getString(K_SERVER_STATE))
            ?.let { runCatching { LockState.valueOf(it) }.getOrNull() } ?: LockState.PENDING_ENROLLMENT
        set(value) {
            secure.put { putString(K_SERVER_STATE, value.name) }
            deviceProtected.put { putString(K_SERVER_STATE, value.name) }
        }

    /** Commands applied locally but not yet acknowledged to the server. */
    var pendingAcks: List<Pair<String, LockState>>
        get() = secure.getString(K_PENDING_ACKS)?.split(';')?.filter { it.contains('=') }?.map {
            val (id, state) = it.split('=')
            id to LockState.valueOf(state)
        } ?: emptyList()
        set(value) = secure.put { putString(K_PENDING_ACKS, value.joinToString(";") { "${it.first}=${it.second.name}" }) }

    val isEnrolled: Boolean get() = deviceId != null && deviceJwt != null

    val raw: KeyValueStore get() = secure
    val directBoot: KeyValueStore get() = deviceProtected

    companion object {
        private const val K_PROVISIONING = "provisioning_extras"
        private const val K_DEVICE_ID = "device_id"
        private const val K_DEVICE_JWT = "device_jwt"
        private const val K_OFFLINE_SECRET = "offline_secret"
        private const val K_OFFLINE_WINDOW = "offline_window_seconds"
        private const val K_MQTT = "mqtt_endpoint"
        private const val K_CONFIG = "tenant_config"
        private const val K_LOAN = "loan_summary"
        private const val K_FCM = "fcm_token"
        private const val K_IMEI = "imei"
        private const val K_SERVER_STATE = "server_state"
        private const val K_PENDING_ACKS = "pending_acks"

        fun create(context: Context): SecureStore {
            val deContext = context.createDeviceProtectedStorageContext()
            val de = SharedPrefsStore(deContext.getSharedPreferences("dpc_direct_boot", Context.MODE_PRIVATE))
            val secure = try {
                val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                SharedPrefsStore(
                    EncryptedSharedPreferences.create(
                        context,
                        "dpc_secure",
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                    ),
                )
            } catch (e: Exception) {
                // Credential-encrypted storage / KeyStore unavailable (direct boot): operate from DE flags only.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) InMemoryStore() else throw e
            }
            return SecureStore(secure, de)
        }
    }
}
