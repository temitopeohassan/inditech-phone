package com.inditech.dpc.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.inditech.dpc.ui.theme.LocalBrand
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale

/** Tenant logo with a branded monogram fallback (offline / not yet cached / previews). */
@Composable
fun TenantLogo(name: String, logoUrl: String?, size: Dp = 56.dp) {
    val brand = LocalBrand.current
    val monogram = @Composable {
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(size / 4))
                .background(brand.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() },
                color = brand.onPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.38f).sp,
            )
        }
    }
    if (logoUrl == null || LocalInspectionMode.current) {
        monogram()
    } else {
        SubcomposeAsyncImage(
            model = logoUrl,
            contentDescription = "$name logo",
            modifier = Modifier.size(size).clip(RoundedCornerShape(size / 4)),
            contentScale = ContentScale.Fit,
            loading = { monogram() },
            error = { monogram() },
        )
    }
}

/** QR code rendered with ZXing directly onto a Compose canvas. */
@Composable
fun QrCode(content: String, modifier: Modifier = Modifier, size: Dp = 180.dp) {
    val matrix = remember(content) {
        QRCodeWriter().encode(
            content, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1),
        )
    }
    Box(
        modifier = modifier
            .size(size + 24.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .padding(12.dp),
    ) {
        Canvas(Modifier.size(size)) {
            val cell = this.size.width / matrix.width
            for (x in 0 until matrix.width) {
                for (y in 0 until matrix.height) {
                    if (matrix.get(x, y)) {
                        drawRect(Color(0xFF0B0F19), topLeft = Offset(x * cell, y * cell), size = Size(cell + 0.5f, cell + 0.5f))
                    }
                }
            }
        }
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, color: Color? = null) {
    val brand = LocalBrand.current
    Button(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(brand.corner),
        colors = ButtonDefaults.buttonColors(containerColor = color ?: brand.primary, contentColor = brand.onPrimary),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, color: Color? = null) {
    val brand = LocalBrand.current
    val c = color ?: brand.text
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(brand.corner),
        border = androidx.compose.foundation.BorderStroke(1.dp, c.copy(alpha = 0.35f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = c),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

@Composable
fun BrandCard(modifier: Modifier = Modifier, borderColor: Color? = null, content: @Composable () -> Unit) {
    val brand = LocalBrand.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(brand.corner * 1.5f))
            .background(brand.surface)
            .then(if (borderColor != null) Modifier.border(1.dp, borderColor, RoundedCornerShape(brand.corner * 1.5f)) else Modifier)
            .padding(20.dp),
    ) { content() }
}

@Composable
fun StatusPill(text: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text.uppercase(), color = color, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier, emphasize: Boolean = false) {
    val brand = LocalBrand.current
    Column(modifier) {
        Text(label.uppercase(), color = brand.muted, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            color = brand.text,
            style = if (emphasize) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
fun KeyValueRow(label: String, value: String) {
    val brand = LocalBrand.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = brand.muted, style = MaterialTheme.typography.bodyMedium)
        Text(value, color = brand.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun RowScope.Weighted(content: @Composable () -> Unit) {
    Box(Modifier.weight(1f)) { content() }
}

object Formats {
    fun money(amount: Double, currency: String): String = runCatching {
        NumberFormat.getCurrencyInstance(Locale.US).apply { this.currency = Currency.getInstance(currency) }.format(amount)
    }.getOrDefault("$currency %.2f".format(amount))

    fun date(iso: String, pattern: String = "MMMM d, yyyy"): String = runCatching {
        DateTimeFormatter.ofPattern(pattern, Locale.US).withZone(ZoneId.of("UTC")).format(Instant.parse(iso))
    }.getOrDefault(iso)
}
