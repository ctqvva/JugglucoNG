@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package tk.glucodata.ui.restricted

import android.content.Context
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tk.glucodata.R
import tk.glucodata.SensorVendor
import tk.glucodata.drivers.ManagedSensorStatusPolicy
import tk.glucodata.sensorBadge
import tk.glucodata.ui.AnimatedHourglassIcon
import tk.glucodata.ui.CustomCalendarIcon
import tk.glucodata.ui.SensorModelBadge
import tk.glucodata.ui.StatusPalette
import tk.glucodata.ui.lifetimeEndMs
import tk.glucodata.ui.viewmodel.SensorInfo
import tk.glucodata.ui.viewmodel.SensorViewModel

/** Starts before this are unset or bogus (1970); the card then shows no lifetime. */
private const val PLAUSIBLE_START_MS = 1_577_836_800_000L // 2020-01-01

/**
 * The sensor card of a restricted phone, built like the dashboard's sensor card: its container
 * (tertiary near the end), its full-height lifetime fill, the quiet name, and "8 / 28" with the
 * calendar, or the hours left with the hourglass in the last day.
 *
 * [status] and [reading] come from the full card, so the two never disagree about the sensor.
 */
@Composable
internal fun RestrictedSensorCard(
    sensor: SensorInfo,
    status: String?,
    reading: (@Composable () -> Unit)?,
    disconnect: (@Composable () -> Unit)?,
) {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val lifetimeKnown = sensor.startMs > PLAUSIBLE_START_MS
    val endMs = sensor.lifetimeEndMs()
    val lifecycle = ManagedSensorStatusPolicy.resolveLifecycleSummary(
        startTimeMs = sensor.startMs,
        officialEndMs = endMs,
        expectedEndMs = endMs,
        nowMs = now,
    )
    val ended = lifetimeKnown && now >= endMs
    val scheme = MaterialTheme.colorScheme
    val isExpiring = lifetimeKnown && lifecycle.progress > 0.90f
    val containerColor = if (isExpiring) {
        scheme.tertiaryContainer
    } else {
        scheme.primaryContainer.copy(alpha = 0.4f).compositeOver(scheme.background)
    }
    val contentColor = if (isExpiring) scheme.onTertiaryContainer else scheme.onPrimaryContainer
    val quietColor = contentColor.copy(alpha = 0.7f)
    val fillColor = when {
        lifecycle.progress > 0.95f -> scheme.error
        lifecycle.progress > 0.80f -> scheme.tertiary
        else -> StatusPalette.sensorLifeFill
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
    ) {
        Box {
            if (lifetimeKnown && lifecycle.progress > 0f) {
                Box(Modifier.matchParentSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(lifecycle.progress)
                            .background(fillColor.copy(alpha = 0.25f)),
                    )
                }
            }
            Row(modifier = Modifier.padding(16.dp)) {
                val badge = remember(sensor.vendor, sensor.sensorType, sensor.vendorModel, sensor.vendorBrand) {
                    sensorBadge(sensor.vendor, sensor.sensorType, sensor.vendorModel, sensor.vendorBrand)
                }
                SensorModelBadge(badge = badge, vendor = sensor.vendor, color = sensor.color)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = sensor.displayName.ifBlank { sensor.serial },
                                style = MaterialTheme.typography.labelMedium,
                                color = quietColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (lifetimeKnown) {
                                Spacer(Modifier.height(4.dp))
                                SensorLifetimeLabel(
                                    daysText = lifecycle.daysText,
                                    currentDay = lifecycle.currentDay,
                                    remainingHours = lifecycle.remainingHours.takeUnless { ended } ?: -1L,
                                )
                            }
                        }
                        reading?.let {
                            Spacer(Modifier.width(12.dp))
                            it()
                        }
                    }
                    // Below the name, so a long status never squeezes it or the days.
                    if (status != null || lifetimeKnown || disconnect != null) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                if (status != null) {
                                    Text(
                                        text = status,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = quietColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (lifetimeKnown) {
                                    Text(
                                        text = if (ended) {
                                            stringResource(R.string.restricted_sensor_ended)
                                        } else {
                                            stringResource(R.string.restricted_sensor_ends, sensorEndText(context, endMs, now))
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (ended) scheme.error else quietColor,
                                    )
                                }
                            }
                            disconnect?.invoke()
                        }
                    }
                }
            }
        }
    }
}

/** The dashboard's lifetime line: "8 / 28" with the calendar, or "5h" with the hourglass. */
@Composable
private fun SensorLifetimeLabel(daysText: String, currentDay: Int, remainingHours: Long) {
    val lastDay = remainingHours in 0L..24L
    val color = androidx.compose.material3.LocalContentColor.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (lastDay) "${remainingHours}h" else daysText,
            style = MaterialTheme.typography.titleMediumEmphasized.copy(fontFeatureSettings = "tnum"),
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        if (lastDay) {
            AnimatedHourglassIcon(
                color = color.copy(alpha = 0.8f),
                modifier = Modifier.size(16.dp),
                cycleDuration = (500 + (1500 * (remainingHours / 24f))).toInt().coerceAtLeast(500),
            )
        } else {
            CustomCalendarIcon(
                color = color.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp),
                currentDay = currentDay,
            )
        }
    }
}

/** "Thu, 26 Nov", with the time once the end is less than two days away. */
private fun sensorEndText(context: Context, endMs: Long, nowMs: Long): String {
    var flags = DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY or
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR
    if (endMs - nowMs < 2L * 24L * 60L * 60L * 1000L) flags = flags or DateUtils.FORMAT_SHOW_TIME
    return DateUtils.formatDateTime(context, endMs, flags)
}

/** Disconnect while restricted: quiet, and it always keeps the sensor's history. */
@Composable
internal fun RestrictedDisconnectButton(sensor: SensorInfo, viewModel: SensorViewModel) {
    val context = LocalContext.current
    var confirmDisconnect by remember { mutableStateOf(false) }

    TextButton(
        onClick = { confirmDisconnect = true },
        shapes = ButtonDefaults.shapes(),
        contentPadding = ButtonDefaults.TextButtonContentPadding,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) {
        Text(stringResource(R.string.disconnect))
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
