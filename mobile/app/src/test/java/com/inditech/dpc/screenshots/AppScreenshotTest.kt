package com.inditech.dpc.screenshots

import androidx.compose.runtime.Composable
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.inditech.dpc.data.model.LockState
import com.inditech.dpc.data.model.TenantConfig
import com.inditech.dpc.domain.LockReason
import com.inditech.dpc.policy.EnrollmentStep
import com.inditech.dpc.ui.SampleData
import com.inditech.dpc.ui.screens.EnrollmentScreen
import com.inditech.dpc.ui.screens.HardLockScreen
import com.inditech.dpc.ui.screens.HomeScreen
import com.inditech.dpc.ui.screens.OfflineUnlockDialog
import com.inditech.dpc.ui.screens.PaymentScreen
import com.inditech.dpc.ui.screens.ReleasedScreen
import com.inditech.dpc.ui.screens.SoftLockScreen
import com.inditech.dpc.ui.screens.SupportScreen
import com.inditech.dpc.ui.theme.TenantTheme
import org.junit.Rule
import org.junit.Test

/**
 * Renders every borrower-facing screen of the DPC. Run `./gradlew recordPaparazziDebug`;
 * the CI workflow copies the PNGs into mobile/screenshots/.
 */
class AppScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5, maxPercentDifference = 0.1)

    private fun shot(name: String, config: TenantConfig = SampleData.config, content: @Composable () -> Unit) {
        paparazzi.snapshot(name) { TenantTheme(config.identity.theme) { content() } }
    }

    @Test fun enrollment_inProgress() = shot("01_enrollment_in_progress") {
        EnrollmentScreen(EnrollmentStep.FETCHING_BRANDING, null, SampleData.config.identity.name, {}, { _, _, _ -> })
    }

    @Test fun enrollment_failed() = shot("02_enrollment_failed") {
        EnrollmentScreen(EnrollmentStep.FAILED, "Enrollment rejected (409): IMEI already enrolled", SampleData.config.identity.name, {}, { _, _, _ -> })
    }

    @Test fun home_active() = shot("03_home_active") {
        HomeScreen(SampleData.config, SampleData.loanCurrent, LockState.ACTIVE, SampleData.PRINCIPAL, "Nov 10, 2024, 9:41 AM", {}, {})
    }

    @Test fun home_grace() = shot("04_home_grace_warning") {
        HomeScreen(SampleData.config, SampleData.loanOverdue.copy(daysOverdue = 1), LockState.GRACE, SampleData.PRINCIPAL, "Oct 16, 2024, 8:02 AM", {}, {})
    }

    @Test fun softLock() = shot("05_soft_lock_restricted") {
        SoftLockScreen(SampleData.config, SampleData.loanOverdue, 26, {}, {}, {})
    }

    @Test fun hardLock_overdue() = shot("06_hard_lock_overdue") {
        HardLockScreen(SampleData.config, SampleData.loanOverdue, SampleData.IMEI, LockReason.PAYMENT, {}, {}, {}, {})
    }

    @Test fun hardLock_tamper() = shot("07_hard_lock_tamper") {
        HardLockScreen(SampleData.config, SampleData.loanOverdue, SampleData.IMEI, LockReason.TAMPER, {}, {}, {}, {})
    }

    @Test fun hardLock_offline_unlockCode() = shot("08_offline_unlock_code") {
        HardLockScreen(SampleData.config, SampleData.loanOverdue, SampleData.IMEI, LockReason.OFFLINE, {}, {}, {}, {})
        OfflineUnlockDialog(onDismiss = {}, onSubmit = { false })
    }

    @Test fun payment() = shot("09_payment_options") {
        PaymentScreen(SampleData.config, SampleData.loanOverdue, {}, {}, {})
    }

    @Test fun support() = shot("10_support") {
        SupportScreen(SampleData.config, SampleData.loanOverdue.accountRef, {}, {}, {}, {})
    }

    @Test fun released() = shot("11_released") {
        ReleasedScreen(SampleData.config) {}
    }

    @Test fun whiteLabel_home() = shot("12_white_label_light_home", SampleData.lightConfig) {
        HomeScreen(SampleData.lightConfig, SampleData.loanCurrent, LockState.ACTIVE, SampleData.PRINCIPAL, "Nov 10, 2024, 9:41 AM", {}, {})
    }

    @Test fun whiteLabel_hardLock() = shot("13_white_label_light_hard_lock", SampleData.lightConfig) {
        HardLockScreen(SampleData.lightConfig, SampleData.loanOverdue, SampleData.IMEI, LockReason.PAYMENT, {}, {}, {}, {})
    }
}
