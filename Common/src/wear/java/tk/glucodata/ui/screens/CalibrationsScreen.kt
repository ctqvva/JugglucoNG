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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import tk.glucodata.Applic
import tk.glucodata.CalibrationAccess
import tk.glucodata.R
import tk.glucodata.ui.WearNavigationRow
import tk.glucodata.ui.WearSectionTitle

private data class WearCalibration(val sourceValue: Float, val userValue: Float, val timestamp: Long)

private fun calibrations(): List<WearCalibration> {
    val sensor = tk.glucodata.ui.WearGlucoseStore.currentSensor()
    val rawMode = runCatching {
        tk.glucodata.CurrentDisplaySource.resolveViewModeForSensor(sensor) in setOf(1, 3)
    }.getOrDefault(false)
    val packed = CalibrationAccess.getActiveCalibrationAnchors(sensor, rawMode)
    return packed.indices.step(3).mapNotNull { offset ->
        if (offset + 2 >= packed.size) null else WearCalibration(
            packed[offset].toFloat(), packed[offset + 1].toFloat(), packed[offset + 2].toLong(),
        ).takeIf { it.timestamp > 0L && it.userValue.isFinite() && it.userValue > 0f }
    }.asReversed()
}

@Composable
fun CalibrationScreen(
    onCalibrate: () -> Unit,
    onEditCalibration: ((timestamp: Long, userValueMgdl: Float, sensorValueMgdl: Float) -> Unit)? = null,
) {
    var revision by remember { mutableIntStateOf(0) }
    val isMmol = remember { runCatching { Applic.unit == 1 }.getOrDefault(false) }
    val conversion = if (isMmol) 18.0182f else 1f
    val entries = remember(revision) { calibrations() }
    val enabled = remember(revision) {
        runCatching { tk.glucodata.CalibrationAccess.hasActiveCalibration(false, null) }.getOrDefault(false)
    }
    val context = LocalContext.current
    val formatter = remember(context) { DateFormat.getDateFormat(context) }
    val timeFormatter = remember(context) { DateFormat.getTimeFormat(context) }
    val canCalibrate = remember {
        findCalibratableDriver() != null ||
            (tk.glucodata.MessageSender.isWearTransportAvailable() && tk.glucodata.MessageSender.getMessageSender() != null)
    }

    ScreenScaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(contentPadding = PaddingValues(top = 32.dp, bottom = 28.dp, start = 20.dp, end = 20.dp)) {
            item { WearSectionTitle(stringResource(R.string.calibration)) }
            if (canCalibrate) {
                item { WearNavigationRow(stringResource(R.string.calibrate_action), stringResource(R.string.glucose_value), onClick = onCalibrate) }
            }
            // CalibrationManager lives on the phone, so these relay a command
            // and let the resulting calibration state sync back.
            item {
                WearNavigationRow(
                    stringResource(if (enabled) R.string.wear_calibration_on else R.string.wear_calibration_off),
                    onClick = {
                        tk.glucodata.WearCalibrationCommand.send(
                            if (enabled) tk.glucodata.WearCalibrationCommand.DISABLE
                            else tk.glucodata.WearCalibrationCommand.ENABLE,
                        )
                        revision++
                    },
                )
            }
            if (entries.isNotEmpty()) {
                item {
                    WearNavigationRow(
                        stringResource(R.string.wear_calibration_clear),
                        onClick = {
                            tk.glucodata.WearCalibrationCommand.send(tk.glucodata.WearCalibrationCommand.CLEAR)
                            revision++
                        },
                    )
                }
            }
            if (entries.isEmpty()) {
                item { Text(stringResource(R.string.nodata), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(entries, key = { it.timestamp }) { entry ->
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .then(
                            onEditCalibration?.let { edit ->
                                Modifier.clickable {
                                    edit(entry.timestamp, entry.userValue, entry.sourceValue)
                                }
                            } ?: Modifier,
                        )
                        .padding(horizontal = 13.dp, vertical = 10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(timeFormatter.format(Date(entry.timestamp)), style = MaterialTheme.typography.labelLarge)
                        Text(formatter.format(Date(entry.timestamp)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        "${formatWearGlucose(entry.sourceValue / conversion, isMmol)} \u2192 " +
                            formatWearGlucose(entry.userValue / conversion, isMmol),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
