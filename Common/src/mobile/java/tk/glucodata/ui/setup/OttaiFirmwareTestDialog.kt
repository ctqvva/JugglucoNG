@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package tk.glucodata.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import tk.glucodata.R
import tk.glucodata.drivers.ottai.OttaiFirmwareSession
import tk.glucodata.drivers.ottai.OttaiFirmwareMode
import tk.glucodata.drivers.ottai.OttaiFirmwareObservation
import tk.glucodata.drivers.ottai.OttaiFirmwareStage
import tk.glucodata.drivers.ottai.OttaiFirmwareState

@Composable
internal fun OttaiFirmwareTestDialog(sensorId: String, address: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember(sensorId, address) { mutableStateOf<OttaiFirmwareMode?>(null) }
    var loading by remember(sensorId, address) { mutableStateOf(true) }
    LaunchedEffect(sensorId, address) {
        mode = withContext(Dispatchers.IO) { OttaiFirmwareSession.pendingMode(context, sensorId, address) }
        loading = false
    }
    if (loading) return
    val selected = mode
    if (selected != null) {
        OttaiFirmwareSessionDialog(sensorId, address, selected, onDismiss)
    } else {
        OttaiFirmwareModeDialog(OttaiFirmwareSession.hasPendingTransfer(context, sensorId),
            onMode = { mode = it },
            onDiscard = { done -> scope.launch {
                done(withContext(Dispatchers.IO) { runCatching { OttaiFirmwareSession.discardPendingTransfer(context, sensorId) } })
            } }, onDismiss = onDismiss)
    }
}

@Composable
internal fun OttaiFirmwareModeDialog(pending: Boolean, onMode: (OttaiFirmwareMode) -> Unit,
    onDiscard: ((Result<Unit>) -> Unit) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ottai_ota_title)) },
        text = { Text(stringResource(if (pending) R.string.ottai_ota_failed else R.string.ottai_ota_mode_hint)) },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!pending) {
                    Button(onClick = { onMode(OttaiFirmwareMode.PREACTIVATION) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(R.string.ottai_ota_fresh_mode))
                    }
                    OutlinedButton(onClick = { onMode(OttaiFirmwareMode.STARTED_SENSOR) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(R.string.ottai_ota_started_mode))
                    }
                }
                FirmwareDiscardAction(pending, true, onDiscard, onDismiss)
                TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun OttaiFirmwareSessionDialog(sensorId: String, address: String, mode: OttaiFirmwareMode, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    var state by remember(sensorId, address, mode) { mutableStateOf(OttaiFirmwareState(mode = mode)) }
    var session by remember(sensorId, address) { mutableStateOf<OttaiFirmwareSession?>(null) }
    DisposableEffect(sensorId, address, mode) {
        var disposed = false
        val keepScreenOn = view.keepScreenOn
        view.keepScreenOn = true
        val owned = OttaiFirmwareSession.start(context, sensorId, address, mode) { if (!disposed) state = it }
        session = owned
        if (owned == null) state = state.copy(stage = OttaiFirmwareStage.FAILED, errorCode = "session_unavailable")
        onDispose {
            disposed = true
            owned?.dispose()
            view.keepScreenOn = keepScreenOn
        }
    }
    val scope = rememberCoroutineScope()
    OttaiFirmwareStatusDialog(state, OttaiFirmwareSession.hasPendingTransfer(context, sensorId),
        onInstall = { session?.install() }, onPrepare = { session?.prepareOnly() },
        onCloudRecheck = { session?.recheckWithCloudAuth() },
        onDiscard = { done ->
            val owned = session
            if (owned != null) owned.discardRecovery(done)
            else scope.launch {
                done(withContext(Dispatchers.IO) { runCatching { OttaiFirmwareSession.discardPendingTransfer(context, sensorId) } })
            }
        }, onDismiss = onDismiss)
}

