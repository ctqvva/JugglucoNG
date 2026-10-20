@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package tk.glucodata.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import tk.glucodata.DashboardChartColors

/**
 * Whether the app is drawing dark, after the in-app Theme setting is applied. Null outside
 * [JugglucoTheme] (the floating overlay and other windows of their own), where the system
 * setting is the only one that applies.
 */
private val LocalAppDarkTheme = staticCompositionLocalOf<Boolean?> { null }

/**
 * Use this, not `isSystemInDarkTheme()`, for anything drawn inside the app: the Theme setting
 * can force light or dark against the system, and glucose colours picked for the wrong one
 * land on the wrong surface.
 */
@Composable
@ReadOnlyComposable
fun isAppInDarkTheme(): Boolean = LocalAppDarkTheme.current ?: isSystemInDarkTheme()

enum class ThemeMode {
    SYSTEM, LIGHT, DARK
}

@Composable
fun JugglucoTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = if (darkTheme) GisDarkColorScheme else GisLightColorScheme

    val view = LocalView.current
    SideEffect {
        DashboardChartColors.update(
            darkTheme = darkTheme,
            primary = colorScheme.primary.toArgb(),
            onSurfaceVariant = colorScheme.onSurfaceVariant.toArgb()
        )
    }
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    // Expressive theme and motion: switches, sliders, navigation indicators, sheets and
    // buttons all move on the same springs as the hand-built components (ExpressiveMotion
    // mirrors MotionScheme.expressive()).
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        typography = tk.glucodata.ui.theme.AppTypography.withFamily(androidx.compose.ui.text.font.FontFamily.SansSerif),
    ) {
        // Display size (density) may grow at most 10% past the device's native density, so an
        // extreme Display Size setting cannot break M3 layouts. Font size is not capped here:
        // text follows the system setting, and the few surfaces too dense to reflow — the
        // dashboard header and chart, sensor cards, Statistics, navigation labels — cap
        // themselves with [FontScaleCap].
        val currentDensity = LocalDensity.current
        val nativeDensity = android.util.DisplayMetrics.DENSITY_DEVICE_STABLE / 160f
        val maxDensity = nativeDensity * 1.1f
        val clampedDensity = Density(
            density = currentDensity.density.coerceAtMost(maxDensity),
            fontScale = currentDensity.fontScale
        )
        CompositionLocalProvider(
            LocalDensity provides clampedDensity,
            LocalAppDarkTheme provides darkTheme,
        ) {
            content()
        }
    }
}

// Below API 31 there is no wallpaper colour to follow. A full tonal-spot scheme generated from
// the app's blue (#1565C0, material-color-utilities, 2025 spec), so the surface-container roles
// every card here is built on are part of the same palette instead of the library's baseline.
private val FallbackLightColorScheme = lightColorScheme(
    primary = Color(0xFF465F8A),
    onPrimary = Color(0xFFF8F8FF),
    primaryContainer = Color(0xFFB3CDFE),
    onPrimaryContainer = Color(0xFF2A446D),
    inversePrimary = Color(0xFFB3CDFE),
    secondary = Color(0xFF565F72),
    onSecondary = Color(0xFFF8F8FF),
    secondaryContainer = Color(0xFFDAE2F9),
    onSecondaryContainer = Color(0xFF495264),
    tertiary = Color(0xFF675882),
    onTertiary = Color(0xFFFEF7FF),
    tertiaryContainer = Color(0xFFDFCCFD),
    onTertiaryContainer = Color(0xFF50426A),
    background = Color(0xFFF9F9FE),
    onBackground = Color(0xFF2F323A),
    surface = Color(0xFFF9F9FE),
    onSurface = Color(0xFF2F323A),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF5C5F68),
    surfaceTint = Color(0xFF465F8A),
    inverseSurface = Color(0xFF0C0E12),
    inverseOnSurface = Color(0xFF9C9CA2),
    error = Color(0xFFA83836),
    onError = Color(0xFFFFF7F6),
    errorContainer = Color(0xFFFA746F),
    onErrorContainer = Color(0xFF6E0A12),
    outline = Color(0xFF777B84),
    outlineVariant = Color(0xFFAFB2BC),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF9F9FE),
    surfaceContainer = Color(0xFFECEDF6),
    surfaceContainerHigh = Color(0xFFE6E8F1),
    surfaceContainerHighest = Color(0xFFE0E2EC),
    surfaceContainerLow = Color(0xFFF3F3FA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFD7DAE4),
)

private val FallbackDarkColorScheme = darkColorScheme(
    primary = Color(0xFFB4C7ED),
    onPrimary = Color(0xFF2E4060),
    primaryContainer = Color(0xFF405373),
    onPrimaryContainer = Color(0xFFD7E3FF),
    inversePrimary = Color(0xFF4D5F80),
    secondary = Color(0xFFBDC7DC),
    onSecondary = Color(0xFF374052),
    secondaryContainer = Color(0xFF333C4D),
    onSecondaryContainer = Color(0xFFB6BFD5),
    tertiary = Color(0xFFEBDDFF),
    onTertiary = Color(0xFF594B74),
    tertiaryContainer = Color(0xFFDFCCFD),
    onTertiaryContainer = Color(0xFF50426A),
    background = Color(0xFF0C0E12),
    onBackground = Color(0xFFE3E5EF),
    surface = Color(0xFF0C0E12),
    onSurface = Color(0xFFE3E5EF),
    surfaceVariant = Color(0xFF22262D),
    onSurfaceVariant = Color(0xFFA8ABB5),
    surfaceTint = Color(0xFFB4C7ED),
    inverseSurface = Color(0xFFF9F9FE),
    inverseOnSurface = Color(0xFF545559),
    error = Color(0xFFFA746F),
    onError = Color(0xFF490006),
    errorContainer = Color(0xFF871F21),
    onErrorContainer = Color(0xFFFF9993),
    outline = Color(0xFF72757E),
    outlineVariant = Color(0xFF444850),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF292C34),
    surfaceContainer = Color(0xFF171A1F),
    surfaceContainerHigh = Color(0xFF1D2026),
    surfaceContainerHighest = Color(0xFF22262D),
    surfaceContainerLow = Color(0xFF111318),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceDim = Color(0xFF0C0E12),
)

