package tk.glucodata.ui.screens

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import tk.glucodata.Applic
import tk.glucodata.GlucosePoint
import tk.glucodata.NotificationHistorySource
import tk.glucodata.R
import tk.glucodata.UiRefreshBus
import tk.glucodata.ui.components.TrendArrowCanvas

private fun recentReadings(isMmol: Boolean): List<GlucosePoint> = runCatching {
    NotificationHistorySource.getDisplayHistory(System.currentTimeMillis() - 24 * 3_600_000L, isMmol, null)
        .asReversed().take(48)
}.getOrDefault(emptyList())

internal fun formatWearGlucose(value: Float, isMmol: Boolean): String =
    if (isMmol) String.format(Locale.getDefault(), "%.1f", value) else String.format(Locale.getDefault(), "%.0f", value)

/** Band colour for chart traces, following the palette the phone mirrors over. */
internal fun rangeColor(value: Float, isMmol: Boolean, neutral: Color): Color =
    tk.glucodata.ui.WearGlucoseColors.bandColor(value, isMmol, neutral)

@Composable
fun RecentReadingsScreen(onCalibrateReading: ((GlucosePoint) -> Unit)? = null) {
    val isMmol = remember { runCatching { Applic.unit == 1 }.getOrDefault(false) }
    var readings by remember { mutableStateOf(recentReadings(isMmol)) }
    val storeSnapshot by tk.glucodata.ui.WearGlucoseStore.snapshot.collectAsState()
    val viewMode = storeSnapshot.viewMode
    val context = LocalContext.current
    val formatter = remember(context) { DateFormat.getTimeFormat(context) }
    LaunchedEffect(Unit) {
        launch { UiRefreshBus.revision.collect { readings = recentReadings(isMmol) } }
    }
    val velocities = remember(storeSnapshot, readings, isMmol) {
        rowVelocities(storeSnapshot.points, readings, storeSnapshot.isRawMode, isMmol)
    }
    ScreenScaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(contentPadding = PaddingValues(top = 32.dp, bottom = 28.dp, start = 20.dp, end = 20.dp)) {
            if (readings.isEmpty()) {
                item { Text(stringResource(R.string.nodata), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(readings, key = { it.timestamp }) { point ->
                val action = remember(point.timestamp) { ReadingActions.resolve(point.timestamp) }
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .then(
                            onCalibrateReading?.let { calibrate ->
                                Modifier.clickable { calibrate(point) }
                            } ?: Modifier,
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(formatter.format(Date(point.timestamp)), style = MaterialTheme.typography.labelLarge)
                        // Marks a reading that already carries a calibration, so
                        // tapping it edits instead of adding a second one.
                        if (action.hasCalibration) {
                            Text(
                                formatWearGlucose(
                                    if (isMmol) action.calibrationUserValueMgdl / 18.0182f
                                    else action.calibrationUserValueMgdl,
                                    isMmol,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    WearGlucoseValue(
                        point = point,
                        isMmol = isMmol,
                        viewMode = viewMode,
                        style = readingValueStyle(
                            viewMode,
                            singleLane = MaterialTheme.typography.titleLarge,
                            dualLane = MaterialTheme.typography.bodyLarge,
                        ),
                        primaryColor = tk.glucodata.ui.WearGlucoseColors.valueColor(
                            primaryLaneValue(point, viewMode),
                            isMmol,
                            MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                    TrendArrowCanvas(
                        velocity = velocities[point.timestamp] ?: 0f,
                        pulseKey = null,
                        modifier = Modifier.size(14.dp).padding(start = 6.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
