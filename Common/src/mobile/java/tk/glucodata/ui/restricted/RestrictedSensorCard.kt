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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tk.glucodata.R
import tk.glucodata.SensorVendor
import tk.glucodata.sensorBadge
import tk.glucodata.ui.SensorModelBadge
import tk.glucodata.ui.lifetimeEndMs
import tk.glucodata.ui.viewmodel.SensorInfo
import tk.glucodata.ui.viewmodel.SensorViewModel

/** Starts before this are unset or bogus (1970); the card then shows no dates. */
private const val PLAUSIBLE_START_MS = 1_577_836_800_000L // 2020-01-01
private const val DAY_MS = 24L * 60L * 60L * 1000L

/**
 * The sensor card a restricted phone shows: which sensor, and when it started and is
 * expected to end. Nothing on it changes the sensor, except Disconnect where the adult
 * allowed sensor changes; that path never deletes history.
 */
@Composable
internal fun RestrictedSensorCard(
    sensor: SensorInfo,
    viewModel: SensorViewModel,
    allowDisconnect: Boolean,
) {
    val context = LocalContext.current
    var confirmDisconnect by remember { mutableStateOf(false) }
    val tint = sensor.color
    val start = sensor.startMs
    val end = sensor.lifetimeEndMs()
    val datesKnown = start > PLAUSIBLE_START_MS && end > start
    val now = System.currentTimeMillis()
    val progress = if (datesKnown) ((now - start).toFloat() / (end - start)).coerceIn(0f, 1f) else 0f

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Box {
            // The same lifetime fill as the full card: how much of the wear has gone.
            if (datesKnown) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(28.dp)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress)
                            .background(lifetimeColor(progress).copy(alpha = 0.12f)),
                    )
                }
            }
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val badge = remember(sensor.vendor, sensor.sensorType, sensor.vendorModel, sensor.vendorBrand) {
                        sensorBadge(sensor.vendor, sensor.sensorType, sensor.vendorModel, sensor.vendorBrand)
                    }
                    SensorModelBadge(badge = badge, vendor = sensor.vendor, color = tint)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = sensor.displayName.ifBlank { sensor.serial },
                            style = MaterialTheme.typography.titleMediumEmphasized,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (datesKnown) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = restrictedSensorDates(context, start, end),
                                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (datesKnown) {
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = remainingLabel(end - now),
                            style = MaterialTheme.typography.labelLarge,
                            color = lifetimeColor(progress),
                        )
                    }
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
                        // stays, AiDex stays paired, Ottai keeps its account binding.
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

@Composable
private fun lifetimeColor(progress: Float) = when {
    progress > 0.95f -> MaterialTheme.colorScheme.error
    progress > 0.80f -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.primary
}

@Composable
private fun remainingLabel(remainingMs: Long): String {
    val days = restrictedSensorDaysLeft(remainingMs)
    return when {
        remainingMs <= 0L -> stringResource(R.string.restricted_sensor_ended)
        days == 0 -> stringResource(R.string.restricted_sensor_ends_today)
        else -> pluralStringResource(R.plurals.restricted_sensor_days_left, days, days)
    }
}

/** Whole days left, rounded down: "1 day left" lasts until the final 24 hours begin. */
internal fun restrictedSensorDaysLeft(remainingMs: Long): Int =
    if (remainingMs <= 0L) 0 else (remainingMs / DAY_MS).toInt()

/** Start and expected end as short numeric dates in the phone's locale, e.g. "3/24 – 4/7". */
internal fun restrictedSensorDates(context: Context, startMs: Long, endMs: Long): String =
    DateUtils.formatDateRange(
        context,
        startMs,
        endMs,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NUMERIC_DATE or DateUtils.FORMAT_NO_YEAR,
    )

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
