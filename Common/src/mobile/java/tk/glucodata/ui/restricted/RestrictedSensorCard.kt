@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package tk.glucodata.ui.restricted

import android.content.Context
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tk.glucodata.R
import tk.glucodata.SensorVendor
import tk.glucodata.drivers.ManagedSensorStatusPolicy
import tk.glucodata.ui.AnimatedHourglassIcon
import tk.glucodata.ui.CustomCalendarIcon
import tk.glucodata.ui.lifetimeEndMs
import tk.glucodata.ui.sensorLifetimeColor
import tk.glucodata.ui.viewmodel.SensorInfo
import tk.glucodata.ui.viewmodel.SensorViewModel

/** Starts before this are unset or bogus (1970); nothing about the wear is shown then. */
private const val PLAUSIBLE_START_MS = 1_577_836_800_000L // 2020-01-01
private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 24L * HOUR_MS

/** How much wear is left, as it is said to the person wearing the sensor. */
internal sealed interface SensorTimeLeft {
    data class Days(val days: Int) : SensorTimeLeft
    data class Hours(val hours: Int) : SensorTimeLeft
    data object UnderAnHour : SensorTimeLeft
    data object EndPassed : SensorTimeLeft
}

internal data class SensorWear(
    val day: Int,
    val totalDays: Int,
    val progress: Float,
    val left: SensorTimeLeft,
)

/**
 * Where a sensor is in its wear. [endMs] is the end the card shows, so the time left and
 * the Ends row cannot disagree. Whole days are rounded down, which keeps "2 days left"
 * in step with "Day 13 of 15".
 */
internal fun sensorWear(startMs: Long, endMs: Long, nowMs: Long): SensorWear {
    val summary = ManagedSensorStatusPolicy.resolveLifecycleSummary(
        startTimeMs = startMs,
        officialEndMs = endMs,
        expectedEndMs = endMs,
        nowMs = nowMs,
    )
    val leftMs = endMs - nowMs
    val left = when {
        leftMs <= 0L -> SensorTimeLeft.EndPassed
        leftMs < HOUR_MS -> SensorTimeLeft.UnderAnHour
        leftMs < DAY_MS -> SensorTimeLeft.Hours((leftMs / HOUR_MS).toInt())
        else -> SensorTimeLeft.Days((leftMs / DAY_MS).toInt())
    }
    return SensorWear(summary.currentDay, summary.totalDays, summary.progress, left)
}

/**
 * The time left, the day of wear and a bar of the whole wear, in the same colours as the
 * card's fill. The calendar and the hourglass in its last day are the dashboard's.
 */