@Composable
internal fun OttaiFirmwareStatusDialog(state: OttaiFirmwareState, pending: Boolean,
    onInstall: () -> Unit, onPrepare: () -> Unit,
    onDiscard: ((Result<Unit>) -> Unit) -> Unit, onDismiss: () -> Unit, onCloudRecheck: () -> Unit = {}) {
    val mode = state.mode
    var confirmInstall by remember { mutableStateOf(false) }
    var confirmParameters by remember { mutableStateOf(false) }
    val stageText = when (state.stage) {
        OttaiFirmwareStage.CONNECTING -> R.string.ottai_ota_connecting
        OttaiFirmwareStage.AUTHENTICATING -> R.string.ottai_ota_authenticating
        OttaiFirmwareStage.CLOUD_AUTHENTICATING -> R.string.ottai_ota_cloud_authenticating
        OttaiFirmwareStage.CHECKING -> R.string.ottai_ota_checking
        OttaiFirmwareStage.READY -> when {
            state.recovery -> R.string.ottai_ota_recovery
            mode == OttaiFirmwareMode.STARTED_SENSOR -> R.string.ottai_ota_started_ready
            else -> R.string.ottai_ota_ready
        }
        OttaiFirmwareStage.NO_UPDATE -> if (state.canPrepareParameters && state.errorCode.isNotEmpty())
            R.string.ottai_ota_parameters else R.string.ottai_ota_no_update
        OttaiFirmwareStage.INSTALLING -> R.string.ottai_ota_installing
        OttaiFirmwareStage.RECONNECTING -> R.string.ottai_ota_reconnecting
        OttaiFirmwareStage.PREPARATION_READY -> R.string.ottai_ota_finish_preparation
        OttaiFirmwareStage.PREPARING -> R.string.ottai_ota_preparing
        OttaiFirmwareStage.COMPLETE -> when {
            mode == OttaiFirmwareMode.PREACTIVATION -> R.string.ottai_ota_complete
            state.after?.commandStatus == 3 -> R.string.ottai_ota_result_active
            state.after?.commandStatus == 4 -> R.string.ottai_ota_result_ended
            else -> R.string.ottai_ota_result_reset
        }
        OttaiFirmwareStage.FAILED -> R.string.ottai_ota_failed
        OttaiFirmwareStage.CLOSED -> R.string.cancel
    }
    AlertDialog(
        onDismissRequest = { if (!state.transferring) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !state.transferring, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.ottai_ota_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.ottai_ota_warning))
                Text(stringResource(if (mode == OttaiFirmwareMode.STARTED_SENSOR) R.string.ottai_ota_started_mode else R.string.ottai_ota_fresh_mode))
                if (mode == OttaiFirmwareMode.STARTED_SENSOR) Text(stringResource(R.string.ottai_ota_mode_hint))
                Text(stringResource(stageText))
                if (state.canRecheckWithCloudAuth && !pending) Text(stringResource(R.string.ottai_ota_cloud_recheck_hint))
                state.before?.let { ObservationText(R.string.ottai_ota_before, it) }
                state.after?.let { ObservationText(R.string.ottai_ota_after, it) }
                if (state.firmware.isNotEmpty()) Text(stringResource(R.string.ottai_ota_version, state.firmware, state.target.ifEmpty { "—" }))
                if (state.totalBytes > 0) {
                    Text(stringResource(R.string.ottai_ota_package, state.totalBytes, state.sha256))
                    LinearWavyProgressIndicator(
                        progress = { state.acknowledgedBytes.toFloat() / state.totalBytes },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (state.errorCode.isNotEmpty()) Text(stringResource(R.string.ottai_ota_error_code, state.errorCode))
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.stage == OttaiFirmwareStage.READY) {
                    Button(onClick = { confirmInstall = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.ottai_ota_install)) }
                }
                if (state.canRecheckWithCloudAuth && !pending) {
                    OutlinedButton(onClick = onCloudRecheck, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.ottai_ota_cloud_recheck)) }
                }
                if (state.canPrepareParameters &&
                    state.stage in setOf(OttaiFirmwareStage.READY, OttaiFirmwareStage.NO_UPDATE, OttaiFirmwareStage.PREPARATION_READY)) {
                    OutlinedButton(onClick = { confirmParameters = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.ottai_ota_parameters)) }
                }
                FirmwareDiscardAction(pending, !state.transferring, onDiscard, onDismiss)
                TextButton(onClick = onDismiss, enabled = !state.transferring,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.close))
                }
            }
        },
    )
    if (confirmInstall || confirmParameters) {
        AlertDialog(
            onDismissRequest = { confirmInstall = false; confirmParameters = false },
            title = { Text(stringResource(if (confirmInstall) R.string.ottai_ota_install else R.string.ottai_ota_parameters)) },
            text = { Text(stringResource(when {
                !confirmInstall -> R.string.ottai_ota_parameters_confirm
                mode == OttaiFirmwareMode.STARTED_SENSOR -> R.string.ottai_ota_started_confirm
                else -> R.string.ottai_ota_confirm
            })) },
            confirmButton = {
                TextButton(onClick = {
                    if (confirmInstall) onInstall() else onPrepare()
                    confirmInstall = false
                    confirmParameters = false
                }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmInstall = false; confirmParameters = false }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ObservationText(label: Int, observation: OttaiFirmwareObservation) {
    val numbers = java.text.NumberFormat.getIntegerInstance()
    fun number(value: Long?): String = value?.let(numbers::format) ?: "—"
    Text(stringResource(R.string.ottai_ota_observation, stringResource(label), observation.commandStatus,
        number(observation.maxActiveSeconds), number(observation.latestDataNo?.toLong()), number(observation.rtcSeconds)))
}

@Composable
private fun FirmwareDiscardAction(pending: Boolean, enabled: Boolean,
    onDiscard: ((Result<Unit>) -> Unit) -> Unit, onDiscarded: () -> Unit) {
    if (!pending) return
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    TextButton(onClick = { confirm = true }, enabled = enabled && !busy,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shapes = ButtonDefaults.shapes()) {
        Text(stringResource(R.string.ottai_ota_discard))
    }
    if (failed) Text(stringResource(R.string.ottai_ota_discard_failed))
    if (confirm) AlertDialog(
        onDismissRequest = { if (!busy) confirm = false },
        title = { Text(stringResource(R.string.ottai_ota_discard)) },
        text = { Text(stringResource(R.string.ottai_ota_discard_confirm)) },
        confirmButton = {
            TextButton(onClick = {
                busy = true
                onDiscard { result ->
                    busy = false
                    confirm = false
                    failed = result.isFailure
                    if (result.isSuccess) onDiscarded()
                }
            }, enabled = !busy, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.ottai_ota_discard)) }
        },
        dismissButton = {
            TextButton(onClick = { confirm = false }, enabled = !busy, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) }
        },
    )
}
