package com.nowly.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF526B5B),
    onPrimary = Color.White,
    secondary = Color(0xFF526B5B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5EAE1),
    onSecondaryContainer = Color(0xFF29362F),
    tertiary = Color(0xFF7B6D58),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFECE4D8),
    onTertiaryContainer = Color(0xFF29362F),
    primaryContainer = Color(0xFFE5EAE1),
    onPrimaryContainer = Color(0xFF29362F),
    background = Color(0xFFF3F4EF),
    onBackground = Color(0xFF29362F),
    surface = Color(0xFFFCFCF9),
    surfaceTint = Color(0xFF526B5B),
    surfaceBright = Color(0xFFFCFCF9),
    surfaceDim = Color(0xFFD9E0D5),
    surfaceContainer = Color(0xFFF0F2EB),
    surfaceContainerLow = Color(0xFFF3F4EF),
    surfaceContainerLowest = Color.White,
    surfaceContainerHigh = Color(0xFFEAEDE5),
    inverseSurface = Color(0xFF29362F),
    inverseOnSurface = Color(0xFFF3F4EF),
    inversePrimary = Color(0xFFBCCAB8),
    onSurface = Color(0xFF29362F),
    onSurfaceVariant = Color(0xFF58665D),
    surfaceContainerHighest = Color(0xFFE5EAE1),
    outline = Color(0xFFD9E0D5),
    outlineVariant = Color(0xFFD9E0D5),
)

private val NowlyTypography = Typography(
    headlineLarge = TextStyle(fontSize = 29.sp, fontWeight = FontWeight.SemiBold, lineHeight = 38.sp),
    titleLarge = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium, lineHeight = 26.sp),
    bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 23.sp),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontSize = 11.sp, lineHeight = 17.sp),
)

private val NowlyShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(17.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** The approved baseline is fixed light appearance; dynamic/dark colors are not enabled. */
@Composable
fun NowlyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = NowlyTypography,
        shapes = NowlyShapes,
        content = content,
    )
}
