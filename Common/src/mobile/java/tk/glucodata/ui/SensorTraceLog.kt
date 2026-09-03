package tk.glucodata.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import tk.glucodata.R
import tk.glucodata.SensorIdentity
import tk.glucodata.ui.viewmodel.SensorInfo

private const val TRACE_TAIL_BYTES = 64L * 1024L
private const val TRACE_LINE_LIMIT = 10

internal fun filterRecentSensorTraceLines(
    lines: List<String>,
    identifiers: Collection<String>,
    limit: Int = TRACE_LINE_LIMIT,
): List<String> {
    val needles = identifiers.mapNotNull { value -> value.trim().takeIf { it.isNotEmpty() } }
    if (needles.isEmpty()) return emptyList()
    return lines.asReversed()
        .filter { line -> needles.any { needle -> line.contains(needle, ignoreCase = true) } }
        .take(limit)
        .asReversed()
}

private fun readRecentSensorTraceLines(file: File, identifiers: Collection<String>): List<String> {
    if (!file.exists()) return emptyList()
    return runCatching {
        val size = file.length()
        file.inputStream().use { stream ->
            if (size > TRACE_TAIL_BYTES) stream.skip(size - TRACE_TAIL_BYTES)
            val lines = stream.bufferedReader().readLines()
            filterRecentSensorTraceLines(
                lines = if (size > TRACE_TAIL_BYTES) lines.drop(1) else lines,
                identifiers = identifiers,
            )
        }
    }.getOrDefault(emptyList())
}

@Composable
internal fun SensorTraceLog(sensor: SensorInfo) {
    val context = LocalContext.current
    val identifiers = remember(sensor.serial, sensor.deviceAddress) {
        buildSet {
            add(sensor.serial)
            sensor.deviceAddress.takeUnless { it.equals("Unknown", ignoreCase = true) }?.let(::add)
            runCatching { SensorIdentity.resolveNativeHistorySensorNames(sensor.serial) }
                .getOrDefault(emptyList())
                .forEach(::add)
        }
    }
    var lines by remember(sensor.serial) { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(context, identifiers) {
        val file = File(context.filesDir, "logs/trace.log")
        while (isActive) {
            lines = withContext(Dispatchers.IO) {
                readRecentSensorTraceLines(file, identifiers)
            }
            delay(2_000L)
        }
    }

    SelectionContainer {
        Text(
            text = if (lines.isEmpty()) {
                stringResource(R.string.no_data_available)
            } else {
                lines.joinToString("\n")
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 32.dp, end = 8.dp, bottom = 8.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
