@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package tk.glucodata.ui.restricted

import android.content.Context
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tk.glucodata.GlucoseValueTone
import tk.glucodata.Notify
import tk.glucodata.R
import tk.glucodata.SensorVendor
import tk.glucodata.drivers.ManagedSensorStatusPolicy
import tk.glucodata.sensorBadge
import tk.glucodata.ui.AnimatedHourglassIcon
import tk.glucodata.ui.CustomCalendarIcon
import tk.glucodata.ui.SensorModelBadge
import tk.glucodata.ui.StatusPalette
import tk.glucodata.ui.getTrendIcon
import tk.glucodata.ui.isAppInDarkTheme
import tk.glucodata.ui.lifetimeEndMs
import tk.glucodata.ui.rememberSensorCurrentSnapshot
import tk.glucodata.ui.util.SensorReadingAgeLabel
import tk.glucodata.ui.viewmodel.SensorInfo
import tk.glucodata.ui.viewmodel.SensorViewModel

/** Starts before this are unset or bogus (1970); the card then shows no lifetime. */
private const val PLAUSIBLE_START_MS = 1_577_836_800_000L // 2020-01-01

/** The user's glucose ranges, for colouring a reading the way the dashboard does. */
internal data class RestrictedReadingRange(
    val isMmol: Boolean,
    val targetLow: Float,
    val targetHigh: Float,
    val veryLow: Float,
    val veryHigh: Float,
    val valueRangeColors: Boolean,
)

/**
 * The sensor card a restricted phone shows, built like the dashboard's sensor card: the same
 * container (amber once the sensor is nearly done), the same lifetime fill, the same
 * "day / days" with its calendar (or the hours left, with the hourglass), and the sensor's
 * newest reading tinted by range like the dashboard hero. Nothing on it changes the sensor,
 * except Disconnect where the adult allowed sensor changes; that path never deletes history.
 */
@Composable
internal fun RestrictedSensorCard(
    sensor: SensorInfo,
    viewModel: SensorViewModel,
    allowDisconnect: Boolean,
    range: RestrictedReadingRange,
) {
    val context = LocalContext.current
    var confirmDisconnect by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val lifetimeKnown = sensor.startMs > PLAUSIBLE_START_MS
    val lifecycle = remember(sensor.startMs, sensor.officialEndMs, sensor.expectedEndMs, sensor.vendorModel, now / 60_000L) {
        ManagedSensorStatusPolicy.resolveLifecycleSummary(
            startTimeMs = sensor.startMs,
            officialEndMs = sensor.officialEndMs,
            expectedEndMs = sensor.expectedEndMs,
            fallbackDurationDays = if (sensor.isAidex) {
                tk.glucodata.drivers.aidex.native.protocol.AiDexWearProfile.ratedDays(sensor.vendorModel) ?: 15
            } else 14,
            nowMs = now,
        )
    }
    val endMs = sensor.lifetimeEndMs()
    val ended = lifetimeKnown && now >= endMs
    // The dashboard sensor card's colours.
    val isExpiring = lifecycle.progress > 0.90f
    val containerColor = if (isExpiring) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            .compositeOver(MaterialTheme.colorScheme.background)
    }
    val contentColor = if (isExpiring) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }
    val fillColor = when {
        lifecycle.progress > 0.95f -> MaterialTheme.colorScheme.error
        lifecycle.progress > 0.80f -> MaterialTheme.colorScheme.tertiary
        else -> StatusPalette.sensorLifeFill
    }
    val shape = MaterialTheme.shapes.extraLarge

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
    ) {
        Box {
            if (lifetimeKnown && lifecycle.progress > 0f) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(shape),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(lifecycle.progress)
                            .background(fillColor.copy(alpha = 0.25f)),
                    )
                }
            }
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val badge = remember(sensor.vendor, sensor.sensorType, sensor.vendorModel, sensor.vendorBrand) {
                        sensorBadge(sensor.vendor, sensor.sensorType, sensor.vendorModel, sensor.vendorBrand)
                    }
                    SensorModelBadge(badge = badge, vendor = sensor.vendor, color = sensor.color)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = sensor.displayName.ifBlank { sensor.serial },
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (lifetimeKnown && !ended) {
                        Spacer(Modifier.width(12.dp))
                        LifecycleLabel(
                            daysText = lifecycle.daysText,
                            currentDay = lifecycle.currentDay,
                            remainingHours = lifecycle.remainingHours,
                            color = contentColor,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                SensorReadingPanel(sensor = sensor, range = range)
                if (lifetimeKnown) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = if (ended) {
                            stringResource(R.string.restricted_sensor_ended)
                        } else {
                            stringResource(R.string.restricted_sensor_ends, restrictedSensorEnd(context, endMs, now))
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (ended) MaterialTheme.colorScheme.error else contentColor.copy(alpha = 0.8f),
                    )
                }
                if (allowDisconnect && !sensor.isCloneSource) {
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = { confirmDisconnect = true },
                        shapes = ButtonDefaults.shapes(),
                        contentPadding = ButtonDefaults.ContentPadding,
                        modifier = Modifier.heightIn(min = 40.dp),
                    ) {
                        Text(stringResource(R.string.disconnect))
                    }
                }
            }
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

