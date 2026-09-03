package tk.glucodata.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import tk.glucodata.R
import tk.glucodata.SensorIdentity
import tk.glucodata.SensorVendor
import tk.glucodata.ui.viewmodel.SensorInfo

private const val TRACE_TAIL_BYTES = 256L * 1024L
private const val TRACE_LINE_LIMIT = 200

/**
 * Native bookkeeping that says nothing about a connection. These lines dominated the log —
 * a sensor id appears in each of them, so identity matching alone surfaced almost nothing
 * else — while the driver traffic worth reading was filtered out for *not* naming the sensor.
 */
private val TRACE_NOISE_MARKERS = listOf(
    "get previous state",
    "setSensorWearDays",
    "ensureDirectStreamShell",
    "savepollallIDsonly",
    "hasSensorStreamCapacity",
    "wakebackup",
    "wakesender",
    "setstreaming",
    "getdataptr",
    "freedataptr",
    "incUsage",
    "decUsage",
    "locknew",
    "scanstate",
    "updateDevices",
    "checkBluetoothAddress",
    "deactivated",
    "makefilename",
)

/**
 * Driver tags whose lines belong to this sensor. Most of what a person needs when a
 * connection misbehaves — handshake stages, protocol frames, end-cycle results — is logged
 * against the driver tag and never mentions the sensor id, so tag attribution is the only
 * way to surface it. Two sensors of the same family share a tag; that is better than
 * showing neither.
 */
internal fun sensorTraceDriverTags(vendor: SensorVendor?): List<String> = when (vendor) {
    SensorVendor.YUWELL -> listOf("Anytime")
    SensorVendor.OTTAI -> listOf("Ottai")
    SensorVendor.SIBIONICS -> listOf("Sibionics")
    SensorVendor.MICROTECH -> listOf("AiDex")
    SensorVendor.GLUTEC -> listOf("MQ")
    SensorVendor.SINOCARE -> listOf("ICan")
    SensorVendor.NIGHTSCOUT -> listOf("Nightscout")
    else -> emptyList()
}

/** `1788437835 22712 I/Anytime CT5 identity check OK` -> level `I`, or 0 when the line has none. */
private fun traceLevel(line: String): Char {
    val marker = line.indexOf('/')
    if (marker <= 0) return ' '
    val level = line[marker - 1]
    return if (level in "VDIWEA" && (marker < 2 || line[marker - 2] == ' ')) level else ' '
}

internal fun isUsefulSensorTraceLine(line: String): Boolean {
    // A warning or an error is the whole reason someone opened this log.
    val level = traceLevel(line)
    if (level == 'W' || level == 'E') return true
    return TRACE_NOISE_MARKERS.none { marker -> line.contains(marker, ignoreCase = true) }
}

/**
 * Trade the epoch seconds and pid for a wall clock, which is what a reader is actually
 * matching against when they say "it dropped out around twenty past".
 */
internal fun formatSensorTraceLine(line: String, formatTime: (Long) -> String): String {
    val firstGap = line.indexOf(' ')
    if (firstGap <= 0) return line
    val seconds = line.substring(0, firstGap).toLongOrNull() ?: return line
    val secondGap = line.indexOf(' ', firstGap + 1)
    if (secondGap <= 0) return line
    val rest = line.substring(secondGap + 1).trim()
    if (rest.isEmpty()) return line
    return "${formatTime(seconds * 1_000L)}  $rest"
}

internal fun filterRecentSensorTraceLines(
    lines: List<String>,
    identifiers: Collection<String>,
    driverTags: Collection<String> = emptyList(),
    limit: Int = TRACE_LINE_LIMIT,
): List<String> {
    val needles = identifiers.mapNotNull { value -> value.trim().takeIf { it.isNotEmpty() } }
    val tags = driverTags.mapNotNull { value -> value.trim().takeIf { it.isNotEmpty() } }
    if (needles.isEmpty() && tags.isEmpty()) return emptyList()
    return lines.asReversed()
        .asSequence()
        .filter(::isUsefulSensorTraceLine)
        .filter { line ->
            needles.any { needle -> line.contains(needle, ignoreCase = true) } ||
                tags.any { tag -> line.contains("/$tag", ignoreCase = true) }
        }
        .take(limit)
        .toList()
        .asReversed()
}

