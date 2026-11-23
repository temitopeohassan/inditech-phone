package com.inditech.dpc.ui.screens

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.inditech.dpc.data.model.LoanSummary
import com.inditech.dpc.data.model.PaymentMode
import com.inditech.dpc.data.model.PaymentModeType
import com.inditech.dpc.data.model.TenantConfig
import com.inditech.dpc.ui.components.BrandCard
import com.inditech.dpc.ui.components.Formats
import com.inditech.dpc.ui.components.PrimaryButton
import com.inditech.dpc.ui.theme.LocalBrand

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    val brand = LocalBrand.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = brand.text) }
        Text(title, style = MaterialTheme.typography.titleLarge, color = brand.text)
    }
}

/** Repayment options configured per tenant (virtual account, USSD, web checkout). */
@Composable
fun PaymentScreen(
    config: TenantConfig,
    loan: LoanSummary?,
    onBack: () -> Unit,
    onDialUssd: (String) -> Unit,
    onOpenCheckout: (String) -> Unit,
) {
    val brand = LocalBrand.current
    Column(
        Modifier.fillMaxSize().background(brand.background).statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TopBar("Make a payment", onBack)
        BrandCard {
            Column {
                Text("AMOUNT DUE", style = MaterialTheme.typography.labelMedium, color = brand.muted)
                Text(loan?.let { Formats.money(it.amountDue, it.currency) } ?: "—", style = MaterialTheme.typography.headlineLarge, color = brand.text)
                if (loan != null) {
                    Text("Reference: ${loan.accountRef}", style = MaterialTheme.typography.bodyMedium, color = brand.muted)
                }
            }
        }
        config.repaymentSettings.paymentModes.filter { it.enabled }.forEach { mode ->
            PaymentModeCard(mode, loan, onDialUssd, onOpenCheckout)
        }
        Text(
            "Payments are confirmed automatically. Your device unlocks within seconds of a successful payment.",
            style = MaterialTheme.typography.bodyMedium,
            color = brand.muted,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun PaymentModeCard(mode: PaymentMode, loan: LoanSummary?, onDialUssd: (String) -> Unit, onOpenCheckout: (String) -> Unit) {
    val brand = LocalBrand.current
    val (title, icon) = when (mode.type) {
        PaymentModeType.VIRTUAL_ACCOUNT -> "Bank transfer" to Icons.Filled.Home
        PaymentModeType.USSD -> "USSD" to Icons.Filled.Phone
        PaymentModeType.WEB_CHECKOUT -> "Card / online checkout" to Icons.Filled.ShoppingCart
    }
    BrandCard {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon)
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, color = brand.text)
            }
            Spacer(Modifier.height(10.dp))
            Text(mode.instructions, style = MaterialTheme.typography.bodyMedium, color = brand.muted)
            when (mode.type) {
                PaymentModeType.VIRTUAL_ACCOUNT -> {
                    Spacer(Modifier.height(12.dp))
                    Mono(mode.accountNumber ?: "—")
                    mode.bankName?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = brand.muted) }
                }
                PaymentModeType.USSD -> mode.ussdCode?.let { code ->
                    Spacer(Modifier.height(12.dp))
                    Mono(code)
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton("Dial $code", onClick = { onDialUssd(code) }, icon = Icons.Filled.Call, modifier = Modifier.fillMaxWidth())
                }
                PaymentModeType.WEB_CHECKOUT -> mode.checkoutUrl?.let { url ->
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton(
                        "Pay ${loan?.let { Formats.money(it.amountDue, it.currency) } ?: ""} online".replace("  ", " "),
                        onClick = { onOpenCheckout(url) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun IconBadge(icon: ImageVector) {
    val brand = LocalBrand.current
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(brand.primary.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = brand.primary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun Mono(text: String) {
    val brand = LocalBrand.current
    Text(text, color = brand.text, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = 1.sp)
}

/** Support channels — direct call, WhatsApp and email. */
@Composable
fun SupportScreen(config: TenantConfig, accountRef: String?, onBack: () -> Unit, onCall: (String) -> Unit, onWhatsApp: (String) -> Unit, onEmail: (String) -> Unit) {
    val brand = LocalBrand.current
    Column(
        Modifier.fillMaxSize().background(brand.background).statusBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TopBar("Support", onBack)
        BrandCard {
            Column {
                Text("We're here to help", style = MaterialTheme.typography.titleLarge, color = brand.text)
                Spacer(Modifier.height(6.dp))
                Text("Quote your Account ID ${accountRef ?: ""} when you contact ${config.identity.name}.".replace("  ", " "), style = MaterialTheme.typography.bodyMedium, color = brand.muted)
            }
        }
        SupportRow(Icons.Filled.Call, "Call us", config.support.phoneNumber) { onCall(config.support.phoneNumber) }
        SupportRow(Icons.Filled.Phone, "WhatsApp", config.support.whatsappNumber) { onWhatsApp(config.support.whatsappNumber) }
        SupportRow(Icons.Filled.Email, "Email", config.support.email) { onEmail(config.support.email) }
    }
}

@Composable
private fun SupportRow(icon: ImageVector, title: String, value: String, onClick: () -> Unit) {
    val brand = LocalBrand.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(brand.corner * 1.5f)).background(brand.surface).clickable(onClick = onClick).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = brand.text)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = brand.muted)
        }
    }
}

/** Returns true when `url` belongs to one of the tenant's whitelisted payment domains. */
fun isWhitelistedPaymentUrl(url: String, whitelistedDomains: List<String>): Boolean {
    val host = runCatching { Uri.parse(url).host }.getOrNull()?.lowercase() ?: return false
    val scheme = runCatching { Uri.parse(url).scheme }.getOrNull()
    if (scheme != "https") return false
    return whitelistedDomains.any { d -> val domain = d.lowercase().trim(); host == domain || host.endsWith(".$domain") }
}

/** In-app checkout constrained to repayment_settings.whitelisted_domains (works inside Lock Task Mode). */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CheckoutWebView(url: String, whitelistedDomains: List<String>, onBack: () -> Unit) {
    val brand = LocalBrand.current
    Column(Modifier.fillMaxSize().background(brand.background).statusBarsPadding()) {
        TopBar("Secure checkout", onBack)
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            // Cancel navigation outside approved payment processors.
                            return !isWhitelistedPaymentUrl(request.url.toString(), whitelistedDomains)
                        }
                    }
                    if (isWhitelistedPaymentUrl(url, whitelistedDomains)) loadUrl(url)
                }
            },
        )
    }
}
