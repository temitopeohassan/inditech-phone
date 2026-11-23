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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.inditech.dpc.data.model.LoanSummary
import com.inditech.dpc.data.model.LockState
import com.inditech.dpc.data.model.TenantConfig
import com.inditech.dpc.ui.components.BrandCard
import com.inditech.dpc.ui.components.Formats
import com.inditech.dpc.ui.components.KeyValueRow
import com.inditech.dpc.ui.components.LabeledValue
import com.inditech.dpc.ui.components.PrimaryButton
import com.inditech.dpc.ui.components.SecondaryButton
import com.inditech.dpc.ui.components.StatusPill
import com.inditech.dpc.ui.components.TenantLogo
import com.inditech.dpc.ui.theme.LocalBrand

/** Active / Compliant and Grace Period Warning phases (non-intrusive). */
@Composable
fun HomeScreen(
    config: TenantConfig,
    loan: LoanSummary?,
    state: LockState,
    principal: Double?,
    lastSyncLabel: String?,
    onPay: () -> Unit,
    onSupport: () -> Unit,
) {
    val brand = LocalBrand.current
    val grace = state == LockState.GRACE
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
            Column(Modifier.weight(1f)) {
                Text(config.identity.name, style = MaterialTheme.typography.titleLarge, color = brand.text)
                Text("Device financing", style = MaterialTheme.typography.bodyMedium, color = brand.muted)
            }
            if (grace) StatusPill("Payment due", brand.warning) else StatusPill("Protected", brand.success)
        }

        if (grace && loan != null) {
            GraceBanner(config.enforcementPolicies.warningMessage, loan, onPay)
        }

        BrandCard {
            Column {
                Row(verticalAlignment = Alignment.Top) {
                    LabeledValue(
                        if (grace) "Amount due" else "Next payment",
                        loan?.let { Formats.money(it.amountDue, it.currency) } ?: "—",
                        emphasize = true,
                        modifier = Modifier.weight(1f),
                    )
                    LabeledValue("Due date", loan?.let { Formats.date(it.nextDueAt, "MMM d, yyyy") } ?: "—")
                }
                if (loan != null && principal != null && principal > 0) {
                    val paid = (principal - loan.outstandingBalance).coerceAtLeast(0.0)
                    Spacer(Modifier.height(20.dp))
                    LinearProgressIndicator(
                        progress = { (paid / principal).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color = brand.secondary,
                        trackColor = brand.text.copy(alpha = 0.1f),
                        strokeCap = StrokeCap.Round,
                        drawStopIndicator = {},
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Text("${Formats.money(paid, loan.currency)} paid", color = brand.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text("${Formats.money(loan.outstandingBalance, loan.currency)} left", color = brand.muted, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(20.dp))
                PrimaryButton("Pay now", onClick = onPay, modifier = Modifier.fillMaxWidth())
            }
        }

        BrandCard {
            Column {
                Text("Loan account", style = MaterialTheme.typography.titleMedium, color = brand.text)
                Spacer(Modifier.height(8.dp))
                KeyValueRow("Account ID", loan?.accountRef ?: "—")
                KeyValueRow("Outstanding balance", loan?.let { Formats.money(it.outstandingBalance, it.currency) } ?: "—")
                KeyValueRow("Status", loan?.status?.replaceFirstChar { it.uppercase() } ?: "—")
                if (lastSyncLabel != null) KeyValueRow("Last synced", lastSyncLabel)
            }
        }

        BrandCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (grace) Icons.Filled.Info else Icons.Filled.CheckCircle, null, tint = if (grace) brand.warning else brand.success)
                Spacer(Modifier.width(12.dp))
                Text(
                    if (grace) {
                        "Pay before the grace period ends to avoid app restrictions. Emergency calls always remain available."
                    } else {
                        "Your device is in good standing. Keep paying on time to own it outright."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = brand.muted,
                )
            }
        }

        SecondaryButton("Contact support", onClick = onSupport, icon = Icons.Filled.Call, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun GraceBanner(message: String, loan: LoanSummary, onPay: () -> Unit) {
    val brand = LocalBrand.current
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(brand.corner * 1.5f))
            .background(brand.warning.copy(alpha = 0.14f))
            .padding(16.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, null, tint = brand.warning, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    if (loan.daysOverdue > 0) "Payment ${loan.daysOverdue} day${if (loan.daysOverdue == 1) "" else "s"} overdue" else "Payment due ${Formats.date(loan.nextDueAt, "MMM d")}",
                    style = MaterialTheme.typography.titleMedium,
                    color = brand.text,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = brand.text.copy(alpha = 0.85f))
            Spacer(Modifier.height(12.dp))
            Row {
                Icon(Icons.Filled.Lock, null, tint = brand.muted, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Restrictions apply after the grace period", style = MaterialTheme.typography.labelMedium, color = brand.muted)
            }
        }
    }
}
