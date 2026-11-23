package com.inditech.dpc.admin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.inditech.dpc.DpcApplication
import com.inditech.dpc.messaging.EnforcementService
import com.inditech.dpc.sync.SyncScheduler

/**
 * Direct-boot aware: re-applies the last known lock state on LOCKED_BOOT_COMPLETED
 * (before credential-encrypted storage is available) and restarts sync after unlock.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as DpcApplication
        app.container.enforcer.reapplyFromStorage()
        if (intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            SyncScheduler.schedulePeriodic(context)
            EnforcementService.start(context)
        }
    }
}
