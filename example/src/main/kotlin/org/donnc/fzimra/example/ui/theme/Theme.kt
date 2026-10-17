package org.donnc.fzimra.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Green = Color(0xFF0B6E4F)
private val GreenLight = Color(0xFF7FD8B4)
private val Gold = Color(0xFFC8961E)
private val GoldLight = Color(0xFFF2C14E)

private val LightColors = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEFE0),
    onPrimaryContainer = Color(0xFF002116),
    secondary = Gold,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE7B5),
    onSecondaryContainer = Color(0xFF281900),
    tertiary = Color(0xFF3B6470),
    background = Color(0xFFF7F9F7),
    surface = Color(0xFFF7F9F7),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F4F1),
    surfaceContainer = Color(0xFFEBEFEC),
    surfaceContainerHigh = Color(0xFFE5EAE6),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = GreenLight,
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF00513D),
    onPrimaryContainer = Color(0xFFCDEFE0),
    secondary = GoldLight,
    onSecondary = Color(0xFF412D00),
    secondaryContainer = Color(0xFF5D4200),
    onSecondaryContainer = Color(0xFFFFE7B5),
    tertiary = Color(0xFFA2CEDB),
    background = Color(0xFF0F1512),
    surface = Color(0xFF0F1512),
    surfaceContainerLowest = Color(0xFF0A0F0D),
    surfaceContainerLow = Color(0xFF171D1A),
    surfaceContainer = Color(0xFF1B211E),
    surfaceContainerHigh = Color(0xFF252B28),
)

/** Colours for fiscal statuses, readable in light and dark themes. */
@Immutable
data class StatusColors(val good: Color, val waiting: Color, val warning: Color, val bad: Color, val neutral: Color)

private val LightStatus = StatusColors(Color(0xFF1E7B45), Color(0xFF2F6FB5), Color(0xFFB26A00), Color(0xFFBA1A1A), Color(0xFF5F6662))
private val DarkStatus = StatusColors(Color(0xFF7FD8A0), Color(0xFF9CC4F5), Color(0xFFFFB960), Color(0xFFFFB4AB), Color(0xFFA9B0AB))

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

private val AppTypography = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Monospace text, for codes and the gateway's JSON. */
val CodeStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun FzimraTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
