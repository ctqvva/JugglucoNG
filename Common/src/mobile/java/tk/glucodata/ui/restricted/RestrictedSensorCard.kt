@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package tk.glucodata.ui.restricted

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import tk.glucodata.R
import tk.glucodata.SensorVendor
import tk.glucodata.drivers.ManagedSensorStatusPolicy
import tk.glucodata.ui.AnimatedHourglassIcon
import tk.glucodata.ui.CustomCalendarIcon
import tk.glucodata.ui.StatusPalette
import tk.glucodata.ui.lifetimeEndMs
import tk.glucodata.ui.viewmodel.SensorInfo
import tk.glucodata.ui.viewmodel.SensorViewModel

/** Starts before this are unset or bogus (1970); nothing about the wear is shown then. */
private const val PLAUSIBLE_START_MS = 1_577_836_800_000L // 2020-01-01
private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 24L * HOUR_MS

/** How the wear shows on a restricted card, in the dashboard sensor card's colours. */
internal class SensorLife(
    val progress: Float,
    val containerColor: Color,
    val contentColor: Color,
    val fillColor: Color,
    val daysText: String,
    val currentDay: Int,
    val hoursLeft: Long?,
    val startMs: Long,
    val endMs: Long,
    val nowMs: Long,
)

/** Null when the start is unset or bogus: nothing about the wear is known then. */
@Composable
internal fun sensorLife(sensor: SensorInfo): SensorLife? {
    if (sensor.startMs <= PLAUSIBLE_START_MS) return null
    val now = System.currentTimeMillis()
    val endMs = sensor.lifetimeEndMs()
    val lifecycle = ManagedSensorStatusPolicy.resolveLifecycleSummary(
        startTimeMs = sensor.startMs,
        officialEndMs = endMs,
        expectedEndMs = endMs,
        nowMs = now,
    )
    val scheme = MaterialTheme.colorScheme
    val isExpiring = lifecycle.progress > 0.90f
    return SensorLife(
        progress = lifecycle.progress,
        containerColor = if (isExpiring) {
            scheme.tertiaryContainer
        } else {
            scheme.primaryContainer.copy(alpha = 0.4f).compositeOver(scheme.background)
        },
        contentColor = if (isExpiring) scheme.onTertiaryContainer else scheme.onPrimaryContainer,
        fillColor = when {
            lifecycle.progress > 0.95f -> scheme.error
            lifecycle.progress > 0.80f -> scheme.tertiary
            else -> StatusPalette.sensorLifeFill
        }.copy(alpha = 0.25f),
        daysText = lifecycle.daysText,
        currentDay = lifecycle.currentDay,
        hoursLeft = lifecycle.remainingHours.takeIf { now < endMs && it in 0L..24L },
        startMs = sensor.startMs,
        endMs = endMs,
        nowMs = now,
    )
}

/** The dashboard's lifetime fill, from the start edge. */
internal fun Modifier.sensorLifeFill(life: SensorLife): Modifier = drawBehind {
    val width = size.width * life.progress
    val left = if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f
    drawRect(color = life.fillColor, topLeft = Offset(left, 0f), size = Size(width, size.height))
}

/** "8 / 28" with the calendar, or the hours left with the hourglass in the last day. */
@Composable
internal fun SensorLifeDays(life: SensorLife) {
    val hoursLeft = life.hoursLeft
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = hoursLeft?.let { "${it}h" } ?: life.daysText,
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            color = life.contentColor,
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        if (hoursLeft != null) {
            AnimatedHourglassIcon(
                color = life.contentColor.copy(alpha = 0.8f),
                modifier = Modifier.size(16.dp),
                cycleDuration = (500 + (1500 * (hoursLeft / 24f))).toInt().coerceAtLeast(500),
            )
        } else {
            CustomCalendarIcon(
                color = life.contentColor.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp),
                currentDay = life.currentDay,
            )
        }
    }
}

/** Started and Ends side by side, one tile each: the date first, the time under it. */
@Composable
private fun SensorDates(life: SensorLife) {
    val leftMs = life.endMs - life.nowMs
    val ended = leftMs <= 0L
    val endLabel = when {
        ended -> stringResource(R.string.restricted_sensor_ended)
        leftMs < HOUR_MS -> stringResource(R.string.restricted_sensor_ends_soon)
        leftMs < DAY_MS -> (leftMs / HOUR_MS).toInt().let {
            pluralStringResource(R.plurals.restricted_sensor_ends_in_hours, it, it)
        }
        else -> (leftMs / DAY_MS).toInt().let {
            pluralStringResource(R.plurals.restricted_sensor_ends_in_days, it, it)
        }
    }
    // Same height when one tile's label wraps and the other's does not.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SensorDateTile(
            label = stringResource(R.string.sensor_started),
            timeMs = life.startMs,
            shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp, topEnd = 4.dp, bottomEnd = 4.dp),
        )
        SensorDateTile(
            label = endLabel,
            labelColor = if (ended) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            timeMs = life.endMs,
            shape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 12.dp, bottomEnd = 12.dp),
        )
    }
}

@Composable
private fun RowScope.SensorDateTile(
    label: String,
    timeMs: Long,
    shape: Shape,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        // From the bottom, so the two dates stay level when one label takes two lines.
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(12.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = labelColor,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = DateUtils.formatDateTime(
                    context,
                    timeMs,
                    DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY or
                        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR,
                ),
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = DateUtils.formatDateTime(context, timeMs, DateUtils.FORMAT_SHOW_TIME),
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * What replaces the full card's details and controls while restricted: when the sensor
 * started and ends, and Disconnect when the adult allowed sensor changes. That Disconnect
 * never deletes history.
 */
@Composable
internal fun RestrictedSensorDetails(
    sensor: SensorInfo,
    life: SensorLife?,
    viewModel: SensorViewModel,
    allowDisconnect: Boolean,
) {
    val context = LocalContext.current
    var confirmDisconnect by remember { mutableStateOf(false) }

    if (life != null) {
        Spacer(modifier = Modifier.height(12.dp))
        SensorDates(life)
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
