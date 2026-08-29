package com.depthmaker.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Spec 4.4 design tokens. Dark only, on purpose: a grayscale depth map on a
// white ground washes out and the owner cannot judge quality from it.
val BackgroundColor = Color(0xFF0E0E10)
val SurfaceColor = Color(0xFF1A1A1D)
val PrimaryColor = Color(0xFF4A9EFF)
val OnPrimaryColor = Color(0xFFFFFFFF)
val TextPrimary = Color(0xFFF2F2F3)
val TextSecondary = Color(0xFF9A9AA0)
val ErrorColor = Color(0xFFFF5A5A)
val SuccessColor = Color(0xFF3DD68C)

private val DepthColors = darkColorScheme(
    primary = PrimaryColor,
    onPrimary = OnPrimaryColor,
    background = BackgroundColor,
    onBackground = TextPrimary,
    surface = SurfaceColor,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceColor,
    onSurfaceVariant = TextSecondary,
    error = ErrorColor,
    onError = Color.White,
    outline = Color(0xFF3A3A40)
)

private val DepthTypography = Typography(
    displayLarge = TextStyle(fontSize = 48.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal)
)

private val DepthShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(16.dp)
)

@Composable
fun DepthMakerTheme(content: @Composable () -> Unit) {
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme()   // theme is dark regardless; see comment above
    MaterialTheme(
        colorScheme = DepthColors,
        typography = DepthTypography,
        shapes = DepthShapes,
        content = content
    )
}
