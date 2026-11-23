package com.inditech.dpc.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inditech.dpc.data.model.Theme as TenantPalette

fun parseHex(hex: String, fallback: Color = Color(0xFF0B5FFF)): Color = runCatching {
    val clean = hex.removePrefix("#")
    Color(("FF$clean").toLong(16))
}.getOrDefault(fallback)

data class Brand(
    val primary: Color,
    val secondary: Color,
    val background: Color,
    val surface: Color,
    val text: Color,
    val corner: Dp,
) {
    val onPrimary: Color get() = if (primary.luminance() > 0.5f) Color(0xFF111111) else Color.White
    val muted: Color get() = text.copy(alpha = 0.68f)
    val danger: Color = Color(0xFFE5484D)
    val warning: Color = Color(0xFFF5A524)
    val success: Color = Color(0xFF30A46C)
}

val DefaultBrand = Brand(
    primary = Color(0xFF0B5FFF),
    secondary = Color(0xFF00C2A8),
    background = Color(0xFF0E1525),
    surface = Color(0xFF1A2338),
    text = Color.White,
    corner = 12.dp,
)

fun TenantPalette.toBrand() = Brand(
    primary = parseHex(primaryColor),
    secondary = parseHex(secondaryColor),
    background = parseHex(backgroundColor),
    surface = parseHex(surfaceColor),
    text = parseHex(textColor, Color.White),
    corner = buttonCornerRadius.coerceIn(0f, 32f).dp,
)

val LocalBrand = staticCompositionLocalOf { DefaultBrand }

private val AppTypography = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp, letterSpacing = 0.sp),
    headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
)

/** Hydrates Material 3 from the tenant's dynamic palette (step 4 of enrollment: UI hydration). */
@Composable
fun TenantTheme(palette: TenantPalette?, content: @Composable () -> Unit) {
    val brand = palette?.toBrand() ?: DefaultBrand
    val dark = brand.background.luminance() < 0.5f
    val scheme = if (dark) {
        darkColorScheme(
            primary = brand.primary, onPrimary = brand.onPrimary, secondary = brand.secondary,
            background = brand.background, onBackground = brand.text, surface = brand.surface, onSurface = brand.text,
            error = brand.danger,
        )
    } else {
        lightColorScheme(
            primary = brand.primary, onPrimary = brand.onPrimary, secondary = brand.secondary,
            background = brand.background, onBackground = brand.text, surface = brand.surface, onSurface = brand.text,
            error = brand.danger,
        )
    }
    CompositionLocalProvider(LocalBrand provides brand) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AppTypography,
            shapes = Shapes(
                small = RoundedCornerShape(brand.corner / 2),
                medium = RoundedCornerShape(brand.corner),
                large = RoundedCornerShape(brand.corner * 1.5f),
            ),
            content = content,
        )
    }
}
