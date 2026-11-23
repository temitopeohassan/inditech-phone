package com.inditech.dpc.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inditech.dpc.data.model.LoanSummary
import com.inditech.dpc.data.model.TenantConfig
import com.inditech.dpc.domain.LockReason
import com.inditech.dpc.ui.components.BrandCard
import com.inditech.dpc.ui.components.Formats
import com.inditech.dpc.ui.components.PrimaryButton
import com.inditech.dpc.ui.components.QrCode
import com.inditech.dpc.ui.components.SecondaryButton
import com.inditech.dpc.ui.components.StatusPill
import com.inditech.dpc.ui.components.TenantLogo
import com.inditech.dpc.ui.theme.LocalBrand

/**
 * Hard Lock (Full Lockdown): full-screen takeover with tenant branding, payment
 * deficit, dynamic payment QR, emergency dialer and support hotline.
 */
@Composable
fun HardLockScreen(
    config: TenantConfig,
    loan: LoanSummary?,
    imei: String?,
    reason: LockReason,
    onCallSupport: () -> Unit,
    onEmergency: () -> Unit,
    onPay: () -> Unit,
    onEnterCode: () -> Unit,
) {
    val brand = LocalBrand.current
    val checkout = config.repaymentSettings.paymentModes.firstOrNull { it.enabled && it.checkoutUrl != null }?.checkoutUrl
    Column(
        Modifier
            .fillMaxSize()
            .background(brand.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TenantLogo(config.identity.name, config.identity.logoUrl, 64.dp)
        Spacer(Modifier.height(8.dp))
        Text(config.identity.name, style = MaterialTheme.typography.titleMedium, color = brand.muted)
        Spacer(Modifier.height(20.dp))
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(brand.danger.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (reason == LockReason.TAMPER) Icons.Filled.Warning else Icons.Filled.Lock, null, tint = brand.danger, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(
            when (reason) {
                LockReason.TAMPER -> "DEVICE SECURITY ALERT"
                LockReason.OFFLINE -> "DEVICE OFFLINE & LOCKED"
                else -> "DEVICE OVERDUE & LOCKED"
            },
            style = MaterialTheme.typography.headlineMedium,
            color = brand.text,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            lockCopy(reason, loan, config.enforcementPolicies.lockMessage),
            style = MaterialTheme.typography.bodyLarge,
            color = brand.text.copy(alpha = 0.82f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))
        if (reason != LockReason.TAMPER) {
            QrCode(checkout ?: "account:${loan?.accountRef ?: imei.orEmpty()}", size = 168.dp)
            Spacer(Modifier.height(8.dp))
            Text("Scan to pay", style = MaterialTheme.typography.labelMedium, color = brand.muted)
            Spacer(Modifier.height(18.dp))
        }
        BrandCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                IdRow("ACCOUNT ID", loan?.accountRef ?: "—")
                IdRow("IMEI", imei ?: "—")
            }
        }
        Spacer(Modifier.height(18.dp))
        if (reason != LockReason.TAMPER) {
            PrimaryButton("Pay now", onClick = onPay, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            if (config.support.allowDirectSupportCall) {
                SecondaryButton("Call support", onClick = onCallSupport, icon = Icons.Filled.Call, modifier = Modifier.weight(1f))
            }
            if (config.support.allowEmergencyCalls) {
                SecondaryButton("Emergency", onClick = onEmergency, icon = Icons.Filled.Phone, color = brand.danger, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onEnterCode) {
            Text("Have an unlock code?", color = brand.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
}

private fun lockCopy(reason: LockReason, loan: LoanSummary?, lockMessage: String): String = when {
    reason == LockReason.TAMPER -> "A modification to this device's security was detected. Contact support to restore access."
    reason == LockReason.OFFLINE -> "This device has been offline for too long. Connect to the internet or enter an unlock code from support."
    loan != null -> {
        val days = if (loan.daysOverdue > 0) "Your account is ${loan.daysOverdue} day${if (loan.daysOverdue == 1) "" else "s"} overdue. " else ""
        "${days}Payment of ${Formats.money(loan.amountDue, loan.currency)} was due on ${Formats.date(loan.nextDueAt)}. $lockMessage"
    }
    else -> lockMessage
}

@Composable
private fun IdRow(label: String, value: String) {
    val brand = LocalBrand.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = brand.muted, modifier = Modifier.weight(1f))
        Text(value, color = brand.text, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

/** Soft Lock (Restricted): non-essential apps suspended, outgoing calls limited to emergency & support. */
@Composable
fun SoftLockScreen(
    config: TenantConfig,
    loan: LoanSummary?,
    hoursUntilHardLock: Int?,
    onPay: () -> Unit,
    onCallSupport: () -> Unit,
    onEmergency: () -> Unit,
) {
    val brand = LocalBrand.current
    Column(
        Modifier
            .fillMaxSize()
            .background(brand.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TenantLogo(config.identity.name, config.identity.logoUrl, 44.dp)
            Spacer(Modifier.width(12.dp))
            Text(config.identity.name, style = MaterialTheme.typography.titleLarge, color = brand.text, modifier = Modifier.weight(1f))
            StatusPill("Restricted", brand.danger)
        }
        BrandCard(borderColor = brand.danger.copy(alpha = 0.4f)) {
            Column {
                Text("Restricted mode", style = MaterialTheme.typography.headlineMedium, color = brand.text)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Most apps are paused because your payment is overdue. Pay the amount below to restore full access instantly.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = brand.muted,
                )
                Spacer(Modifier.height(20.dp))
                Text("AMOUNT DUE", style = MaterialTheme.typography.labelMedium, color = brand.muted)
                Text(loan?.let { Formats.money(it.amountDue, it.currency) } ?: "—", style = MaterialTheme.typography.headlineLarge, color = brand.text)
                if (loan != null) {
                    Text("Due ${Formats.date(loan.nextDueAt)} · Account ${loan.accountRef}", style = MaterialTheme.typography.bodyMedium, color = brand.muted)
                }
                if (hoursUntilHardLock != null) {
                    Spacer(Modifier.height(16.dp))
                    Row(
                        Modifier.clip(RoundedCornerShape(brand.corner)).background(brand.danger.copy(alpha = 0.12f)).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Lock, null, tint = brand.danger, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Full device lock in ${hoursUntilHardLock}h if unpaid", color = brand.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(20.dp))
                PrimaryButton("Pay now", onClick = onPay, modifier = Modifier.fillMaxWidth())
            }
        }
        BrandCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Still available", style = MaterialTheme.typography.titleMedium, color = brand.text)
                Available("Emergency calls (${config.support.emergencyNumbers.joinToString(", ")})")
                Available("Calls to ${config.identity.name} support")
                Available("Payment apps & USSD banking")
                Available("Incoming calls and SMS")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton("Call support", onClick = onCallSupport, icon = Icons.Filled.Call, modifier = Modifier.weight(1f))
            SecondaryButton("Emergency", onClick = onEmergency, icon = Icons.Filled.Phone, color = brand.danger, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun Available(text: String) {
    val brand = LocalBrand.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Check, null, tint = brand.success, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = brand.text)
    }
}

/** Loan discharged — Device Owner relinquished. */
@Composable
fun ReleasedScreen(config: TenantConfig, onDone: () -> Unit) {
    val brand = LocalBrand.current
    Column(
        Modifier.fillMaxSize().background(brand.background).statusBarsPadding().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(88.dp).clip(CircleShape).background(brand.success.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Check, null, tint = brand.success, modifier = Modifier.size(48.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text("It's all yours!", style = MaterialTheme.typography.headlineLarge, color = brand.text)
        Spacer(Modifier.height(12.dp))
        Text(
            "Your loan with ${config.identity.name} is fully paid. All device restrictions have been removed and this phone is now released to you.",
            style = MaterialTheme.typography.bodyLarge,
            color = brand.muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        PrimaryButton("Done", onClick = onDone, modifier = Modifier.fillMaxWidth())
    }
}

/** Offline unlock token entry (time-based code read out by support). */
@Composable
fun OfflineUnlockDialog(onDismiss: () -> Unit, onSubmit: (String) -> Boolean, initialError: Boolean = false) {
    val brand = LocalBrand.current
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(initialError) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = brand.surface,
        title = { Text("Enter unlock code", color = brand.text) },
        text = {
            Column {
                Text(
                    "Call support from any phone and quote your Account ID. They will give you an 8-digit code that works even without internet.",
                    color = brand.muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { v -> code = v.filter { it.isDigit() }.take(8); error = false },
                    singleLine = true,
                    isError = error,
                    textStyle = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 6.sp, color = brand.text),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    supportingText = { if (error) Text("Invalid or expired code") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (!onSubmit(code)) error = true }, enabled = code.length == 8) {
                Text("Unlock", color = brand.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = brand.muted) } },
    )
}
