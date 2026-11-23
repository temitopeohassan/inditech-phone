package com.inditech.dpc.ui

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.inditech.dpc.DpcApplication
import com.inditech.dpc.R
import com.inditech.dpc.data.model.LockState
import com.inditech.dpc.data.model.ProvisioningExtras
import com.inditech.dpc.policy.DeviceUiState
import com.inditech.dpc.policy.EnrollmentStep
import com.inditech.dpc.sync.SyncScheduler
import com.inditech.dpc.ui.screens.CheckoutWebView
import com.inditech.dpc.ui.screens.EnrollmentScreen
import com.inditech.dpc.ui.screens.HardLockScreen
import com.inditech.dpc.ui.screens.HomeScreen
import com.inditech.dpc.ui.screens.OfflineUnlockDialog
import com.inditech.dpc.ui.screens.PaymentScreen
import com.inditech.dpc.ui.screens.ReleasedScreen
import com.inditech.dpc.ui.screens.SoftLockScreen
import com.inditech.dpc.ui.screens.SupportScreen
import com.inditech.dpc.ui.theme.TenantTheme
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.Instant
import java.util.Date

sealed interface Route {
    data object Main : Route
    data object Payment : Route
    data object Support : Route
    data class Checkout(val url: String) : Route
}

class MainActivity : ComponentActivity() {

    private val container by lazy { (application as DpcApplication).container }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by container.enforcer.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.effective.state, state.isDeviceOwner) { syncLockTask(state) }
            TenantTheme(state.config?.identity?.theme) {
                DpcApp(state)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        container.enforcer.apply()
        if (container.store.isEnrolled) SyncScheduler.heartbeatNow(this)
    }

    /** Anchor the UI in Lock Task Mode while hard-locked (home / recents / status bar disabled). */
    private fun syncLockTask(state: DeviceUiState) {
        val am = getSystemService(ActivityManager::class.java)
        val inLockTask = am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        val shouldLock = state.effective.state == LockState.HARD_LOCKED && state.isDeviceOwner
        runCatching {
            if (shouldLock && !inLockTask) startLockTask()
            if (!shouldLock && inLockTask) stopLockTask()
        }
    }

    @Composable
    private fun DpcApp(state: DeviceUiState) {
        var route by remember { mutableStateOf<Route>(Route.Main) }
        var showCodeDialog by remember { mutableStateOf(false) }
        val config = state.config
        val locked = state.effective.state == LockState.HARD_LOCKED || state.effective.state == LockState.SOFT_LOCKED

        BackHandler(enabled = route != Route.Main || locked) {
            if (route != Route.Main) route = Route.Main
        }

        if (state.enrollmentStep != EnrollmentStep.DONE || config == null) {
            EnrollmentScreen(
                step = state.enrollmentStep,
                error = state.enrollmentError,
                tenantName = config?.identity?.name ?: getString(R.string.app_name),
                onRetry = { SyncScheduler.enqueueEnrollment(this) },
                onManualSubmit = { tenantId, token, url ->
                    container.repository.saveManualProvisioning(ProvisioningExtras(tenantId, token, url))
                    lifecycleScope.launch { container.repository.enroll() }
                },
            )
            return
        }

        when (val r = route) {
            Route.Payment -> PaymentScreen(config, state.loan, onBack = { route = Route.Main }, onDialUssd = ::dialUssd, onOpenCheckout = { route = Route.Checkout(it) })
            Route.Support -> SupportScreen(
                config, state.loan?.accountRef, onBack = { route = Route.Main },
                onCall = ::dial, onWhatsApp = { open("https://wa.me/" + it.filter(Char::isDigit)) }, onEmail = { open("mailto:$it") },
            )
            is Route.Checkout -> CheckoutWebView(r.url, config.repaymentSettings.whitelistedDomains, onBack = { route = Route.Payment })
            Route.Main -> when (state.effective.state) {
                LockState.HARD_LOCKED -> HardLockScreen(
                    config = config,
                    loan = state.loan,
                    imei = state.imei,
                    reason = state.effective.reason,
                    onCallSupport = { dial(config.support.phoneNumber) },
                    onEmergency = { emergencyDial(config.support.emergencyNumbers.firstOrNull() ?: "112") },
                    onPay = { route = Route.Payment },
                    onEnterCode = { showCodeDialog = true },
                )
                LockState.SOFT_LOCKED -> SoftLockScreen(
                    config = config,
                    loan = state.loan,
                    hoursUntilHardLock = hoursUntilHardLock(state),
                    onPay = { route = Route.Payment },
                    onCallSupport = { dial(config.support.phoneNumber) },
                    onEmergency = { emergencyDial(config.support.emergencyNumbers.firstOrNull() ?: "112") },
                )
                LockState.RELEASED -> ReleasedScreen(config, onDone = { finish() })
                else -> HomeScreen(
                    config = config,
                    loan = state.loan,
                    state = state.effective.state,
                    principal = state.loan?.principalAmount,
                    lastSyncLabel = state.lastSyncAt?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) },
                    onPay = { route = Route.Payment },
                    onSupport = { route = Route.Support },
                )
            }
        }

        if (showCodeDialog) {
            OfflineUnlockDialog(
                onDismiss = { showCodeDialog = false },
                onSubmit = { code ->
                    val ok = container.repository.redeemOfflineCode(code)
                    if (ok) {
                        showCodeDialog = false
                        Toast.makeText(this, "Device temporarily unlocked", Toast.LENGTH_LONG).show()
                    }
                    ok
                },
            )
        }
    }

    private fun hoursUntilHardLock(state: DeviceUiState): Int? {
        val loan = state.loan ?: return null
        val p = state.config?.enforcementPolicies ?: return null
        val due = runCatching { Instant.parse(loan.nextDueAt).toEpochMilli() }.getOrNull() ?: return null
        val hardAt = due + (p.gracePeriodHours + (p.softLockHours ?: 48)) * 3_600_000L
        return ((hardAt - container.clock.now()) / 3_600_000L).toInt().takeIf { it >= 0 }
    }

    private fun dial(number: String) = open("tel:" + Uri.encode(number))

    private fun dialUssd(code: String) = open("tel:" + Uri.encode(code))

    private fun emergencyDial(number: String) {
        try {
            startActivity(Intent("com.android.phone.EmergencyDialer.DIAL").setData(Uri.parse("tel:$number")))
        } catch (_: ActivityNotFoundException) {
            open("tel:$number")
        } catch (_: SecurityException) {
            open("tel:$number")
        }
    }

    private fun open(uri: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Toast.makeText(this, "Unable to open $uri", Toast.LENGTH_SHORT).show() }
    }
}
