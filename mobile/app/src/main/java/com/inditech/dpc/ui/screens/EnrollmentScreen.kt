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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.inditech.dpc.policy.EnrollmentStep
import com.inditech.dpc.ui.components.BrandCard
import com.inditech.dpc.ui.components.PrimaryButton
import com.inditech.dpc.ui.components.SecondaryButton
import com.inditech.dpc.ui.components.TenantLogo
import com.inditech.dpc.ui.theme.LocalBrand

private val STEPS = listOf(
    EnrollmentStep.CONNECTING to "Connecting to secure server",
    EnrollmentStep.FETCHING_BRANDING to "Downloading your lender's configuration",
    EnrollmentStep.APPLYING_POLICIES to "Applying device protection policies",
)

/** Shown during setup — the Android setup wizard cannot complete until this succeeds. */
@Composable
fun EnrollmentScreen(
    step: EnrollmentStep,
    error: String?,
    tenantName: String,
    onRetry: () -> Unit,
    onManualSubmit: (tenantId: String, token: String, apiUrl: String) -> Unit,
) {
    val brand = LocalBrand.current
    var showManual by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .background(brand.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        TenantLogo(tenantName, null, 72.dp)
        Spacer(Modifier.height(24.dp))
        Text("Setting up your device", style = MaterialTheme.typography.headlineMedium, color = brand.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "This phone is financed by $tenantName. Keep it connected to Wi-Fi or mobile data while we finish setup.",
            style = MaterialTheme.typography.bodyMedium,
            color = brand.muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))

        if (step == EnrollmentStep.NOT_PROVISIONED && !showManual) {
            BrandCard {
                Column {
                    Text("Device not provisioned", style = MaterialTheme.typography.titleMedium, color = brand.text)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Factory reset the device and tap the welcome screen six times to scan the enrollment QR code provided by your loan agent.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = brand.muted,
                    )
                    Spacer(Modifier.height(16.dp))
                    SecondaryButton("Enter enrollment code manually", onClick = { showManual = true }, modifier = Modifier.fillMaxWidth())
                }
            }
        } else if (showManual && step == EnrollmentStep.NOT_PROVISIONED) {
            ManualEnrollmentForm(onSubmit = onManualSubmit, onCancel = { showManual = false })
        } else {
            BrandCard {
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    val currentIndex = STEPS.indexOfFirst { it.first == step }.let { if (step == EnrollmentStep.DONE) STEPS.size else it }
                    STEPS.forEachIndexed { index, (_, label) ->
                        val done = currentIndex > index
                        val active = currentIndex == index && step != EnrollmentStep.FAILED
                        val failed = step == EnrollmentStep.FAILED && index == maxOf(currentIndex, 0)
                        StepRow(label, done = done, active = active, failed = failed)
                    }
                }
            }
            if (step == EnrollmentStep.FAILED) {
                Spacer(Modifier.height(16.dp))
                Text(error ?: "Enrollment failed", color = brand.danger, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                PrimaryButton("Retry", onClick = onRetry, icon = Icons.Filled.Refresh, modifier = Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(32.dp))
        Text(
            "Device protection cannot be skipped. Setup will continue automatically once enrollment completes.",
            style = MaterialTheme.typography.bodyMedium,
            color = brand.muted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StepRow(label: String, done: Boolean, active: Boolean, failed: Boolean) {
    val brand = LocalBrand.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            when {
                done -> Icon(Icons.Filled.CheckCircle, null, tint = brand.success, modifier = Modifier.size(26.dp))
                failed -> Icon(Icons.Filled.Warning, null, tint = brand.danger, modifier = Modifier.size(26.dp))
                active -> CircularProgressIndicator(color = brand.primary, strokeWidth = 3.dp, modifier = Modifier.size(22.dp), progress = { 0.7f })
                else -> Box(Modifier.size(14.dp).clip(CircleShape).background(brand.muted.copy(alpha = 0.3f)))
            }
        }
        Spacer(Modifier.width(14.dp))
        Text(label, color = if (done || active) brand.text else brand.muted, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ManualEnrollmentForm(onSubmit: (String, String, String) -> Unit, onCancel: () -> Unit) {
    val brand = LocalBrand.current
    var tenant by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("https://") }
    BrandCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Manual enrollment", style = MaterialTheme.typography.titleMedium, color = brand.text)
            OutlinedTextField(tenant, { tenant = it }, label = { Text("Tenant ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(token, { token = it }, label = { Text("Provisioning token") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(url, { url = it }, label = { Text("API gateway URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            PrimaryButton("Enroll device", onClick = { onSubmit(tenant.trim(), token.trim(), url.trim()) }, modifier = Modifier.fillMaxWidth())
            SecondaryButton("Cancel", onClick = onCancel, modifier = Modifier.fillMaxWidth())
        }
    }
}
