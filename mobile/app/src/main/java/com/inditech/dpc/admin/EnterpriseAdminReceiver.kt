package com.inditech.dpc.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.util.Log
import com.inditech.dpc.DpcApplication
import com.inditech.dpc.data.model.ProvisioningExtras
import com.inditech.dpc.sync.SyncScheduler

/**
 * Device Owner entry point. Step 2 of the enrollment lifecycle: tenant identity
 * is injected through EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE (QR / Zero-Touch).
 */
class EnterpriseAdminReceiver : DeviceAdminReceiver() {

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        @Suppress("DEPRECATION")
        val extras: PersistableBundle? = intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE)
        handleProvisioning(context, extras)
    }

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device admin enabled")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "This device is protected by your financing agreement and cannot be removed."

    companion object {
        private const val TAG = "EnterpriseAdminReceiver"

        fun parseExtras(extras: PersistableBundle?): ProvisioningExtras? {
            if (extras == null) return null
            val tenantId = extras.getString("tenant_id") ?: return null
            val token = extras.getString("provisioning_token") ?: return null
            val gateway = extras.getString("api_gateway_url") ?: return null
            return ProvisioningExtras(tenantId, token, gateway)
        }

        fun handleProvisioning(context: Context, extras: PersistableBundle?) {
            val parsed = parseExtras(extras)
            val app = context.applicationContext as DpcApplication
            if (parsed != null) {
                app.container.store.provisioning = parsed
                Log.i(TAG, "Provisioned for tenant ${parsed.tenantId}")
            } else {
                Log.w(TAG, "Provisioning completed without admin extras bundle")
            }
            app.container.securityManager.applyFinancingSecurityPolicies(authorizedAdminAccountIds = emptyList())
            SyncScheduler.enqueueEnrollment(context)
        }
    }
}
