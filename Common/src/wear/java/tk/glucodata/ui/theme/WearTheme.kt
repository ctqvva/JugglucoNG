@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package tk.glucodata.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Typography
import tk.glucodata.R

// Same IBM Plex Sans Variable the phone UI uses (shared res/font).
// Condensed width for data-dense watch layouts, mirroring the phone's
// DisplayLarge configuration in src/mobile Type.kt.
private fun plexFont(weight: Int, width: Float) = Font(
    R.font.ibm_plex_sans_var,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.width(width),
    ),
)

val PlexRegular = FontFamily(plexFont(400, 90f))
val PlexMedium = FontFamily(plexFont(500, 90f))
val PlexDataCondensed = FontFamily(plexFont(500, 85f))

// Phone-derived dark indigo system. Glucose state colors remain data colors and
// are never substituted with theme accents.
private val WearColors = ColorScheme().copy(
    primary = Color(0xFF90CAF9),
    onPrimary = Color(0xFF002F4F),
    primaryContainer = Color(0xFF174F70),
    onPrimaryContainer = Color(0xFFCBE6FF),
    secondary = Color(0xFF81D4FA),
    onSecondary = Color(0xFF003547),
    secondaryContainer = Color(0xFF164D60),
    onSecondaryContainer = Color(0xFFC4EAFF),
    tertiary = Color(0xFFCE93D8),
    onTertiary = Color(0xFF45204D),
    background = Color(0xFF121212),
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFFC7C7C7),
    surfaceContainer = Color(0xFF1E1E1E),
    surfaceContainerLow = Color(0xFF181818),
    surfaceContainerHigh = Color(0xFF292929),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private fun wearTypography(): Typography {
    val base = Typography()
    return base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = PlexDataCondensed),
        displayMedium = base.displayMedium.copy(fontFamily = PlexDataCondensed),
        displaySmall = base.displaySmall.copy(fontFamily = PlexDataCondensed),
        titleLarge = base.titleLarge.copy(fontFamily = PlexMedium),
        titleMedium = base.titleMedium.copy(fontFamily = PlexMedium),
        titleSmall = base.titleSmall.copy(fontFamily = PlexMedium),
        bodyLarge = base.bodyLarge.copy(fontFamily = PlexRegular),
        bodyMedium = base.bodyMedium.copy(fontFamily = PlexRegular),
        bodySmall = base.bodySmall.copy(fontFamily = PlexRegular),
        labelLarge = base.labelLarge.copy(fontFamily = PlexMedium),
        labelMedium = base.labelMedium.copy(fontFamily = PlexMedium),
        labelSmall = base.labelSmall.copy(fontFamily = PlexMedium),
    )
}

@Composable
fun WearJugglucoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = WearColors,
        typography = wearTypography(),
        content = content,
    )
}
