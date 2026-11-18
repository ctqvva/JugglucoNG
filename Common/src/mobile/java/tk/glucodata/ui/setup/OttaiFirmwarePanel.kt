@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package tk.glucodata.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.R
import tk.glucodata.drivers.ottai.OttaiCloudClient
import tk.glucodata.drivers.ottai.OttaiFirmwareMode
import tk.glucodata.drivers.ottai.OttaiFirmwareSession
import tk.glucodata.drivers.ottai.OttaiFirmwareStage
import tk.glucodata.drivers.ottai.OttaiFirmwareState
import tk.glucodata.drivers.ottai.OttaiRegistry
import tk.glucodata.ui.components.AdaptiveButtonRow
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.ExpandableSettingsCard

@Composable
internal fun OttaiFirmwareDisclosure(expanded: Boolean, onExpandedChange: (Boolean) -> Unit,
    enabled: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    ExpandableSettingsCard(
        title = stringResource(R.string.ottai_ota_check),
        summary = "",
        icon = Icons.Default.SystemUpdate,
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        position = CardPosition.SINGLE,
        iconTint = MaterialTheme.colorScheme.tertiary,
        enabled = enabled,
        modifier = modifier,
        content = content,
    )
}

/** Keep BLE ownership above the lazy list so scrolling the disclosure offscreen is harmless. */
@Composable
internal fun rememberOttaiFirmwarePanel(sensorId: String, address: String, closeWhenIdle: Boolean,
    beforeSession: () -> Unit, onSessionStarted: (OttaiFirmwareSession?) -> Unit,
    onSessionFinished: (OttaiFirmwareSession) -> Unit, onSessionDisposed: (OttaiFirmwareSession) -> Unit,
    onSignIn: () -> Unit, onLeave: () -> Unit): @Composable () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember(sensorId, address) { mutableStateOf<OttaiFirmwareMode?>(null) }
    var recoveryMode by remember(sensorId, address) { mutableStateOf<OttaiFirmwareMode?>(null) }
    var loading by remember(sensorId, address) { mutableStateOf(true) }
    LaunchedEffect(sensorId, address) {
        recoveryMode = withContext(Dispatchers.IO) { OttaiFirmwareSession.pendingMode(context, sensorId, address) }
        loading = false
    }
    val selected = mode
    if (selected != null) {
        return rememberOttaiFirmwareSession(sensorId, address, selected, closeWhenIdle,
            beforeSession, onSessionStarted, onSessionFinished, onSessionDisposed, onSignIn, onLeave)
    }
    val managed = OttaiFirmwareSession.hasManagedOwner(context, sensorId)
    val hasKeys = OttaiRegistry.loadMaterials(context, sensorId).authKeys != null
    val pending = OttaiFirmwareSession.hasPendingSetup(context, sensorId)
    LaunchedEffect(closeWhenIdle) { if (closeWhenIdle) onLeave() }
    return {
        when {
            managed && !pending -> Text(stringResource(R.string.ottai_ota_disconnect_first), style = MaterialTheme.typography.bodyMedium)
            loading -> LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            else -> OttaiFirmwareModePicker(pending,
                recoveryMode = recoveryMode.takeUnless { managed },
                allowStarted = hasKeys,
                onMode = { mode = it },
                onDiscard = { done -> scope.launch {
                    done(withContext(Dispatchers.IO) { runCatching { OttaiFirmwareSession.discardPendingTransfer(context, sensorId) } })
                } },
                onDiscarded = onLeave)
        }
    }
}

@Composable
internal fun OttaiFirmwareModePicker(pending: Boolean, onMode: (OttaiFirmwareMode) -> Unit,
    onDiscard: ((Result<Unit>) -> Unit) -> Unit, onDiscarded: () -> Unit,
    recoveryMode: OttaiFirmwareMode? = null, allowStarted: Boolean = true, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (pending) {
            Text(stringResource(R.string.ottai_ota_pending), style = MaterialTheme.typography.bodyMedium)
            if (recoveryMode != null) {
                FilledTonalButton(onClick = { onMode(recoveryMode) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.ottai_retry))
                }
            }
            FirmwareDiscardAction(enabled = true, onDiscard, onDiscarded)
        } else {
            AdaptiveButtonRow {
                FilledTonalButton(onClick = { onMode(OttaiFirmwareMode.PREACTIVATION) },
                    modifier = Modifier.heightIn(min = 48.dp), shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.ottai_ota_fresh_mode))
                }
                if (allowStarted) FilledTonalButton(onClick = { onMode(OttaiFirmwareMode.STARTED_SENSOR) },
                    modifier = Modifier.heightIn(min = 48.dp), shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.ottai_ota_started_mode))
                }
            }
        }
    }
}

