package com.inditech.dpc.policy

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.app.admin.FactoryResetProtectionPolicy
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.util.Log
import com.inditech.dpc.admin.EnterpriseAdminReceiver
import com.inditech.dpc.data.model.EnforcementPolicies
import com.inditech.dpc.data.model.LockState
import com.inditech.dpc.ui.MainActivity

/**
 * Applies DevicePolicyManager / UserManager hardening and enforces lock states.
 * Every call is a no-op (logged) when the app is not the Device Owner, so the
 * UI can still be exercised on development devices.
 */
class DeviceSecurityManager(private val context: Context) {

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponent = ComponentName(context, EnterpriseAdminReceiver::class.java)
    private val prefs = context.createDeviceProtectedStorageContext().getSharedPreferences("dpc_policy", Context.MODE_PRIVATE)

    val isDeviceOwner: Boolean get() = dpm.isDeviceOwnerApp(context.packageName)

    /**
     * Applies complete security profile for financing and multi-tenant isolation.
     */
    fun applyFinancingSecurityPolicies(
        authorizedAdminAccountIds: List<String>,
        kioskPackageName: String = context.packageName,
        vpnPackageName: String? = null,
        disableUsbFileTransfer: Boolean = true,
    ) {
        if (!isDeviceOwner) {
            Log.w(TAG, "Application is not executing as Device Owner — skipping hardening")
            return
        }

        // 1. System restrictions via UserManager
        val restrictions = buildList {
            add(UserManager.DISALLOW_FACTORY_RESET)
            add(UserManager.DISALLOW_SAFE_BOOT)
            add(UserManager.DISALLOW_DEBUGGING_FEATURES)
            if (disableUsbFileTransfer) add(UserManager.DISALLOW_USB_FILE_TRANSFER)
            add(UserManager.DISALLOW_CONFIG_DATE_TIME)
            add(UserManager.DISALLOW_ADD_USER)
            add(UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA)
            add(UserManager.DISALLOW_APPS_CONTROL)
            add(UserManager.DISALLOW_UNINSTALL_APPS)
            add(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
        }
        restrictions.forEach { runCatching { dpm.addUserRestriction(adminComponent, it) } }
        runCatching { dpm.setUninstallBlocked(adminComponent, context.packageName, true) }
        // Network time only; prevents clock manipulation from bypassing deadlines.
        runCatching { dpm.setAutoTimeRequired(adminComponent, true) }

        // 2. Enterprise Factory Reset Protection (EFRP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && authorizedAdminAccountIds.isNotEmpty()) {
            runCatching {
                val frpPolicy = FactoryResetProtectionPolicy.Builder()
                    .setFactoryResetProtectionAccounts(authorizedAdminAccountIds)
                    .setFactoryResetProtectionEnabled(true)
                    .build()
                dpm.setFactoryResetProtectionPolicy(adminComponent, frpPolicy)
            }
        }

        // 3. Dedicated Lock Task Mode (kiosk) allow-list
        dpm.setLockTaskPackages(adminComponent, arrayOf(context.packageName, kioskPackageName).distinct().toTypedArray())

        // 4. Always-On VPN with network lockout, if configured
        vpnPackageName?.let { vpnPackage ->
            runCatching { dpm.setAlwaysOnVpnPackage(adminComponent, vpnPackage, /* lockdownEnabled = */ true) }
        }

        // 5. Device passcode rules
        @Suppress("DEPRECATION")
        runCatching { dpm.setPasswordQuality(adminComponent, DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX) }
        runCatching { dpm.setMaximumFailedPasswordsForWipe(adminComponent, 10) }
    }

    /** Enforces one of the four lifecycle phases on the hardware. */
    fun applyLockState(state: LockState, policies: EnforcementPolicies?) {
        if (!isDeviceOwner) {
            Log.i(TAG, "applyLockState($state) — not device owner, UI-only enforcement")
            return
        }
        val whitelist = (policies?.whitelistedPackagesWhenLocked ?: emptyList()).filter { isInstalled(it) }
        when (state) {
            LockState.HARD_LOCKED -> {
                setKiosk(enabled = true, whitelist = whitelist)
                suspendNonEssentialApps(whitelist)
                runCatching { dpm.setStatusBarDisabled(adminComponent, true) }
                setOutgoingCallsBlocked(false) // emergency + support calls go through the whitelisted dialer
            }
            LockState.SOFT_LOCKED -> {
                setKiosk(enabled = false, whitelist = whitelist)
                suspendNonEssentialApps(whitelist)
                runCatching { dpm.setStatusBarDisabled(adminComponent, false) }
                setOutgoingCallsBlocked(policies?.blockOutgoingCallsWhenSoftLocked ?: true)
            }
            LockState.ACTIVE, LockState.GRACE, LockState.PENDING_ENROLLMENT -> clearLockPolicies()
            LockState.RELEASED -> releaseDevice()
        }
    }

