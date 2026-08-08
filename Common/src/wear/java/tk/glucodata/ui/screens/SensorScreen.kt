package tk.glucodata.ui.screens

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tk.glucodata.ManagedCurrentSensor
import tk.glucodata.Natives
import tk.glucodata.R
import tk.glucodata.SensorBluetooth
import tk.glucodata.UiRefreshBus
import tk.glucodata.ui.WearNavigationRow
import tk.glucodata.ui.WearSectionTitle

private const val SENSOR_TICK_MS = 60_000L

private data class SensorRow(val serial: String, val isCurrent: Boolean, val isConnected: Boolean)

private fun loadSensors(): List<SensorRow> = runCatching {
    val current = ManagedCurrentSensor.get() ?: Natives.lastsensorname()
    val active = Natives.activeSensors()?.toList().orEmpty()
    val connected = SensorBluetooth.mygatts()?.mapNotNull { it.SerialNumber }.orEmpty()
    // One physical sensor can appear under several ids at once (native alias
    // plus the managed record synced by a handoff), which listed it twice.
    // Collapse by canonical identity, keeping the connected/managed form.
    val visible = active + connected
    val managed = runCatching {
        tk.glucodata.drivers.ManagedSensorIdentityRegistry
            .persistedSensorIds(tk.glucodata.Applic.app)
    }.getOrDefault(emptyList())
        .filter { managedId ->
            visible.any { candidate -> tk.glucodata.SensorIdentity.matches(managedId, candidate) }
        }
    val ordered = managed + connected + active
    val kept = tk.glucodata.SensorIdentity.distinctLogicalSensorIds(ordered)
    kept.map { id ->
        SensorRow(
            serial = id,
            isCurrent = tk.glucodata.SensorIdentity.matches(id, current),
            isConnected = connected.any { tk.glucodata.SensorIdentity.matches(it, id) },
        )
    }
}.getOrDefault(emptyList())

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun SensorScreen(onCalibrate: () -> Unit, onOpenSettings: (() -> Unit)? = null) {
    var forgetTarget by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }
    val storeSnapshot by tk.glucodata.ui.WearGlucoseStore.snapshot.collectAsState()
    val displayedSensor = storeSnapshot.sensorId
    var revision by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val sensors = remember(revision) { loadSensors() }
    val canCalibrate = remember(revision) {
        findCalibratableDriver() != null ||
            (tk.glucodata.MessageSender.isWearTransportAvailable() &&
                tk.glucodata.MessageSender.getMessageSender() != null)
    }
    val context = LocalContext.current
    val dateFormat = remember(context) { DateFormat.getMediumDateFormat(context) }
    val currentSensor = sensors.firstOrNull { it.isCurrent }?.serial
    val viewMode = remember(currentSensor, revision) {
        tk.glucodata.CurrentDisplaySource.resolveViewModeForSensor(currentSensor)
    }

    LaunchedEffect(Unit) {
        launch { UiRefreshBus.revision.collect { revision = it; now = System.currentTimeMillis() } }
        launch { tk.glucodata.WearSensorClaim.revision.collect { revision += 1L } }
        while (true) { delay(SENSOR_TICK_MS); now = System.currentTimeMillis() }
    }

    ScreenScaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 28.dp),
        ) {
            item { WearSectionTitle(stringResource(R.string.sensor)) }
            if (sensors.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.no_sensors_found),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.wear_libre_nfc_caveat),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(sensors, key = { it.serial }) { row ->
                val details = remember(row, revision, now / SENSOR_TICK_MS) {
                    loadWearSensorPresentation(row.serial, now)
                }
                // Tapping a sensor makes the rest of the app follow it; tapping the
                // one already shown hands the choice back to "whichever is
                // reporting". With two sensors there was previously no way to say.
                val displayed = displayedSensor != null &&
                    tk.glucodata.SensorIdentity.matches(displayedSensor, row.serial)
                Column(
                    Modifier.fillMaxWidth()
                        // Clip before the background and the click, so the ripple
                        // follows the card's corners instead of a square.
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            if (displayed) MaterialTheme.colorScheme.surfaceContainerHigh
                            else MaterialTheme.colorScheme.surfaceContainer,
                        )
                        .combinedClickable(
                            onClick = {
                                tk.glucodata.ui.WearSensorSelection.pin(
                                    if (tk.glucodata.ui.WearSensorSelection.pinned() != null && displayed) null
                                    else row.serial,
                                )
                            },
                            // Long press offers to drop a sensor this watch holds
                            // on its own, which the phone cannot remove for it.
                            onLongClick = { forgetTarget = row.serial },
                        )
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                ) {
                    Text(
                        text = details.serial,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (displayed || row.isCurrent) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    if (forgetTarget == row.serial) {
                        androidx.wear.compose.material3.Button(
                            onClick = {
                                forgetTarget = null
                                tk.glucodata.ui.WearSensorSelection.pin(null)
                                tk.glucodata.WearSync2.forgetSensorLocally(row.serial)
                            },
                            label = { Text(stringResource(R.string.wear_sensor_forget)) },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                    val connection = details.connectionStatus.ifEmpty {
                        if (row.isConnected) stringResource(R.string.status_connected) else ""
                    }
                    if (connection.isNotEmpty()) {
                        SensorDetailRow(stringResource(R.string.connection_label), connection)
                    }
                    details.detailedStatus
                        .takeIf { it.isNotEmpty() && !it.equals(connection, ignoreCase = true) }
                        ?.let { SensorDetailRow(stringResource(R.string.status), it) }
                    details.dayValueText.takeIf { it.isNotEmpty() }?.let {
                        SensorDetailRow(
                            stringResource(R.string.wear_sensor_day_label),
                            it,
                        )
                    }
                    details.lifecycleProgress?.let { progress ->
                        val progressColor = when {
                            progress >= 0.95f -> MaterialTheme.colorScheme.error
                            progress >= 0.80f -> MaterialTheme.colorScheme.tertiary
                            else -> androidx.compose.ui.graphics.Color(0xFF66BB6A)
                        }
                        Box(
                            Modifier.fillMaxWidth()
                                .padding(top = 8.dp)
                                .height(5.dp)
                                .background(
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                                    RoundedCornerShape(3.dp),
                                ),
                        ) {
                            Box(
                                Modifier.fillMaxWidth(progress.coerceIn(0f, 1f))
                                    .fillMaxHeight()
                                    .background(progressColor, RoundedCornerShape(3.dp)),
                            )
                        }
                    }
                    details.startTimeMs.takeIf { it > 0L }?.let {
                        SensorDetailRow(stringResource(R.string.sensor_started), dateFormat.format(Date(it)))
                    }
                    details.lastReadingMs.takeIf { it > 0L }?.let {
                        val age = DateUtils.getRelativeTimeSpanString(
                            it,
                            now,
                            DateUtils.MINUTE_IN_MILLIS,
                        ).toString()
                        SensorDetailRow(stringResource(R.string.readings), age)
                    }
                }
            }
            if (currentSensor != null) {
                item {
                    val modeLabel = stringResource(
                        when (viewMode) {
                            1 -> R.string.raw
                            2 -> R.string.auto_raw
                            3 -> R.string.raw_auto
                            else -> R.string.auto
                        },
                    )
                    WearNavigationRow(
                        "${stringResource(R.string.display)}: $modeLabel",
                        onClick = {
                            val nextMode = (viewMode + 1) % 4
                            if (tk.glucodata.CurrentDisplaySource.setViewModeForSensor(currentSensor, nextMode)) {
                                UiRefreshBus.requestDataRefresh()
                            }
                        },
                    )
                }
            }
            if (canCalibrate) {
                item { WearNavigationRow(stringResource(R.string.calibrate_action), onClick = onCalibrate) }
            }
            onOpenSettings?.let { open ->
                item { WearNavigationRow(stringResource(R.string.settings), onClick = open) }
            }
            item {
                val claim = tk.glucodata.WearSensorClaim.currentState()
                Text(
                    text = stringResource(
                        when (claim) {
                            tk.glucodata.WearSensorClaimState.CONNECTED -> R.string.wear_claim_watch_owns
                            tk.glucodata.WearSensorClaimState.REQUESTING -> R.string.wear_claim_waiting
                            tk.glucodata.WearSensorClaimState.PHONE_OWNS -> R.string.wear_claim_state_phone_owns
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
            if (tk.glucodata.WearSensorClaim.currentState() != tk.glucodata.WearSensorClaimState.PHONE_OWNS) {
                item {
                    WearNavigationRow(
                        stringResource(R.string.wear_return_sensor_to_phone),
                        onClick = {
                            tk.glucodata.WearSensorClaim.setDirectRequested(false)
                            // setDirectRequested publishes both the claim state and netinfo.
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SensorDetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(
            "$label:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.58f),
        )
    }
}