private fun readRecentSensorTraceLines(
    file: File,
    identifiers: Collection<String>,
    driverTags: Collection<String>,
): List<String> {
    if (!file.exists()) return emptyList()
    return runCatching {
        val size = file.length()
        file.inputStream().use { stream ->
            if (size > TRACE_TAIL_BYTES) stream.skip(size - TRACE_TAIL_BYTES)
            val lines = stream.bufferedReader().readLines()
            filterRecentSensorTraceLines(
                lines = if (size > TRACE_TAIL_BYTES) lines.drop(1) else lines,
                identifiers = identifiers,
                driverTags = driverTags,
            )
        }
    }.getOrDefault(emptyList())
}

@Composable
internal fun SensorTraceLog(sensor: SensorInfo) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val identifiers = remember(sensor.serial, sensor.deviceAddress) {
        buildSet {
            add(sensor.serial)
            sensor.deviceAddress.takeUnless { it.equals("Unknown", ignoreCase = true) }?.let(::add)
            runCatching { SensorIdentity.resolveNativeHistorySensorNames(sensor.serial) }
                .getOrDefault(emptyList())
                .forEach(::add)
        }
    }
    val driverTags = remember(sensor.vendor) { sensorTraceDriverTags(sensor.vendor) }
    var lines by remember(sensor.serial) { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(context, identifiers, driverTags) {
        val file = File(context.filesDir, "logs/trace.log")
        while (isActive) {
            lines = withContext(Dispatchers.IO) {
                readRecentSensorTraceLines(file, identifiers, driverTags)
            }
            delay(2_000L)
        }
    }

    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val rendered = remember(lines) {
        lines.joinToString("\n") { line ->
            formatSensorTraceLine(line) { millis -> timeFormat.format(Date(millis)) }
        }
    }

    if (lines.isEmpty()) {
        // Nothing to act on, so no container: a caption says it without pretending to be a component.
        Text(
            text = stringResource(R.string.no_data_available),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
        return
    }

    val verticalScroll = rememberScrollState()
    val scrollBoundary = remember {
        object : NestedScrollConnection {
            // Keep leftover drag distance and fling velocity inside the log at either edge.
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset = Offset(0f, available.y)

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                Velocity(0f, available.y)
        }
    }
    val scrollbarColor = MaterialTheme.colorScheme.onSurfaceVariant
    // Follow the tail, the way a person watching a live log expects.
    LaunchedEffect(rendered) { verticalScroll.animateScrollTo(verticalScroll.maxValue) }

    Column(modifier = Modifier.fillMaxWidth()) {
        SelectionContainer {
            Text(
                text = rendered,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .drawWithContent {
                        drawContent()
                        val maxScroll = verticalScroll.maxValue
                        if (maxScroll > 0 && maxScroll != Int.MAX_VALUE) {
                            val trackHeight = size.height
                            val thumbHeight = (trackHeight * trackHeight / (trackHeight + maxScroll))
                                .coerceIn(24.dp.toPx().coerceAtMost(trackHeight), trackHeight)
                            val thumbTop = (trackHeight - thumbHeight) * verticalScroll.value / maxScroll
                            val width = 4.dp.toPx()
                            val left = if (layoutDirection == LayoutDirection.Rtl) 0f else size.width - width
                            val radius = CornerRadius(width / 2)
                            drawRoundRect(
                                color = scrollbarColor.copy(alpha = 0.12f),
                                topLeft = Offset(left, 0f),
                                size = Size(width, trackHeight),
                                cornerRadius = radius,
                            )
                            drawRoundRect(
                                color = scrollbarColor.copy(alpha = 0.65f),
                                topLeft = Offset(left, thumbTop),
                                size = Size(width, thumbHeight),
                                cornerRadius = radius,
                            )
                        }
                    }
                    .nestedScroll(scrollBoundary)
                    .verticalScroll(verticalScroll)
                    .padding(end = 12.dp),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    letterSpacing = 0.sp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                softWrap = true,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { clipboard.setText(AnnotatedString(rendered)) }) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
                Spacer8()
                Text(stringResource(R.string.copy))
            }
            TextButton(
                onClick = {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, rendered)
                    }
                    runCatching {
                        context.startActivity(
                            Intent.createChooser(share, context.getString(R.string.sensor_connection_log))
                        )
                    }
                },
            ) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer8()
                Text(stringResource(R.string.share))
            }
        }
    }
}

@Composable
private fun Spacer8() {
    androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
}
