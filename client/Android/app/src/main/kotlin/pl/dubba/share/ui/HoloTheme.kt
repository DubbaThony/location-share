package pl.dubba.share.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Holo (Android 4.x ICS/JB) color + typography palette as a Compose theme.
 * Wraps Material3 with the canonical Holo Dark scheme - Holo Blue accents on
 * near-black surfaces, Roboto Light typography (FontFamily.SansSerif resolves
 * to Roboto on Android). Use for the non-skeuomorphic scaffolding screens;
 * the main location-share dial UI does its own pixel-level drawing and won't
 * inherit much from here beyond color hints.
 */
object HoloColors {
    val Blue = Color(0xFF33B5E5)        // canonical Holo Blue (holo_blue_light)
    val BlueBright = Color(0xFF00DDFF)  // holo_blue_bright
    val BlueDark = Color(0xFF0099CC)    // holo_blue_dark
    val GreenOk = Color(0xFF99CC00)     // holo_green_light
    val Orange = Color(0xFFFFBB33)      // holo_orange_light
    val Red = Color(0xFFFF4444)         // holo_red_light
    val RedDark = Color(0xFFCC0000)     // holo_red_dark

    val Background = Color(0xFF000000)
    val Surface = Color(0xFF1A1A1A)
    val SurfaceVariant = Color(0xFF2A2A2A)
    val OnSurface = Color(0xFFFFFFFF)
    val OnSurfaceMuted = Color(0xFFB0B0B0)
    val Outline = Color(0xFF333333)
}

private val HoloScheme = darkColorScheme(
    primary = HoloColors.Blue,
    onPrimary = HoloColors.OnSurface,
    primaryContainer = HoloColors.BlueDark,
    onPrimaryContainer = HoloColors.OnSurface,
    secondary = HoloColors.BlueBright,
    onSecondary = HoloColors.OnSurface,
    tertiary = HoloColors.GreenOk,
    onTertiary = HoloColors.OnSurface,
    background = HoloColors.Background,
    onBackground = HoloColors.OnSurface,
    surface = HoloColors.Surface,
    onSurface = HoloColors.OnSurface,
    surfaceVariant = HoloColors.SurfaceVariant,
    onSurfaceVariant = HoloColors.OnSurfaceMuted,
    outline = HoloColors.Outline,
    error = HoloColors.Red,
    onError = HoloColors.OnSurface,
)

private val HoloTypography = Typography(
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Light, fontSize = 22.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Light, fontSize = 18.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Light, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Light, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Light, fontSize = 12.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp),
)

@Composable
fun HoloTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HoloScheme,
        typography = HoloTypography,
        content = content,
    )
}
