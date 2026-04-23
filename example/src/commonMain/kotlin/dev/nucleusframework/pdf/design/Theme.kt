package dev.nucleusframework.pdf.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
data class Palette(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val border: Color,
    val onBackground: Color,
    val muted: Color,
    val accent: Color,
    val accentContent: Color,
    val pageBackdrop: Color,
    val error: Color,
    val isDark: Boolean,
)

@Immutable
data class Typography(
    val title: TextStyle,
    val subtitle: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
    val mono: TextStyle,
)

@Immutable
data class Shapes(
    val small: RoundedCornerShape = RoundedCornerShape(8.dp),
    val medium: RoundedCornerShape = RoundedCornerShape(12.dp),
    val large: RoundedCornerShape = RoundedCornerShape(16.dp),
)

@Immutable
data class AppTheme(
    val colors: Palette,
    val type: Typography,
    val shapes: Shapes = Shapes(),
)

private val DarkPalette = Palette(
    background = Color(0xFF0B0C0F),
    surface = Color(0xFF131519),
    surfaceRaised = Color(0xFF1A1D23),
    border = Color(0xFF262A31),
    onBackground = Color(0xFFECEFF3),
    muted = Color(0xFF8C93A0),
    accent = Color(0xFF7C9CFF),
    accentContent = Color(0xFF0B0C0F),
    pageBackdrop = Color(0xFF17191E),
    error = Color(0xFFFF6B6B),
    isDark = true,
)

private val LightPalette = Palette(
    background = Color(0xFFF6F7F9),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFF0F2F5),
    border = Color(0xFFE2E5EA),
    onBackground = Color(0xFF16181C),
    muted = Color(0xFF6B7280),
    accent = Color(0xFF4464E5),
    accentContent = Color(0xFFFFFFFF),
    pageBackdrop = Color(0xFFE8EAEE),
    error = Color(0xFFD84343),
    isDark = false,
)

private fun typographyFor(palette: Palette): Typography = Typography(
    title = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = palette.onBackground),
    subtitle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = palette.onBackground),
    body = TextStyle(fontSize = 13.sp, color = palette.onBackground),
    label = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = palette.muted, letterSpacing = 0.4.sp),
    mono = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = palette.onBackground),
)

private val DarkTheme = AppTheme(DarkPalette, typographyFor(DarkPalette))
private val LightTheme = AppTheme(LightPalette, typographyFor(LightPalette))

val LocalAppTheme: ProvidableCompositionLocal<AppTheme> = staticCompositionLocalOf { DarkTheme }

val colors: Palette
    @Composable get() = LocalAppTheme.current.colors
val type: Typography
    @Composable get() = LocalAppTheme.current.type
val shapes: Shapes
    @Composable get() = LocalAppTheme.current.shapes

/**
 * Provides the app palette based on [isDark]. Platform entry points hand in whatever signal
 * the OS exposes — Nucleus' native detector on desktop, [isSystemInDarkTheme] elsewhere.
 */
@Composable
fun AppThemeProvider(isDark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppTheme provides if (isDark) DarkTheme else LightTheme, content = content)
}
