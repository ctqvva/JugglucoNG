package tk.glucodata.ui

import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import tk.glucodata.R
import tk.glucodata.SensorHandoffUiState
import tk.glucodata.SensorOwnershipRuntime
import tk.glucodata.UiRefreshBus
import tk.glucodata.drivers.ManagedSensorRuntime
import tk.glucodata.drivers.SensorReach
import tk.glucodata.drivers.SensorReachPolicy
import tk.glucodata.ui.symbols.Symbols
import tk.glucodata.ui.util.SensorReadingAgeLabel
import tk.glucodata.ui.util.rememberSensorReadingAgeText

@Immutable
internal data class SensorReachState(
    val reach: SensorReach = SensorReach.FINE,
    /** The newest reading's time, 0 when there is none. */
    val lastReadingMs: Long = 0L,
    /** A battery low enough to be the reason, null when it is not or is unknown. */
    val lowBatteryPercent: Int? = null,
)

private fun bluetoothOn(context: Context): Boolean = runCatching {
    (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled
}.getOrNull() ?: true

/**
 * Whether a sensor this phone reads itself has gone out of reach or quiet. Looked at again
 * on every status refresh and every 15 s, since "for a while" passes without any event.
 */
@Composable
internal fun rememberSensorReach(serial: String, lastReadingMs: Long): SensorReachState {
    val context = LocalContext.current
    val revision by UiRefreshBus.revision.collectAsStateWithLifecycle(initialValue = 0L)
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(serial) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(15_000L)
        }
    }
    return remember(serial, lastReadingMs, revision, nowMs) {
        val now = System.currentTimeMillis()
        val snapshot = if (serial.isBlank()) null else ManagedSensorRuntime.resolveUiSnapshot(serial, serial)
        if (snapshot == null ||
            !SensorReachPolicy.holdsOwnLink(snapshot.uiFamily) ||
            !snapshot.isUiEnabled ||
            snapshot.isSensorExpired ||
            SensorOwnershipRuntime.handoffUiState(serial) != SensorHandoffUiState.NONE ||
            !bluetoothOn(context)
        ) {
            // Paused, handed over, ended or Bluetooth off: each says so itself.
            SensorReachPolicy.forgetLink(serial)
            return@remember SensorReachState(lastReadingMs = lastReadingMs)
        }
        val linkUp = snapshot.isVendorConnected
        val reach = SensorReachPolicy.resolve(
            nowMs = now,
            lastReadingMs = lastReadingMs,
            linkUp = linkUp,
            linkDownSinceMs = SensorReachPolicy.observeLink(snapshot.serial, linkUp, now),
            catchingUp = snapshot.catchingUp,
        )
        SensorReachState(
            reach = reach,
            lastReadingMs = lastReadingMs,
            lowBatteryPercent = snapshot.batteryPercent
                .takeIf { it in 0..SensorReachPolicy.LOW_BATTERY_PERCENT },
        )
    }
}

/** Where the prompt sits: inside a sensor card, or on its own on the dashboard. */
internal enum class SensorReachPromptHost { SENSOR_CARD, DASHBOARD }

/**
 * The status line of a sensor the phone cannot get to, turned into what to do about it.
 * The Bluetooth mark that sat at the line's end keeps looking for the sensor in the tile.
 */
@Composable
internal fun SensorOutOfReachPrompt(
    state: SensorReachState,
    host: SensorReachPromptHost,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val container = MaterialTheme.colorScheme.tertiaryContainer
    val content = MaterialTheme.colorScheme.onTertiaryContainer
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (host == SensorReachPromptHost.DASHBOARD) 20.dp else 16.dp),
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                .padding(if (host == SensorReachPromptHost.DASHBOARD) 16.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(content.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                SearchingSignalGlyph(color = content)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.sensor_out_of_reach_title),
                        style = MaterialTheme.typography.titleSmallEmphasized,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.lastReadingMs > 0L) {
                        SensorReadingAgeLabel(
                            readingMillis = state.lastReadingMs,
                            iconTint = content.copy(alpha = 0.72f),
                            textColor = content.copy(alpha = 0.72f),
                            // On the title's first line, not floating between its two.
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = state.lowBatteryPercent?.let {
                        stringResource(R.string.sensor_out_of_reach_battery, it)
                    } ?: stringResource(R.string.sensor_out_of_reach_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = content.copy(alpha = 0.86f),
                )
            }
        }
    }
}

/**
 * In place of the reading chip while the link is up but the sensor sends nothing: the old
 * value would read as current, and an error would overstate a sensor that is only quiet.
 * How long it has been is the chip; what is happening goes in the status beside it.
 */
@Composable
internal fun SensorQuietChip(lastReadingMs: Long, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 32.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Symbols.HourglassEmptyFilled,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = rememberSensorReadingAgeText(lastReadingMs),
                style = MaterialTheme.typography.titleSmallEmphasized.copy(fontFeatureSettings = "tnum"),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** The Bluetooth sign with its waves lighting one after another, as a radio that is looking. */
@Composable
private fun SearchingSignalGlyph(color: Color) {
    val transition = rememberInfiniteTransition(label = "sensorSearching")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 2_400, easing = LinearEasing), RepeatMode.Restart),
        label = "sensorSearchingSweep",
    )
    val unlit = color.copy(alpha = 0.24f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Symbols.BluetoothFilled,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp),
        )
        Canvas(modifier = Modifier.size(width = 12.dp, height = 20.dp)) {
            val rtl = layoutDirection == LayoutDirection.Rtl
            val stroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
            val centerX = if (rtl) size.width + 2.dp.toPx() else -2.dp.toPx()
            for (wave in 0 until 3) {
                // Each wave lights as the sweep reaches it and fades as it moves on.
                val lit = (1f - kotlin.math.abs(sweep - 1f - wave)).coerceIn(0f, 1f)
                val radius = (5 + wave * 4).dp.toPx()
                drawArc(
                    color = androidx.compose.ui.graphics.lerp(unlit, color, lit),
                    startAngle = if (rtl) 140f else -40f,
                    sweepAngle = 80f,
                    useCenter = false,
                    topLeft = Offset(centerX - radius, size.height / 2f - radius),
                    size = Size(radius * 2, radius * 2),
                    style = stroke,
                )
            }
        }
    }
}
