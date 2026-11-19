@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package tk.glucodata.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
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

@Composable
internal fun OttaiFirmwareAction(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp),
        shapes = ButtonDefaults.shapes()) {
        Text(stringResource(R.string.ottai_ota_check))
    }
}

@Composable
internal fun OttaiFirmwareDialog(sensorId: String, address: String, closeWhenIdle: Boolean,
    beforeSession: () -> Unit, onSessionStarted: (OttaiFirmwareSession?) -> Unit,
    onSessionFinished: (OttaiFirmwareSession) -> Unit, onSessionDisposed: (OttaiFirmwareSession) -> Unit,
    onSignIn: () -> Unit, onLeave: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var mode by remember(sensorId, address) { mutableStateOf<OttaiFirmwareMode?>(null) }
    var recoveryMode by remember(sensorId, address) { mutableStateOf<OttaiFirmwareMode?>(null) }
    var loading by remember(sensorId, address) { mutableStateOf(true) }
    var state by remember(sensorId, address) { mutableStateOf<OttaiFirmwareState?>(null) }
    var session by remember(sensorId, address) { mutableStateOf<OttaiFirmwareSession?>(null) }
    var attempt by remember(sensorId, address) { mutableStateOf(0) }
    LaunchedEffect(sensorId, address) {
        recoveryMode = withContext(Dispatchers.IO) { OttaiFirmwareSession.pendingMode(context, sensorId, address) }
        loading = false
    }
    val selected = mode
    if (selected != null) {
        DisposableEffect(sensorId, address, selected, attempt) {
            var disposed = false
            val keepScreenOn = view.keepScreenOn
            view.keepScreenOn = true
            state = OttaiFirmwareState(mode = selected)
            beforeSession()
            var owner: OttaiFirmwareSession? = null
            owner = OttaiFirmwareSession.start(context, sensorId, address, selected) { next ->
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
                state = OttaiFirmwareState(stage = OttaiFirmwareStage.FAILED, mode = selected, errorCode = reason)
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
    }
    val managed = if (selected == null) OttaiFirmwareSession.hasManagedOwner(context, sensorId)
        else state?.errorCode == "sensor_managed"
    val pending = OttaiFirmwareSession.hasPendingSetup(context, sensorId)
    OttaiFirmwareDialogContent(
        state = state ?: selected?.let { OttaiFirmwareState(mode = it) },
        pending = pending,
        managed = managed,
        loading = loading && (!managed || pending),
        recoveryMode = recoveryMode,
        allowStarted = selected == null && OttaiRegistry.loadMaterials(context, sensorId).authKeys != null,
        onMode = { mode = it },
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
        onLeave = onLeave,
        onSignIn = onSignIn,
        closeWhenIdle = closeWhenIdle,
        onRetry = { if (state?.stage == OttaiFirmwareStage.CHECK_FAILED) session?.retryOffer() else attempt++ },
    )
}

private enum class FirmwareAction(val label: Int) {
    INSTALL(R.string.ottai_ota_install),
    PREPARE(R.string.ottai_ota_parameters),
    DISCARD(R.string.ottai_ota_discard),
}

@Composable
internal fun OttaiFirmwareDialogContent(
    state: OttaiFirmwareState? = null,
    pending: Boolean = false,
    managed: Boolean = state?.errorCode == "sensor_managed",
    loading: Boolean = false,
    recoveryMode: OttaiFirmwareMode? = null,
    allowStarted: Boolean = true,
    onMode: (OttaiFirmwareMode) -> Unit = {},
    onInstall: () -> Unit = {},
    onPrepare: () -> Unit = {},
    onDiscard: ((Result<Unit>) -> Unit) -> Unit,
    onLeave: () -> Unit,
    onCloudRecheck: () -> Unit = {},
    onRetry: (() -> Unit)? = null,
    onSignIn: (() -> Unit)? = null,
    closeWhenIdle: Boolean = false,
) {
    var action by remember { mutableStateOf<FirmwareAction?>(null) }
    var working by remember { mutableStateOf(false) }
    var discardFailed by remember { mutableStateOf(false) }
    val stage = state?.stage
    val installReady = stage == OttaiFirmwareStage.READY
    val canPrepare = state?.canPrepareNow == true
    val expired = state?.errorCode?.contains(OttaiCloudClient.BIZ_TOKEN_INVALID, true) == true
    LaunchedEffect(stage) {
        if (action != FirmwareAction.DISCARD) {
            if (working && stage in setOf(OttaiFirmwareStage.COMPLETE, OttaiFirmwareStage.PREPARATION_READY,
                    OttaiFirmwareStage.FAILED, OttaiFirmwareStage.CLOSED)) {
                working = false
                action = null
            } else if (!working &&
                ((action == FirmwareAction.INSTALL && !installReady) || (action == FirmwareAction.PREPARE && !canPrepare))) {
                action = null
            }
        }
    }
    LaunchedEffect(closeWhenIdle, working) { if (closeWhenIdle && !working) onLeave() }

    val selected = action
    val checkRunning = loading || stage in setOf(OttaiFirmwareStage.CONNECTING, OttaiFirmwareStage.AUTHENTICATING,
        OttaiFirmwareStage.CLOUD_AUTHENTICATING, OttaiFirmwareStage.CHECKING)
    val status = firmwareStatus(state, pending, managed, expired)
    val canChooseMode = state == null && !loading && !pending && !managed
    val retry = if (state == null && pending && !managed && !loading && recoveryMode != null) {
        { onMode(recoveryMode) }
    } else if (!managed && stage in setOf(OttaiFirmwareStage.FAILED, OttaiFirmwareStage.CHECK_FAILED)) onRetry else null

    val primary = when {
        expired && onSignIn != null -> R.string.ottai_login_button to onSignIn
        installReady -> R.string.ottai_ota_install to { action = FirmwareAction.INSTALL }
        retry != null -> R.string.ottai_retry to retry
        canPrepare -> R.string.ottai_ota_parameters to { action = FirmwareAction.PREPARE }
        else -> null
    }
    fun dismiss() {
        if (working) return
        if (action == null) onLeave() else action = null
    }

    AlertDialog(
        onDismissRequest = ::dismiss,
        properties = DialogProperties(dismissOnBackPress = !working, dismissOnClickOutside = !working),
        title = { Text(stringResource(selected?.label ?: R.string.ottai_ota_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    working && selected != null -> {
                        if (selected != FirmwareAction.DISCARD) {
                            Text(stringResource(when {
                                stage == OttaiFirmwareStage.RECONNECTING -> R.string.ottai_ota_reconnecting
                                selected == FirmwareAction.INSTALL -> R.string.ottai_ota_installing
                                else -> R.string.ottai_ota_preparing
                            }))
                        }
                        if (selected == FirmwareAction.INSTALL && state != null && state.totalBytes > 0) {
                            LinearWavyProgressIndicator(
                                progress = { state.acknowledgedBytes.toFloat() / state.totalBytes },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    selected != null -> Text(stringResource(when (selected) {
                        FirmwareAction.INSTALL -> if (state?.mode == OttaiFirmwareMode.STARTED_SENSOR)
                            R.string.ottai_ota_started_confirm else R.string.ottai_ota_confirm
                        FirmwareAction.PREPARE -> R.string.ottai_ota_parameters_confirm
                        FirmwareAction.DISCARD -> R.string.ottai_ota_discard_confirm
                    }))
                    else -> {
                        status?.let { Text(it) }
                        if (checkRunning) LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                        if (canChooseMode) {
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
                        if (canPrepare && primary?.first != R.string.ottai_ota_parameters)
                            TextButton(onClick = { action = FirmwareAction.PREPARE }, shapes = ButtonDefaults.shapes()) {
                                Text(stringResource(R.string.ottai_ota_parameters))
                            }
                        if (state?.canRecheckWithCloudAuth == true && !pending)
                            TextButton(onClick = onCloudRecheck, shapes = ButtonDefaults.shapes()) {
                                Text(stringResource(R.string.ottai_ota_cloud_recheck))
                            }
                        if (pending && !checkRunning)
                            TextButton(onClick = { action = FirmwareAction.DISCARD }, shapes = ButtonDefaults.shapes()) {
                                Text(stringResource(R.string.ottai_ota_discard))
                            }
                        if (discardFailed) Text(stringResource(R.string.ottai_ota_discard_failed), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        dismissButton = {
            if (!working && (selected != null || primary != null))
                TextButton(onClick = ::dismiss, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(if (selected != null) R.string.cancel else R.string.close))
                }
        },
        confirmButton = {
            if (!working) {
                val label = selected?.label ?: primary?.first ?: R.string.close
                TextButton(onClick = {
                    when (selected) {
                        FirmwareAction.INSTALL -> { working = true; onInstall() }
                        FirmwareAction.PREPARE -> { working = true; onPrepare() }
                        FirmwareAction.DISCARD -> {
                            working = true
                            onDiscard { result ->
                                working = false
                                action = null
                                discardFailed = result.isFailure
                                if (result.isSuccess) onLeave()
                            }
                        }
                        null -> if (primary == null) onLeave() else primary.second()
                    }
                }, shapes = ButtonDefaults.shapes()) { Text(stringResource(label)) }
            }
        },
    )
}

@Composable
private fun firmwareStatus(state: OttaiFirmwareState?, pending: Boolean, managed: Boolean, expired: Boolean): String? {
    val label = when (state?.stage) {
        null -> when {
            pending -> R.string.ottai_ota_pending
            managed -> R.string.ottai_ota_disconnect_first
            else -> return null
        }
        OttaiFirmwareStage.CONNECTING -> R.string.connecting
        OttaiFirmwareStage.AUTHENTICATING, OttaiFirmwareStage.CLOUD_AUTHENTICATING -> R.string.ottai_ota_authenticating
        OttaiFirmwareStage.CHECKING -> R.string.ottai_ota_checking
        OttaiFirmwareStage.READY -> if (state.target.isNotBlank())
            return stringResource(R.string.ottai_ota_version, state.firmware, state.target) else R.string.firmware
        OttaiFirmwareStage.NO_UPDATE -> R.string.ottai_ota_no_update
        OttaiFirmwareStage.CHECK_FAILED -> R.string.ottai_ota_check_failed
        OttaiFirmwareStage.INSTALLING -> R.string.ottai_ota_installing
        OttaiFirmwareStage.RECONNECTING -> R.string.ottai_ota_reconnecting
        OttaiFirmwareStage.PREPARATION_READY -> R.string.ottai_ota_finish_preparation
        OttaiFirmwareStage.PREPARING -> R.string.ottai_ota_preparing
        OttaiFirmwareStage.COMPLETE -> when {
            state.mode == OttaiFirmwareMode.PREACTIVATION -> R.string.ottai_ota_complete
            state.after?.commandStatus == 3 -> R.string.ottai_ota_result_active
            state.after?.commandStatus == 4 -> R.string.ottai_ota_result_ended
            else -> R.string.ottai_ota_result_reset
        }
        OttaiFirmwareStage.FAILED -> when {
            expired -> R.string.ottai_session_invalid
            managed -> R.string.ottai_ota_disconnect_first
            state.errorCode.contains(OttaiCloudClient.BIZ_OTA_UPGRADE_REQUIRED, true) -> R.string.ottai_ota_upgrade_required
            pending -> R.string.ottai_ota_pending
            else -> R.string.ottai_ota_check_failed
        }
        OttaiFirmwareStage.CLOSED -> return null
    }
    return stringResource(label)
}