    /** Lock Task Mode + HOME takeover so the kiosk survives Home/Recents and reboots. */
    private fun setKiosk(enabled: Boolean, whitelist: List<String>) {
        val packages = (listOf(context.packageName) + whitelist).distinct().toTypedArray()
        dpm.setLockTaskPackages(adminComponent, packages)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                dpm.setLockTaskFeatures(
                    adminComponent,
                    if (enabled) DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS else DevicePolicyManager.LOCK_TASK_FEATURE_NONE,
                )
            }
        }
        if (enabled) {
            val filter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            dpm.addPersistentPreferredActivity(adminComponent, filter, ComponentName(context, MainActivity::class.java))
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        } else {
            dpm.clearPackagePersistentPreferredActivities(adminComponent, context.packageName)
        }
    }

    /** Payment-default soft lock: suspend every launchable app except essentials. */
    @SuppressLint("QueryPermissionsNeeded")
    private fun suspendNonEssentialApps(whitelist: List<String>) {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchable = context.packageManager.queryIntentActivities(launcherIntent, 0)
            .map { it.activityInfo.packageName }
            .distinct()
        val essentials = (whitelist + context.packageName + ESSENTIAL_PACKAGES).toSet()
        val toSuspend = launchable.filterNot { it in essentials }
        if (toSuspend.isEmpty()) return
        val failed = runCatching { dpm.setPackagesSuspended(adminComponent, toSuspend.toTypedArray(), true) }.getOrDefault(emptyArray())
        val suspended = toSuspend - failed.toSet()
        prefs.edit().putStringSet(K_SUSPENDED, (prefs.getStringSet(K_SUSPENDED, emptySet())!! + suspended).toSet()).apply()
    }

    private fun unsuspendAll() {
        val suspended = prefs.getStringSet(K_SUSPENDED, emptySet())!!
        if (suspended.isNotEmpty()) runCatching { dpm.setPackagesSuspended(adminComponent, suspended.toTypedArray(), false) }
        prefs.edit().remove(K_SUSPENDED).apply()
    }

    private fun setOutgoingCallsBlocked(blocked: Boolean) {
        // Emergency numbers remain dialable under DISALLOW_OUTGOING_CALLS.
        runCatching {
            if (blocked) dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_OUTGOING_CALLS)
            else dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_OUTGOING_CALLS)
        }
    }

    private fun clearLockPolicies() {
        setKiosk(enabled = false, whitelist = emptyList())
        unsuspendAll()
        setOutgoingCallsBlocked(false)
        runCatching { dpm.setStatusBarDisabled(adminComponent, false) }
    }

    /** Loan discharged: lift every restriction and relinquish Device Owner. */
    fun releaseDevice() {
        if (!isDeviceOwner) return
        clearLockPolicies()
        listOf(
            UserManager.DISALLOW_FACTORY_RESET, UserManager.DISALLOW_SAFE_BOOT, UserManager.DISALLOW_DEBUGGING_FEATURES,
            UserManager.DISALLOW_USB_FILE_TRANSFER, UserManager.DISALLOW_CONFIG_DATE_TIME, UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA, UserManager.DISALLOW_APPS_CONTROL, UserManager.DISALLOW_UNINSTALL_APPS,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
        ).forEach { runCatching { dpm.clearUserRestriction(adminComponent, it) } }
        runCatching { dpm.setUninstallBlocked(adminComponent, context.packageName, false) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching { dpm.setFactoryResetProtectionPolicy(adminComponent, null) }
        @Suppress("DEPRECATION")
        runCatching { dpm.clearDeviceOwnerApp(context.packageName) }
    }

    private fun isInstalled(pkg: String) = try {
        context.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    companion object {
        private const val TAG = "DeviceSecurityManager"
        private const val K_SUSPENDED = "suspended_packages"
        val ESSENTIAL_PACKAGES = listOf(
            "com.android.dialer", "com.google.android.dialer", "com.samsung.android.dialer", "com.android.phone",
            "com.android.emergency", "com.android.settings", "com.android.systemui",
        )
    }
}
