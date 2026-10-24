package io.github.kdbrian.rivapln.todoradar.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shapes drawn inside git-graph nodes; each TODO marker owns one. */
enum class NodeShape { Circle, Diamond, Square, Triangle, Cross, Check, Dot, Arrow, Trash }

/**
 * "Git graph" palette: ink outlines on warm paper, with pastel node fills (sky, lavender,
 * mint, peach) and a deep green for actions. Light only, by design.
 */
@Immutable
data class RadarColors(
    val ink: Color = Color(0xFF1C1C21),
    val inkSoft: Color = Color(0xFF6B6B76),
    val paper: Color = Color(0xFFF4F4F1),
    val card: Color = Color(0xFFFFFFFF),
    val hairline: Color = Color(0xFFE6E6E3),
    val mint: Color = Color(0xFF72D6A3),
    val mintDeep: Color = Color(0xFF1F8A4C),
    val sky: Color = Color(0xFFCFE8F8),
    val skyDeep: Color = Color(0xFF2B7DB5),
    val lavender: Color = Color(0xFFC6B3F7),
    val lavenderDeep: Color = Color(0xFF7356D4),
    val peach: Color = Color(0xFFFFD5B5),
    val peachDeep: Color = Color(0xFFC0651E),
    val rose: Color = Color(0xFFE0566B),
) {
    fun tagFill(tag: String) = when (tag) {
        "FIXME" -> lavender
        "HACK" -> mint
        "XXX" -> peach
        else -> sky
    }

    fun tagInk(tag: String) = when (tag) {
        "FIXME" -> lavenderDeep
        "HACK" -> mintDeep
        "XXX" -> peachDeep
        else -> skyDeep
    }

    fun tagShape(tag: String) = when (tag) {
        "FIXME" -> NodeShape.Diamond
        "HACK" -> NodeShape.Triangle
        "XXX" -> NodeShape.Square
        else -> NodeShape.Circle
    }
}

private val Palette = RadarColors()

private val Scheme = lightColorScheme(
    primary = Palette.mintDeep,
    onPrimary = Color.White,
    primaryContainer = Palette.mint,
    onPrimaryContainer = Palette.ink,
    secondary = Palette.skyDeep,
    onSecondary = Color.White,
    secondaryContainer = Palette.sky,
    onSecondaryContainer = Palette.ink,
    tertiary = Palette.lavenderDeep,
    tertiaryContainer = Palette.lavender,
    onTertiaryContainer = Palette.ink,
    background = Palette.paper,
    onBackground = Palette.ink,
    surface = Palette.paper,
    onSurface = Palette.ink,
    surfaceVariant = Color(0xFFEDEDEA),
    onSurfaceVariant = Palette.inkSoft,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFAF8),
    surfaceContainer = Palette.card,
    surfaceContainerHigh = Color(0xFFF1F1EE),
    surfaceContainerHighest = Color(0xFFEAEAE6),
    outline = Color(0xFFCFCFD4),
    outlineVariant = Palette.hairline,
    error = Palette.rose,
    onError = Color.White,
)

/** Serif display faces (like the inspiration's section titles) over a clean sans body. */
private val Serif = FontFamily.Serif
private val Base = Typography()

private val RadarTypography = Typography(
    displayMedium = Base.displayMedium.copy(fontFamily = Serif, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
    displaySmall = Base.displaySmall.copy(fontFamily = Serif, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineLarge = Base.headlineLarge.copy(fontFamily = Serif, fontWeight = FontWeight.Bold),
    headlineMedium = Base.headlineMedium.copy(fontFamily = Serif, fontWeight = FontWeight.Bold),
    headlineSmall = Base.headlineSmall.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.Bold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge,
    bodyMedium = Base.bodyMedium,
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = Base.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp),
    labelSmall = Base.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp),
)

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)

private val RadarShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

val LocalRadarColors = staticCompositionLocalOf { Palette }

@Composable
fun RadarTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRadarColors provides Palette) {
        MaterialTheme(colorScheme = Scheme, typography = RadarTypography, shapes = RadarShapes, content = content)
    }
}

object Radar {
    val colors: RadarColors @Composable get() = LocalRadarColors.current
}
