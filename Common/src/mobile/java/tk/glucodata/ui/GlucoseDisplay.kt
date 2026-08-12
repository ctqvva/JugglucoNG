package tk.glucodata.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.TrendingDown
import androidx.compose.material.icons.rounded.TrendingFlat
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import tk.glucodata.R
import kotlin.math.abs

// Helper to format timestamps or date strings into "Mon 20.05.2024 14:30"
fun formatSensorTime(rawTime: String): String {
    if (rawTime.isBlank()) return ""

    val outputFormat = java.text.SimpleDateFormat("EEE dd.MM.yyyy HH:mm", java.util.Locale.getDefault())

    // 1. Try as Unix Timestamp (Digits only)
    if (rawTime.all { it.isDigit() }) {
        val timestamp = rawTime.toLongOrNull()
        if (timestamp != null) {
            val millis = if (timestamp < 10_000_000_000L) timestamp * 1000L else timestamp
            return outputFormat.format(java.util.Date(millis))
        }
    }

    // 2. Try as Full Date "yyyy-MM-dd HH:mm:ss"
    try {
        val fullDateParser = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val date = fullDateParser.parse(rawTime)
        if (date != null) return outputFormat.format(date)
    } catch (e: Exception) {
        // Ignore and try next format
    }

    // 3. Try as Partial Date "MM-dd HH:mm:ss" (What you likely have)
    try {
        val partialParser = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val date = partialParser.parse(rawTime)

        if (date != null) {
            // Fix the Year (partial date defaults to 1970)
            val cal = java.util.Calendar.getInstance()
            cal.time = date

            // Set to Current Year
            val nowCal = java.util.Calendar.getInstance()
            val currentYear = nowCal.get(java.util.Calendar.YEAR)
            cal.set(java.util.Calendar.YEAR, currentYear)

            // Smart Logic: If the resulting date is more than 30 days in the future,
            // it likely belongs to the PREVIOUS year (e.g. It\'s Jan 2025, but date is "12-31")
            // Sensors usually don\'t have future start dates.
            if (cal.timeInMillis > System.currentTimeMillis() + (30L * 24 * 60 * 60 * 1000)) {
                cal.add(java.util.Calendar.YEAR, -1)
            }

            return outputFormat.format(cal.time)
        }
    } catch (e: Exception) {
        // Ignore
    }

    // 4. Fallback: Return raw string if nothing matched
    return rawTime
}

fun getTrendIcon(rate: Float, modifier: Modifier = Modifier): ImageVector =
    when {
        rate > 0.5f -> Icons.Rounded.TrendingUp
        rate < -0.5f -> Icons.Rounded.TrendingDown
        else -> Icons.Rounded.TrendingFlat
    }

@Composable
fun getTrendDescription(rate: Float): String {
    return when {
        rate > 0 -> stringResource(R.string.trend_rising, rate)
        rate < 0 -> stringResource(R.string.trend_falling, abs(rate))
        else -> stringResource(R.string.trend_unchanged)
    }
}

fun getDisplayValues(
    point: GlucosePoint,
    viewMode: Int,
    unit: String,
    calibratedValue: Float? = null
): DisplayValues {
    val isMmol = if (unit.isNotEmpty()) tk.glucodata.ui.util.GlucoseFormatter.isMmol(unit) else tk.glucodata.ui.util.GlucoseFormatter.isMmolApp()
    val hideInitialWhenCalibrated = calibratedValue != null &&
        tk.glucodata.data.calibration.CalibrationManager.shouldHideInitialWhenCalibrated()
    return DisplayValueResolver.resolve(
        autoValue = point.value,
        rawValue = point.rawValue,
        viewMode = viewMode,
        isMmol = isMmol,
        unitLabel = unit,
        calibratedValue = calibratedValue,
        hideInitialWhenCalibrated = hideInitialWhenCalibrated
    )
}

// buildGlucoseString moved to src/main (GlucoseValueText.kt), same package, so
// the watch renders the auto/raw lanes with the identical span hierarchy.