@Composable
private fun SensorWearSummary(wear: SensorWear) {
    val color = sensorLifetimeColor(wear.progress)
    val days = wear.left as? SensorTimeLeft.Days
    val leftText = when (val left = wear.left) {
        is SensorTimeLeft.Days ->
            pluralStringResource(R.plurals.restricted_sensor_days_left, left.days, left.days)
        is SensorTimeLeft.Hours ->
            pluralStringResource(R.plurals.restricted_sensor_hours_left, left.hours, left.hours)
        SensorTimeLeft.UnderAnHour -> stringResource(R.string.restricted_sensor_last_hour)
        SensorTimeLeft.EndPassed -> stringResource(R.string.restricted_sensor_end_passed)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Wraps instead of clipping where the two labels are long.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = leftText,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = if (days != null) MaterialTheme.colorScheme.onSurface else color,
                )
                Spacer(Modifier.width(8.dp))
                when {
                    days != null -> CustomCalendarIcon(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                        currentDay = wear.day,
                    )
                    wear.left != SensorTimeLeft.EndPassed -> AnimatedHourglassIcon(
                        color = color,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            if (wear.left != SensorTimeLeft.EndPassed) {
                Text(
                    text = stringResource(R.string.restricted_sensor_day, wear.day, wear.totalDays),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Still: a sensor's wear is a level, not something loading.
        LinearWavyProgressIndicator(
            progress = { wear.progress },
            modifier = Modifier.fillMaxWidth(),
            color = color,
            waveSpeed = 0.dp,
        )
    }
}

/**
 * What replaces the full card's details and controls while restricted: how much wear is
 * left, when the sensor started and when it ends, in the card's own data rows, and Disconnect
 * when the adult allowed sensor changes. That Disconnect never deletes history.
 */
@Composable
internal fun RestrictedSensorDetails(
    sensor: SensorInfo,
    viewModel: SensorViewModel,
    allowDisconnect: Boolean,
) {
    val context = LocalContext.current
    var confirmDisconnect by remember { mutableStateOf(false) }

    if (sensor.startMs > PLAUSIBLE_START_MS) {
        Spacer(modifier = Modifier.height(16.dp))
        RestrictedSensorWearPanel(sensor)
    }

    if (allowDisconnect && !sensor.isCloneSource) {
        Spacer(modifier = Modifier.height(16.dp))
        FilledTonalButton(
            onClick = { confirmDisconnect = true },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp),
            shapes = ButtonDefaults.shapes(),
            contentPadding = ButtonDefaults.ContentPadding,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Text(stringResource(R.string.disconnect))
        }
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text(stringResource(R.string.disconnect_sensor_title)) },
            text = { Text(stringResource(R.string.restricted_disconnect_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDisconnect = false
                        // The keep-everything variant of each vendor's Disconnect: history
                        // stays, AiDex stays paired, Ottai keeps its saved keys.
                        val removed = when {
                            sensor.isAidex -> {
                                viewModel.disconnectAiDexSensor(sensor.serial, breakPairing = false)
                                true
                            }
                            sensor.vendor == SensorVendor.OTTAI ->
                                viewModel.disconnectOttaiSensor(sensor.serial, wipeData = false, forgetAndUnbind = false)
                            else -> viewModel.terminateSensor(sensor.serial, wipeData = false)
                        }
                        if (!removed) {
                            Toast.makeText(context, R.string.disconnect_sensor_failed, Toast.LENGTH_LONG).show()
                        }
                    },
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                ) { Text(stringResource(R.string.disconnect)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmDisconnect = false },
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                ) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** The wear summary above the Started and Ends rows, in one panel. */
@Composable
internal fun RestrictedSensorWearPanel(sensor: SensorInfo) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val end = sensor.lifetimeEndMs()
            SensorWearSummary(sensorWear(sensor.startMs, end, System.currentTimeMillis()))
            Spacer(Modifier.height(4.dp))
            val context = LocalContext.current
            DetailRow(stringResource(R.string.sensor_started), wearDate(context, sensor.startMs))
            val endLabel = if (sensor.officialEndMs > 0) R.string.sensor_ends_officially else R.string.sensor_expected_end
            DetailRow(stringResource(endLabel), wearDate(context, end))
        }
    }
}

/**
 * "Sun, 8 Nov, 13:54", in the phone's language and clock. No year: a sensor is worn for weeks,
 * and without it the date stays on one line at large text.
 */
private fun wearDate(context: Context, ms: Long): String = DateUtils.formatDateTime(
    context,
    ms,
    DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY or
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR or
        DateUtils.FORMAT_SHOW_TIME,
)

/** The full card's label–value row. */
@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

/** The Sensors tab with no sensor, on a restricted phone that may not add one. */
@Composable
internal fun RestrictedSensorsLockedState(onUnlock: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.no_sensors_connected),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.restricted_sensors_locked),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        FilledTonalButton(
            onClick = onUnlock,
            shapes = ButtonDefaults.shapes(),
            contentPadding = ButtonDefaults.ContentPadding,
            modifier = Modifier.heightIn(min = 40.dp),
        ) {
            Text(stringResource(R.string.restricted_unlock_title))
        }
    }
}