/** "6 / 28" with the calendar, or the hours left with the hourglass in the last day. */
@Composable
private fun LifecycleLabel(daysText: String, currentDay: Int, remainingHours: Long, color: Color) {
    val lastDay = remainingHours in 0L..24L
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            // The dashboard's own notation: day of wear over rated days, or hours in the last day.
            text = if (lastDay) "${remainingHours}h" else daysText,
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            color = color,
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        if (lastDay) {
            AnimatedHourglassIcon(
                color = color.copy(alpha = 0.8f),
                modifier = Modifier.size(14.dp),
                cycleDuration = (500 + (1500 * (remainingHours / 24f))).toInt().coerceAtLeast(500),
            )
        } else {
            CustomCalendarIcon(
                color = color.copy(alpha = 0.6f),
                modifier = Modifier.size(14.dp),
                currentDay = currentDay,
            )
        }
    }
}

/**
 * The sensor's newest reading, on the dashboard hero's container: tinted by its range band
 * while fresh, with the value in the range colour when the user turned value colours on.
 */
@Composable
private fun SensorReadingPanel(sensor: SensorInfo, range: RestrictedReadingRange) {
    val snapshot = rememberSensorCurrentSnapshot(sensor)
    val isDark = isAppInDarkTheme()
    val base = MaterialTheme.colorScheme.primaryContainer
    val onBase = MaterialTheme.colorScheme.onPrimaryContainer
    val fresh = snapshot != null &&
        kotlin.math.abs(System.currentTimeMillis() - snapshot.timeMillis) <= Notify.glucosetimeout
    val tone = snapshot?.let {
        GlucoseValueTone.heroTone(
            value = it.primaryValue,
            isDark = isDark,
            isMmol = range.isMmol,
            targetLow = range.targetLow,
            targetHigh = range.targetHigh,
            veryLowThreshold = range.veryLow,
            veryHighThreshold = range.veryHigh,
            isFreshData = fresh,
        )
    }
    val container = tone?.let { lerp(base, Color(it.tintArgb), it.blendFraction) } ?: base
    val valueColor = if (snapshot != null && fresh) {
        Color(
            GlucoseValueTone.valueColorArgb(
                value = snapshot.primaryValue,
                isDark = isDark,
                isMmol = range.isMmol,
                targetLow = range.targetLow,
                targetHigh = range.targetHigh,
                veryLowThreshold = range.veryLow,
                veryHighThreshold = range.veryHigh,
                fallbackArgb = onBase.toArgb(),
                enabled = range.valueRangeColors,
            )
        )
    } else {
        onBase.copy(alpha = 0.6f)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = container,
        contentColor = onBase,
    ) {
        if (snapshot == null) {
            Text(
                text = stringResource(R.string.nodata),
                style = MaterialTheme.typography.bodyLarge,
                color = onBase.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            )
        } else {
            Row(
                modifier = Modifier
                    .heightIn(min = 72.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = snapshot.primaryStr,
                    style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
                    color = valueColor,
                    maxLines = 1,
                )
                if (fresh && snapshot.rate.isFinite()) {
                    Icon(
                        imageVector = getTrendIcon(snapshot.rate),
                        contentDescription = null,
                        tint = valueColor,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                SensorReadingAgeLabel(
                    readingMillis = snapshot.timeMillis,
                    iconTint = sensor.color.copy(alpha = 0.82f),
                    textColor = onBase.copy(alpha = 0.8f),
                    textStyle = MaterialTheme.typography.labelLarge,
                    iconSize = 16.dp,
                )
            }
        }
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

/**
 * When the sensor ends, said plainly: weekday and date ("Wed, 26 Nov"), with the time too
 * once it is less than two days away.
 */
internal fun restrictedSensorEnd(context: Context, endMs: Long, nowMs: Long): String {
    var flags = DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY or
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR
    if (endMs - nowMs < 2L * 24L * 60L * 60L * 1000L) flags = flags or DateUtils.FORMAT_SHOW_TIME
    return DateUtils.formatDateTime(context, endMs, flags)
}