/**
 * The largest font scale a data-dense surface follows. Body text — settings, lists, dialogs,
 * explanations — reflows and follows the system setting all the way. A glucose hero, a chart
 * axis, a row of stat tiles or a navigation label sits in a fixed-width slot instead, and past
 * about 1.3x it truncates into something nobody can read. Android 14's non-linear font scaling
 * makes the same trade: large text grows less than small text.
 */
const val DENSE_FONT_SCALE_CAP = 1.3f

/** Caps the font scale of [content] at [max]; see [DENSE_FONT_SCALE_CAP]. */
@Composable
fun FontScaleCap(max: Float = DENSE_FONT_SCALE_CAP, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    // Always provided, even when nothing is capped, so crossing the cap does not change the
    // shape of the composition and throw away the content's state.
    val capped = if (density.fontScale <= max) density else Density(density.density, max)
    CompositionLocalProvider(LocalDensity provides capped, content = content)
}


// Единый стиль с основным приложением GIS: те же цвета (бирюзовый акцент, сине-чёрный фон в тёмной теме) и системный шрифт.
private val GisLightColorScheme = lightColorScheme(
    primary = Color(0xFF0A6B66), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD4ECEA), onPrimaryContainer = Color(0xFF053B38),
    secondary = Color(0xFF3B4A5F), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE2E8F0), onSecondaryContainer = Color(0xFF0B1220),
    tertiary = Color(0xFFB45309), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFDE9D0), onTertiaryContainer = Color(0xFF4A2204),
    error = Color(0xFFB91C1C), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFBDADA), onErrorContainer = Color(0xFF450A0A),
    background = Color(0xFFE6ECF3), onBackground = Color(0xFF0B1220),
    surface = Color(0xFFE6ECF3), onSurface = Color(0xFF0B1220),
    surfaceVariant = Color(0xFFF3F6FA), onSurfaceVariant = Color(0xFF3B4A5F),
    outline = Color(0xFF7A8699), outlineVariant = Color(0xFFB3BFD0),
    surfaceTint = Color(0xFF0A6B66),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF3F6FA),
    surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHigh = Color(0xFFF3F6FA), surfaceContainerHighest = Color(0xFFE2E8F0),
)

private val GisDarkColorScheme = darkColorScheme(
    primary = Color(0xFF5FD0C5), onPrimary = Color(0xFF06201F),
    primaryContainer = Color(0xFF14302F), onPrimaryContainer = Color(0xFFBFF0EA),
    secondary = Color(0xFFA6B3C7), onSecondary = Color(0xFF0B1220),
    secondaryContainer = Color(0xFF1C2639), onSecondaryContainer = Color(0xFFE7EDF8),
    tertiary = Color(0xFFF6C177), onTertiary = Color(0xFF2B1A03),
    tertiaryContainer = Color(0xFF3A2A10), onTertiaryContainer = Color(0xFFFBE3BC),
    error = Color(0xFFF59E98), onError = Color(0xFF3A0A08),
    errorContainer = Color(0xFF4A1B18), onErrorContainer = Color(0xFFFCD9D6),
    background = Color(0xFF080D19), onBackground = Color(0xFFE7EDF8),
    surface = Color(0xFF080D19), onSurface = Color(0xFFE7EDF8),
    surfaceVariant = Color(0xFF0F1629), onSurfaceVariant = Color(0xFFA6B3C7),
    outline = Color(0xFF73819B), outlineVariant = Color(0xFF31405F),
    surfaceTint = Color(0xFF5FD0C5),
    surfaceContainerLowest = Color(0xFF05080F), surfaceContainerLow = Color(0xFF0F1629),
    surfaceContainer = Color(0xFF1A2438), surfaceContainerHigh = Color(0xFF1F2B42), surfaceContainerHighest = Color(0xFF26334D),
)

private fun androidx.compose.material3.Typography.withFamily(f: androidx.compose.ui.text.font.FontFamily) = androidx.compose.material3.Typography(
    displayLarge = displayLarge.copy(fontFamily = f), displayMedium = displayMedium.copy(fontFamily = f), displaySmall = displaySmall.copy(fontFamily = f),
    headlineLarge = headlineLarge.copy(fontFamily = f), headlineMedium = headlineMedium.copy(fontFamily = f), headlineSmall = headlineSmall.copy(fontFamily = f),
    titleLarge = titleLarge.copy(fontFamily = f), titleMedium = titleMedium.copy(fontFamily = f), titleSmall = titleSmall.copy(fontFamily = f),
    bodyLarge = bodyLarge.copy(fontFamily = f), bodyMedium = bodyMedium.copy(fontFamily = f), bodySmall = bodySmall.copy(fontFamily = f),
    labelLarge = labelLarge.copy(fontFamily = f), labelMedium = labelMedium.copy(fontFamily = f), labelSmall = labelSmall.copy(fontFamily = f),
)
