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

    val colorScheme = when {
        android.os.Build.VERSION.SDK_INT >= 31 -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> FallbackDarkColorScheme
        else -> FallbackLightColorScheme
    }

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
            // The navigation panel and page draw behind this edge-to-edge system area.
            // One owner avoids a later theme recomposition repainting an opaque strip.
            window.navigationBarColor = Color.Transparent.toArgb()
            if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
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
        typography = tk.glucodata.ui.theme.AppTypography,
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
