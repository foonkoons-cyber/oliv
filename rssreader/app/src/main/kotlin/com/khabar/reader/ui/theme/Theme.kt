package com.khabar.reader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.khabar.reader.data.ThemeMode

// Warm paper in the day, near-black at night. No dynamic colour: a reader is looked at for long
// stretches, and a wallpaper-derived accent on body text is a lottery.
private val LightColors = lightColorScheme(
    primary = Color(0xFF1B4332),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3E8DC),
    onPrimaryContainer = Color(0xFF0B2018),
    secondary = Color(0xFF8A5A16),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFAF7F2),
    onBackground = Color(0xFF1A1A17),
    surface = Color(0xFFFFFDFA),
    onSurface = Color(0xFF1A1A17),
    surfaceVariant = Color(0xFFEFE9E0),
    onSurfaceVariant = Color(0xFF5F5B54),
    outline = Color(0xFFCFC7B9),
    outlineVariant = Color(0xFFE3DCD0),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FD1A6),
    onPrimary = Color(0xFF06281B),
    primaryContainer = Color(0xFF1D4634),
    onPrimaryContainer = Color(0xFFCFEEDF),
    secondary = Color(0xFFE0B871),
    onSecondary = Color(0xFF2E2000),
    background = Color(0xFF12130F),
    onBackground = Color(0xFFECEAE3),
    surface = Color(0xFF1B1D18),
    onSurface = Color(0xFFECEAE3),
    surfaceVariant = Color(0xFF262922),
    onSurfaceVariant = Color(0xFFA5A79C),
    outline = Color(0xFF4A4E44),
    outlineVariant = Color(0xFF33362D),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3B0906)
)

// Line height matters more than font choice for long-form reading, so it is set explicitly
// instead of inheriting Material's UI-oriented defaults.
private val KhabarTypography = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 27.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
)

private val KhabarShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp)
)

@Composable
fun KhabarTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = KhabarTypography,
        shapes = KhabarShapes,
        content = content
    )
}
