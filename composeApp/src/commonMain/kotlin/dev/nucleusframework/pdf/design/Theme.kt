package dev.nucleusframework.pdf.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
    val error: Color,
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
    val small: RoundedCornerShape = RoundedCornerShape(6.dp),
    val medium: RoundedCornerShape = RoundedCornerShape(10.dp),
    val large: RoundedCornerShape = RoundedCornerShape(14.dp),
)

@Immutable
data class AppTheme(
    val colors: Palette,
    val type: Typography,
    val shapes: Shapes = Shapes(),
)

private val DefaultPalette = Palette(
    background = Color(0xFF0E0F12),
    surface = Color(0xFF15171B),
    surfaceRaised = Color(0xFF1C1F24),
    border = Color(0xFF2A2E36),
    onBackground = Color(0xFFE6E8EC),
    muted = Color(0xFF8A8F98),
    accent = Color(0xFFF6F7F9),
    accentContent = Color(0xFF0E0F12),
    error = Color(0xFFFF6B6B),
)

private val DefaultTypography = Typography(
    title = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = DefaultPalette.onBackground),
    subtitle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = DefaultPalette.onBackground),
    body = TextStyle(fontSize = 13.sp, color = DefaultPalette.onBackground),
    label = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = DefaultPalette.muted, letterSpacing = 0.4.sp),
    mono = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = DefaultPalette.onBackground),
)

val LocalAppTheme: ProvidableCompositionLocal<AppTheme> =
    staticCompositionLocalOf { AppTheme(DefaultPalette, DefaultTypography) }

val colors: Palette
    @Composable get() = LocalAppTheme.current.colors
val type: Typography
    @Composable get() = LocalAppTheme.current.type
val shapes: Shapes
    @Composable get() = LocalAppTheme.current.shapes
