package com.inditech.dpc.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.inditech.dpc.DpcApplication
import com.inditech.dpc.data.SyncResult
import com.inditech.dpc.messaging.EnforcementService
import java.util.concurrent.TimeUnit

/** Batched, network-aware scheduling to keep the DPC under the 1.5%/day battery budget. */
object SyncScheduler {
    private const val ENROLL = "dpc-enroll"
    private const val HEARTBEAT = "dpc-heartbeat"
    private const val HEARTBEAT_NOW = "dpc-heartbeat-now"
    private const val WATCHDOG = "dpc-offline-watchdog"

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun enqueueEnrollment(context: Context) {
        val request = OneTimeWorkRequestBuilder<EnrollmentWorker>()
            .setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ENROLL, ExistingWorkPolicy.REPLACE, request)
    }

    fun schedulePeriodic(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            HEARTBEAT,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<HeartbeatWorker>(15, TimeUnit.MINUTES)
                .setConstraints(online)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build(),
        )
        // Runs with no network constraint: enforces the offline lockout policy.
        wm.enqueueUniquePeriodicWork(
            WATCHDOG,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build(),
        )
    }

    fun heartbeatNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            HEARTBEAT_NOW,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<HeartbeatWorker>()
                .setConstraints(online)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build(),
        )
    }
}

class EnrollmentWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as DpcApplication
        return when (val r = app.container.repository.enroll()) {
            SyncResult.Ok -> {
                SyncScheduler.schedulePeriodic(applicationContext)
                EnforcementService.start(applicationContext)
                Result.success()
            }
            SyncResult.NotEnrolled -> Result.failure()
            is SyncResult.Failed -> if (r.retryable) Result.retry() else Result.failure()
        }
    }
}

class HeartbeatWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as DpcApplication
        return when (val r = app.container.repository.heartbeat()) {
            SyncResult.Ok, SyncResult.NotEnrolled -> Result.success()
            is SyncResult.Failed -> if (r.retryable && runAttemptCount < 3) Result.retry() else Result.success()
        }
    }
}

class WatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        (applicationContext as DpcApplication).container.repository.watchdog()
        return Result.success()
    }
}
