package com.inditech.dpc.messaging

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.inditech.dpc.DpcApplication
import com.inditech.dpc.data.model.DeviceCommand
import com.inditech.dpc.data.model.LockState
import com.inditech.dpc.sync.SyncScheduler
import kotlinx.coroutines.runBlocking

/** High-priority data-only FCM channel (primary command path). */
class PushService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val command = parse(message.data) ?: run {
            Log.w(TAG, "Ignoring malformed push payload")
            return
        }
        val app = application as DpcApplication
        // onMessageReceived runs on a background thread with a ~10s budget.
        runBlocking { app.container.repository.handleCommand(command) }
        SyncScheduler.heartbeatNow(this)
    }

    override fun onNewToken(token: String) {
        (application as DpcApplication).container.repository.onNewFcmToken(token)
        SyncScheduler.heartbeatNow(this)
    }

    companion object {
        private const val TAG = "PushService"

        fun parse(data: Map<String, String>): DeviceCommand? {
            val type = data["commandType"] ?: return null
            val id = data["commandId"] ?: return null
            val target = data["targetState"]?.let { runCatching { LockState.valueOf(it) }.getOrNull() } ?: when (type) {
                "LOCK" -> LockState.HARD_LOCKED
                "SOFT_LOCK" -> LockState.SOFT_LOCKED
                "WARN" -> LockState.GRACE
                "RELEASE" -> LockState.RELEASED
                "UNLOCK" -> LockState.ACTIVE
                else -> return null
            }
            return DeviceCommand(type, id, target, data["timestamp"])
        }
    }
}
