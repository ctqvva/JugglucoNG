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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.unit.Dp
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
    /** Whether the driver holds its link right now; null where that is not this phone's to say. */
    val linkUp: Boolean? = null,
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
            !SensorReachPolicy.holdsOwnLink(snapshot.uiFamily, snapshot.readsOverBluetooth) ||
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
            linkUp = linkUp,
        )
    }
}

/** Where the out-of-range chip sits: at the end of a sensor card's status line, or alone on the dashboard. */
internal enum class SensorReachPromptHost { SENSOR_CARD, DASHBOARD }

/**
 * The Bluetooth chip at the end of the status line, grown leftward over the status text once
 * no reading or link has come from the sensor for a while. It does not guess why (too far,
 * dead, failed): it says there is no signal and that the app keeps trying. The dashboard
 * shows the same chip alone.
 */
@Composable
internal fun SensorOutOfReachChip(
    state: SensorReachState,
    host: SensorReachPromptHost,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val content = MaterialTheme.colorScheme.onTertiaryContainer
    val dashboard = host == SensorReachPromptHost.DASHBOARD
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = content,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = if (dashboard) 12.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // On the card the sensor is the card's own, so the title need not name it.
                Text(
                    text = stringResource(
                        if (dashboard) R.string.sensor_out_of_reach_title else R.string.sensor_out_of_reach_title_short,
                    ),
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.lowBatteryPercent?.let {
                        stringResource(R.string.sensor_out_of_reach_battery, it)
                    } ?: stringResource(R.string.sensor_out_of_reach_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = content.copy(alpha = 0.86f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            // The link mark stays where it always sits, at the line's end, still looking.
            SearchingSignalGlyph(color = content, iconSize = 16.dp)
        }
    }
}

/**
 * In place of the reading chip beside [SensorOutOfReachChip], grown to its height: only how
 * long it has been. The old value would read as current, so it is not shown.
 */
@Composable
internal fun SensorSilenceAgeChip(
    lastReadingMs: Long,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SensorReadingAgeLabel(
                readingMillis = lastReadingMs,
                iconTint = accentColor.copy(alpha = 0.82f),
                textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                textStyle = MaterialTheme.typography.titleMediumEmphasized.copy(fontFeatureSettings = "tnum"),
                iconSize = 16.dp,
            )
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
internal fun SearchingSignalGlyph(color: Color, iconSize: Dp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Symbols.BluetoothFilled,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(iconSize),
        )
        SearchingSignalWaves(color = color)
    }
}

/** Just the waves of [SearchingSignalGlyph], for a mark that draws its own sign. */
@Composable
internal fun SearchingSignalWaves(color: Color) {
    val transition = rememberInfiniteTransition(label = "sensorSearching")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 2_400, easing = LinearEasing), RepeatMode.Restart),
        label = "sensorSearchingSweep",
    )
    val unlit = color.copy(alpha = 0.24f)
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
