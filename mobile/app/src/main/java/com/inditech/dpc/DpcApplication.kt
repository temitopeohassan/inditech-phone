package com.inditech.dpc

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.Configuration
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.inditech.dpc.messaging.EnforcementService
import com.inditech.dpc.sync.SyncScheduler

class DpcApplication : Application(), Configuration.Provider {

    lateinit var container: AppContainer
        private set

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build()

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        createChannels()
        initFirebase()
        container.enforcer.reapplyFromStorage()
        if (container.store.isEnrolled) {
            SyncScheduler.schedulePeriodic(this)
            EnforcementService.start(this)
        } else if (container.store.provisioning != null) {
            SyncScheduler.enqueueEnrollment(this)
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ENFORCEMENT, getString(R.string.enforcement_channel), NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_REMINDERS, getString(R.string.reminders_channel), NotificationManager.IMPORTANCE_HIGH))
    }

    /** Firebase is configured from Gradle properties (no google-services.json needed for white-label builds). */
    private fun initFirebase() {
        if (BuildConfig.FIREBASE_APP_ID.isBlank()) return
        runCatching {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(
                    this,
                    FirebaseOptions.Builder()
                        .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                        .setApiKey(BuildConfig.FIREBASE_API_KEY)
                        .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                        .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                        .build(),
                )
            }
            FirebaseMessaging.getInstance().token.addOnSuccessListener { container.repository.onNewFcmToken(it) }
        }
    }

    companion object {
        const val CHANNEL_ENFORCEMENT = "enforcement"
        const val CHANNEL_REMINDERS = "reminders"
    }
}
