@file:OptIn(ExperimentalMaterial3Api::class)

package tk.glucodata.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import tk.glucodata.DashboardChartColors

enum class ThemeMode {
    SYSTEM, LIGHT, DARK
}

val LocalDarkTheme = androidx.compose.runtime.compositionLocalOf { false }

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
        darkTheme -> darkColorScheme(
            primary = Color(0xFF90CAF9),
            secondary = Color(0xFF81D4FA),
            tertiary = Color(0xFFCE93D8),
            background = Color(0xFF121212),
            surface = Color(0xFF1E1E1E),
            onPrimary = Color.Black,
            onSecondary = Color.Black,
            onTertiary = Color.Black,
            onBackground = Color.White,
            onSurface = Color.White
        )
        else -> lightColorScheme(
            primary = Color(0xFF1565C0),
            secondary = Color(0xFF039BE5),
            tertiary = Color(0xFF7B1FA2),
            background = Color(0xFFFAFAFA),
            surface = Color.White,
            onPrimary = Color.White,
            onSecondary = Color.White,
            onTertiary = Color.White,
            onBackground = Color.Black,
            onSurface = Color.Black
        )
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
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = tk.glucodata.ui.theme.AppTypography,
    ) {
        // Edit 52b (v3): Allow Display Size scaling up to 20% above hardware native.
        // This lets users who want larger UI (accessibility) get a meaningful boost,
        // while preventing extreme Display Size settings from breaking M3 layouts.
        // fontScale capped at 1.15 — "slightly larger" text is fine, "huge" breaks cards.
        val currentDensity = LocalDensity.current
        val nativeDensity = android.util.DisplayMetrics.DENSITY_DEVICE_STABLE / 160f
        val maxDensity = nativeDensity * 1.1f
        val clampedDensity = Density(
            density = currentDensity.density.coerceAtMost(maxDensity),
            fontScale = currentDensity.fontScale.coerceAtMost(1.1f)
        )
        CompositionLocalProvider(
            LocalDensity provides clampedDensity,
            LocalDarkTheme provides darkTheme
        ) {
            content()
        }
    }
}
