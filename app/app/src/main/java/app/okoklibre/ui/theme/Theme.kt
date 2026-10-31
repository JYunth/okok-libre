package app.okoklibre.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.okoklibre.R
import app.okoklibre.data.ThemeMode

/**
 * The whole colour system. Ink carries the interface; [accent] is the active person's
 * colour, so whose data is on screen is visible everywhere without any added chrome.
 * [attention] is the only other colour and it always means something needs a look.
 */
@Immutable
data class AppColors(
    val ground: Color,
    val raised: Color,
    val ink: Color,
    val muted: Color,
    val faint: Color,
    val hairline: Color,
    val attention: Color,
    val accent: Color,
    val onAccent: Color,
)

val LocalAppColors = compositionLocalOf { lightColors(0) }

private val LightAccents = listOf(
    Color(0xFF2158D9), Color(0xFF0B7F57), Color(0xFF6D3BDB),
    Color(0xFFC4285F), Color(0xFF0A7487), Color(0xFF525C6B),
)
private val DarkAccents = listOf(
    Color(0xFF8AB0FF), Color(0xFF4FD0A0), Color(0xFFB9A2FF),
    Color(0xFFFF8FB4), Color(0xFF63CFE0), Color(0xFFB4BDC9),
)

/** How many people get a colour of their own before they start to repeat. */
val PersonColorCount: Int get() = LightAccents.size

fun personColor(index: Int, dark: Boolean): Color =
    (if (dark) DarkAccents else LightAccents)[index.mod(PersonColorCount)]

private fun lightColors(personIndex: Int) = AppColors(
    ground = Color(0xFFFFFFFF),
    raised = Color(0xFFF5F5F6),
    ink = Color(0xFF101114),
    muted = Color(0xFF60646C),
    faint = Color(0xFFB9BCC2),
    hairline = Color(0xFFE9EAEC),
    attention = Color(0xFFC2410C),
    accent = personColor(personIndex, dark = false),
    onAccent = Color(0xFFFFFFFF),
)

private fun darkColors(personIndex: Int) = AppColors(
    ground = Color(0xFF0C0D0F),
    raised = Color(0xFF17181B),
    ink = Color(0xFFF3F4F6),
    muted = Color(0xFF9A9FA8),
    faint = Color(0xFF4A4E56),
    hairline = Color(0xFF232529),
    attention = Color(0xFFFB923C),
    accent = personColor(personIndex, dark = true),
    onAccent = Color(0xFF0C0D0F),
)

@OptIn(ExperimentalTextApi::class)
private fun geist(weight: Int) = Font(
    R.font.geist,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private val Geist = FontFamily(geist(300), geist(400), geist(500), geist(600))

private fun style(size: Int, line: Int, weight: Int = 400, tracking: Double = 0.0) = TextStyle(
    fontFamily = Geist,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
    // Figures line up everywhere, which is most of what this app shows.
    fontFeatureSettings = "tnum",
)

private val AppTypography = Typography(
    displayLarge = style(84, 88, 300, -3.0),
    displayMedium = style(56, 60, 300, -1.8),
    displaySmall = style(38, 44, 400, -1.0),
    headlineLarge = style(30, 36, 500, -0.6),
    headlineMedium = style(24, 30, 500, -0.4),
    headlineSmall = style(20, 26, 500, -0.2),
    titleLarge = style(18, 24, 500, -0.1),
    titleMedium = style(16, 22, 500),
    titleSmall = style(14, 20, 500),
    bodyLarge = style(16, 24),
    bodyMedium = style(14, 21),
    bodySmall = style(12, 17, tracking = 0.1),
    labelLarge = style(14, 20, 500),
    labelMedium = style(12, 16, 500, 0.1),
    labelSmall = style(11, 14, 500, 0.2),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun isAppInDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** [personIndex] is the active profile's colour; a switch cross-fades the whole app to it. */
@Composable
fun OkokLibreTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    personIndex: Int = 0,
    content: @Composable () -> Unit,
) {
    val dark = isAppInDarkTheme(mode)
    val target = remember(dark, personIndex) { if (dark) darkColors(personIndex) else lightColors(personIndex) }
    val accent by animateColorAsState(target.accent, tween(240), label = "person accent")
    val colors = target.copy(accent = accent)

    val scheme = if (dark) {
        darkColorScheme(
            primary = accent,
            onPrimary = colors.onAccent,
            inversePrimary = personColor(personIndex, dark = false),
            primaryContainer = accent.copy(alpha = 0.16f),
            onPrimaryContainer = colors.ink,
            secondary = colors.ink,
            onSecondary = colors.ground,
            secondaryContainer = colors.raised,
            onSecondaryContainer = colors.ink,
            tertiary = accent,
            onTertiary = colors.onAccent,
            tertiaryContainer = accent.copy(alpha = 0.16f),
            onTertiaryContainer = colors.ink,
            background = colors.ground,
            onBackground = colors.ink,
            surface = colors.ground,
            onSurface = colors.ink,
            surfaceVariant = colors.raised,
            onSurfaceVariant = colors.muted,
            surfaceContainerLowest = colors.ground,
            surfaceContainerLow = colors.ground,
            surfaceContainer = colors.raised,
            surfaceContainerHigh = colors.raised,
            surfaceContainerHighest = colors.raised,
            surfaceTint = Color.Transparent,
            outline = colors.muted,
            outlineVariant = colors.hairline,
            error = colors.attention,
            onError = colors.ground,
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = colors.onAccent,
            inversePrimary = personColor(personIndex, dark = true),
            primaryContainer = accent.copy(alpha = 0.10f),
            onPrimaryContainer = colors.ink,
            secondary = colors.ink,
            onSecondary = colors.ground,
            secondaryContainer = colors.raised,
            onSecondaryContainer = colors.ink,
            tertiary = accent,
            onTertiary = colors.onAccent,
            tertiaryContainer = accent.copy(alpha = 0.10f),
            onTertiaryContainer = colors.ink,
            background = colors.ground,
            onBackground = colors.ink,
            surface = colors.ground,
            onSurface = colors.ink,
            surfaceVariant = colors.raised,
            onSurfaceVariant = colors.muted,
            surfaceContainerLowest = colors.ground,
            surfaceContainerLow = colors.ground,
            surfaceContainer = colors.raised,
            surfaceContainerHigh = colors.raised,
            surfaceContainerHighest = colors.raised,
            surfaceTint = Color.Transparent,
            outline = colors.muted,
            outlineVariant = colors.hairline,
            error = colors.attention,
            onError = colors.ground,
        )
    }

    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
