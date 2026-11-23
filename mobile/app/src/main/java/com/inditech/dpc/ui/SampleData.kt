package com.inditech.dpc.ui

import com.inditech.dpc.data.model.EnforcementPolicies
import com.inditech.dpc.data.model.Identity
import com.inditech.dpc.data.model.LoanSummary
import com.inditech.dpc.data.model.OfflineAction
import com.inditech.dpc.data.model.OfflineLockFallback
import com.inditech.dpc.data.model.PaymentMode
import com.inditech.dpc.data.model.PaymentModeType
import com.inditech.dpc.data.model.RepaymentSettings
import com.inditech.dpc.data.model.Support
import com.inditech.dpc.data.model.TenantConfig
import com.inditech.dpc.data.model.Theme

/** Representative tenant payloads used by previews and screenshot tests. */
object SampleData {
    val config = TenantConfig(
        tenantId = "tenant_af_east_0192",
        version = 3,
        identity = Identity(
            name = "Lumina Credit",
            logoUrl = "https://assets.example.com/lumina/logo.png",
            theme = Theme(
                primaryColor = "#0B5FFF",
                secondaryColor = "#00C2A8",
                backgroundColor = "#0E1525",
                surfaceColor = "#1A2338",
                textColor = "#FFFFFF",
                buttonCornerRadius = 14f,
            ),
        ),
        support = Support(
            phoneNumber = "+234 100 000 0000",
            whatsappNumber = "+234 800 123 4567",
            email = "support@luminacredit.com",
            allowEmergencyCalls = true,
            allowDirectSupportCall = true,
            emergencyNumbers = listOf("112", "911"),
        ),
        repaymentSettings = RepaymentSettings(
            paymentModes = listOf(
                PaymentMode(PaymentModeType.VIRTUAL_ACCOUNT, true, "Transfer the amount due to your dedicated account. Payments reflect within minutes.", bankName = "Providus Bank", accountNumber = "9920 1847 33"),
                PaymentMode(PaymentModeType.USSD, true, "Dial the code from the phone number registered on your account.", ussdCode = "*737*50*45#"),
                PaymentMode(PaymentModeType.WEB_CHECKOUT, true, "Pay securely with your debit card.", checkoutUrl = "https://pay.luminacredit.com/checkout/LND-894120"),
            ),
            whitelistedDomains = listOf("pay.luminacredit.com"),
        ),
        enforcementPolicies = EnforcementPolicies(
            gracePeriodHours = 72,
            warningOverlayHoursBeforeLock = 24,
            warningMessage = "Your payment is due soon. Pay now to keep using your phone without interruption.",
            lockMessage = "Please complete payment to unlock.",
            whitelistedPackagesWhenLocked = listOf("com.android.dialer"),
            offlineLockFallback = OfflineLockFallback(3, OfflineAction.HARD_LOCK),
            softLockHours = 48,
        ),
    )

    /** A second white-label tenant with a light palette, to show branding flexibility. */
    val lightConfig = config.copy(
        tenantId = "tenant_ng_west_0007",
        identity = Identity(
            name = "Kora Mobile Finance",
            logoUrl = "https://assets.example.com/kora/logo.png",
            theme = Theme("#E4572E", "#17BEBB", "#F7F7F2", "#FFFFFF", "#1B1B1E", 24f),
        ),
    )

    val loanOverdue = LoanSummary(
        loanId = "c0a8012e-0000-4000-8000-000000000001",
        accountRef = "LND-894120",
        currency = "USD",
        amountDue = 45.0,
        outstandingBalance = 225.0,
        nextDueAt = "2024-10-15T00:00:00Z",
        daysOverdue = 5,
        status = "overdue",
    )

    val loanCurrent = loanOverdue.copy(nextDueAt = "2024-11-15T00:00:00Z", daysOverdue = 0, status = "active", outstandingBalance = 180.0)

    const val IMEI = "864209041182391"
    const val PRINCIPAL = 270.0
}