@Composable
private fun rememberOttaiFirmwareSession(sensorId: String, address: String, mode: OttaiFirmwareMode,
    closeWhenIdle: Boolean, beforeSession: () -> Unit, onSessionStarted: (OttaiFirmwareSession?) -> Unit,
    onSessionFinished: (OttaiFirmwareSession) -> Unit, onSessionDisposed: (OttaiFirmwareSession) -> Unit,
    onSignIn: () -> Unit, onLeave: () -> Unit): @Composable () -> Unit {
    val context = LocalContext.current
    val view = LocalView.current
    var state by remember(sensorId, address, mode) { mutableStateOf(OttaiFirmwareState(mode = mode)) }
    var session by remember(sensorId, address) { mutableStateOf<OttaiFirmwareSession?>(null) }
    var attempt by remember(sensorId, address, mode) { mutableStateOf(0) }
    DisposableEffect(sensorId, address, mode, attempt) {
        var disposed = false
        val keepScreenOn = view.keepScreenOn
        view.keepScreenOn = true
        state = OttaiFirmwareState(mode = mode)
        beforeSession()
        var owner: OttaiFirmwareSession? = null
        owner = OttaiFirmwareSession.start(context, sensorId, address, mode) { next ->
            if (!disposed) {
                state = next
                if (next.stage in setOf(OttaiFirmwareStage.COMPLETE, OttaiFirmwareStage.FAILED, OttaiFirmwareStage.CLOSED)) {
                    owner?.let(onSessionFinished)
                }
            }
        }
        val owned = owner
        session = owned
        onSessionStarted(owned)
        if (owned == null) {
            val reason = if (OttaiFirmwareSession.hasManagedOwner(context, sensorId)) "sensor_managed" else "session_unavailable"
            state = state.copy(stage = OttaiFirmwareStage.FAILED, errorCode = reason)
        }
        onDispose {
            disposed = true
            if (owned != null) {
                owned.dispose()
                onSessionDisposed(owned)
            }
            view.keepScreenOn = keepScreenOn
        }
    }
    val scope = rememberCoroutineScope()
    return rememberOttaiFirmwareContent(state, OttaiFirmwareSession.hasPendingSetup(context, sensorId),
        onInstall = { session?.install() },
        onPrepare = { session?.prepareOnly() },
        onCloudRecheck = { session?.recheckWithCloudAuth() },
        onDiscard = { done ->
            val owned = session
            if (owned != null) owned.discardRecovery(done)
            else scope.launch {
                done(withContext(Dispatchers.IO) { runCatching { OttaiFirmwareSession.discardPendingTransfer(context, sensorId) } })
            }
        },
        onLeave = onLeave, onSignIn = onSignIn, closeWhenIdle = closeWhenIdle,
        onRetry = { if (state.stage == OttaiFirmwareStage.CHECK_FAILED) session?.retryOffer() else attempt++ })
}

private enum class FirmwareWrite { INSTALL, PREPARE }

