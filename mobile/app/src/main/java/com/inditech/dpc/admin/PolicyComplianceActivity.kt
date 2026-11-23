package com.inditech.dpc.admin

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.os.Bundle
import android.os.PersistableBundle

/**
 * Android 10+: invoked by the setup wizard after Device Owner is set, before the
 * user reaches the home screen. Enrollment must be kicked off here so the setup
 * wizard cannot be completed without tenant policies being applied.
 */
class PolicyComplianceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val extras: PersistableBundle? = intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE)
        EnterpriseAdminReceiver.handleProvisioning(this, extras)
        setResult(RESULT_OK)
        finish()
    }
}