@Composable
internal fun rememberOttaiFirmwareContent(state: OttaiFirmwareState, pending: Boolean,
    onInstall: () -> Unit, onPrepare: () -> Unit,
    onDiscard: ((Result<Unit>) -> Unit) -> Unit, onLeave: () -> Unit, onCloudRecheck: () -> Unit = {},
    onRetry: (() -> Unit)? = null, onSignIn: (() -> Unit)? = null, closeWhenIdle: Boolean = false,
    modifier: Modifier = Modifier): @Composable () -> Unit {
    val mode = state.mode
    var write by remember { mutableStateOf<FirmwareWrite?>(null) }
    var writing by remember { mutableStateOf(false) }
    val sessionExpired = state.errorCode.contains(OttaiCloudClient.BIZ_TOKEN_INVALID, true)
    val managed = state.errorCode == "sensor_managed"
    val installReady = state.stage == OttaiFirmwareStage.READY
    val canConfirm = when (write) {
        FirmwareWrite.INSTALL -> installReady
        FirmwareWrite.PREPARE -> state.canPrepareNow
        null -> false
    }
    LaunchedEffect(state.stage) {
        if (writing) {
            if (state.stage in setOf(OttaiFirmwareStage.COMPLETE, OttaiFirmwareStage.PREPARATION_READY,
                    OttaiFirmwareStage.FAILED, OttaiFirmwareStage.CLOSED)) {
                writing = false
                write = null
            }
        } else if (!canConfirm) write = null
    }
    LaunchedEffect(closeWhenIdle, writing) { if (closeWhenIdle && !writing) onLeave() }

    val stageText = when (state.stage) {
        OttaiFirmwareStage.CONNECTING -> R.string.ottai_ota_connecting
        OttaiFirmwareStage.AUTHENTICATING, OttaiFirmwareStage.CLOUD_AUTHENTICATING -> R.string.ottai_ota_authenticating
        OttaiFirmwareStage.CHECKING -> R.string.ottai_ota_checking
        OttaiFirmwareStage.READY -> R.string.firmware
        OttaiFirmwareStage.NO_UPDATE -> R.string.ottai_ota_no_update
        OttaiFirmwareStage.CHECK_FAILED -> R.string.ottai_ota_check_failed
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
        OttaiFirmwareStage.FAILED -> when {
            sessionExpired -> R.string.ottai_session_invalid
            managed -> R.string.ottai_ota_disconnect_first
            state.errorCode.contains(OttaiCloudClient.BIZ_OTA_UPGRADE_REQUIRED, true) -> R.string.ottai_ota_upgrade_required
            pending -> R.string.ottai_ota_pending
            else -> R.string.ottai_ota_check_failed
        }
        OttaiFirmwareStage.CLOSED -> R.string.cancel
    }
    val selected = write
    if (selected != null && (writing || canConfirm)) {
        // Once confirmed, keep the same dialog up to protect sensor writes if the user navigates away.
        val install = selected == FirmwareWrite.INSTALL
        AlertDialog(
            onDismissRequest = { if (!writing) write = null },
            properties = DialogProperties(dismissOnBackPress = !writing, dismissOnClickOutside = !writing),
            title = { Text(stringResource(if (install) R.string.ottai_ota_install else R.string.ottai_ota_parameters)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (writing) {
                        Text(stringResource(if (state.stage == OttaiFirmwareStage.RECONNECTING)
                            R.string.ottai_ota_reconnecting else if (install) R.string.ottai_ota_installing else R.string.ottai_ota_preparing))
                        if (install && state.totalBytes > 0) LinearWavyProgressIndicator(
                            progress = { state.acknowledgedBytes.toFloat() / state.totalBytes },
                            modifier = Modifier.fillMaxWidth(),
                        ) else LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else Text(stringResource(when {
                        !install -> R.string.ottai_ota_parameters_confirm
                        mode == OttaiFirmwareMode.STARTED_SENSOR -> R.string.ottai_ota_started_confirm
                        else -> R.string.ottai_ota_confirm
                    }))
                }
            },
            confirmButton = {
                if (!writing) TextButton(onClick = {
                    writing = true
                    if (install) onInstall() else onPrepare()
                }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                if (!writing) TextButton(onClick = { write = null },
                    shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    return {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val status = if (installReady && state.target.isNotBlank())
                stringResource(R.string.ottai_ota_version, state.firmware, state.target)
            else stringResource(stageText)
            Text(status, style = MaterialTheme.typography.bodyMedium)
            if (state.stage in setOf(OttaiFirmwareStage.CONNECTING, OttaiFirmwareStage.AUTHENTICATING,
                    OttaiFirmwareStage.CLOUD_AUTHENTICATING, OttaiFirmwareStage.CHECKING)) {
                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (sessionExpired && onSignIn != null) {
                Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.ottai_login_button)) }
            } else if (!managed && state.stage in setOf(OttaiFirmwareStage.FAILED, OttaiFirmwareStage.CHECK_FAILED) && onRetry != null) {
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.ottai_retry)) }
            }
            if (installReady) {
                Button(onClick = { write = FirmwareWrite.INSTALL }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.ottai_ota_install)) }
            }
            if (state.canPrepareNow) {
                OutlinedButton(onClick = { write = FirmwareWrite.PREPARE }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.ottai_ota_parameters)) }
            }
            if (state.canRecheckWithCloudAuth && !pending) {
                OutlinedButton(onClick = onCloudRecheck, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.ottai_ota_cloud_recheck)) }
            }
            if (pending) FirmwareDiscardAction(!writing, onDiscard, onLeave)
        }
    }
}

@Composable
private fun FirmwareDiscardAction(enabled: Boolean,
    onDiscard: ((Result<Unit>) -> Unit) -> Unit, onDiscarded: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    TextButton(onClick = { confirm = true }, enabled = enabled && !busy,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shapes = ButtonDefaults.shapes()) {
        Text(stringResource(R.string.ottai_ota_discard))
    }
    if (failed) Text(stringResource(R.string.ottai_ota_discard_failed), color = MaterialTheme.colorScheme.error)
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
