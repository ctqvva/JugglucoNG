package tk.glucodata.ui

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.CloneOutgoingPhase
import tk.glucodata.CloneOutgoingRecoveryProtocol
import tk.glucodata.CloneOutgoingState
import tk.glucodata.CloneRecoveryCategories
import tk.glucodata.CloneRecoveryManifest
import tk.glucodata.CloneRecoveryMode
import tk.glucodata.CloneRecoveryStaging
import tk.glucodata.Natives
import tk.glucodata.R
import tk.glucodata.data.CloneOutgoingRecoveryAccess
import tk.glucodata.ui.components.CompactSheetDragHandle
import tk.glucodata.ui.components.StableModalBottomSheet

private const val RECOVERY_STATUS_POLL_MILLIS = 750L

private data class CloneHistoryRecoveryUiStatus(
    val outgoing: CloneOutgoingState,
    val manifest: CloneRecoveryManifest?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CloneHistoryRecoverySheet(
    mirror: MirrorItemData,
    sheetState: SheetState,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val staging = remember { CloneRecoveryStaging(context.filesDir) }
    var status by remember(mirror.index) { mutableStateOf<CloneHistoryRecoveryUiStatus?>(null) }
    var loadError by remember(mirror.index) { mutableStateOf<String?>(null) }
    var actionError by remember(mirror.index) { mutableStateOf<String?>(null) }
    var actionRunning by remember(mirror.index) { mutableStateOf(false) }
    var selection by remember(mirror.index) {
        mutableStateOf(CloneHistoryRecoverySelection())
    }
    var showFullHistoryConfirmation by remember { mutableStateOf(false) }

    fun decode(raw: String): CloneHistoryRecoveryUiStatus {
        require(raw.isNotBlank()) { "Clone history recovery is unavailable" }
        val outgoing = CloneOutgoingRecoveryProtocol.decodeState(raw)
        val manifest = outgoing.jobId?.let { jobId ->
            runCatching { staging.readManifest(jobId) }.getOrNull()
        }
        return CloneHistoryRecoveryUiStatus(outgoing, manifest)
    }

    suspend fun readStatus(probeWhenMissing: Boolean): CloneHistoryRecoveryUiStatus =
        withContext(Dispatchers.IO) {
            var raw = Natives.cloneRecoveryStatus(mirror.index).orEmpty()
            if (raw.isBlank() && probeWhenMissing) {
                raw = Natives.probeCloneRecovery(mirror.index).orEmpty()
            }
            decode(raw)
        }

    fun runAction(action: suspend () -> CloneHistoryRecoveryUiStatus) {
        if (actionRunning) return
        scope.launch {
            actionRunning = true
            try {
                status = action()
                loadError = null
                actionError = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                actionError = error.message
            } finally {
                actionRunning = false
            }
        }
    }

    fun startSelectedRecovery() {
        val journalSupported = status?.outgoing?.remoteCategories?.let {
            it and CloneRecoveryCategories.JOURNAL != 0
        } == true
        runAction {
            withContext(Dispatchers.IO) {
                decode(
                    Natives.startCloneRecovery(
                        mirror.index,
                        selection.mode.wireValue,
                        selection.includeJournal && journalSupported,
                    ).orEmpty()
                )
            }
        }
    }

    LaunchedEffect(mirror.index) {
        var firstRead = true
        while (isActive) {
            try {
                status = readStatus(probeWhenMissing = firstRead)
                loadError = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (status == null) loadError = error.message
            }
            firstRead = false
            delay(RECOVERY_STATUS_POLL_MILLIS)
        }
    }

    if (showFullHistoryConfirmation) {
        AlertDialog(
            onDismissRequest = { showFullHistoryConfirmation = false },
            icon = { Icon(Icons.Filled.History, contentDescription = null) },
            title = { Text(stringResource(R.string.clone_history_full_confirm_title)) },
            text = { Text(stringResource(R.string.clone_history_full_confirm_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        showFullHistoryConfirmation = false
                        startSelectedRecovery()
                    }
                ) { Text(stringResource(R.string.clone_history_replace_and_send)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { showFullHistoryConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    StableModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { CompactSheetDragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.clone_history_send),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = mirror.label?.takeIf(String::isNotBlank)
                    ?: stringResource(R.string.connection_number, mirror.index),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.clone_history_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))

            val outgoing = status?.outgoing
            when {
                outgoing == null -> CloneHistoryLoadingState(loadError)
                outgoing.phase == CloneOutgoingPhase.PROBE_READY -> {
                    val journalSupported = outgoing.remoteCategories?.let {
                        it and CloneRecoveryCategories.JOURNAL != 0
                    } == true
                    CloneHistoryRecoveryOptions(
                        selection = selection,
                        journalSupported = journalSupported,
                        enabled = !actionRunning,
                        onSelectionChange = { selection = it },
                    )
                    if (!actionError.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = requireNotNull(actionError),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = {
                            if (selection.mode == CloneRecoveryMode.FULL_HISTORY) {
                                showFullHistoryConfirmation = true
                            } else {
                                startSelectedRecovery()
                            }
                        },
                        enabled = !actionRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (actionRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 8.dp).size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        Text(stringResource(R.string.clone_history_send_action))
                    }
                }
                else -> CloneHistoryRecoveryProgress(
                    status = requireNotNull(status),
                    actionRunning = actionRunning,
                    error = actionError ?: loadError,
                    onCancel = {
                        runAction {
                            withContext(Dispatchers.IO) {
                                decode(Natives.cancelCloneRecovery(mirror.index).orEmpty())
                            }
                        }
                    },
                    onStartAnother = {
                        runAction {
                            withContext(Dispatchers.IO) {
                                val label = requireNotNull(mirror.iceLabel)
                                require(CloneOutgoingRecoveryAccess.clearOutgoing(label)) {
                                    "Could not clear the previous Clone history transfer"
                                }
                                decode(Natives.probeCloneRecovery(mirror.index).orEmpty())
                            }
                        }
                    },
                    onDone = onDismiss,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CloneHistoryLoadingState(error: String?) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator()
        Text(
            text = if (error == null) {
                stringResource(R.string.clone_history_checking_receiver)
            } else {
                stringResource(R.string.clone_history_unavailable)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (error == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}

@Composable
private fun CloneHistoryRecoveryOptions(
    selection: CloneHistoryRecoverySelection,
    journalSupported: Boolean,
    enabled: Boolean,
    onSelectionChange: (CloneHistoryRecoverySelection) -> Unit,
) {
    Text(
        text = stringResource(R.string.clone_history_mode),
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(Modifier.height(8.dp))
    CloneHistoryModeOption(
        selected = selection.mode == CloneRecoveryMode.ONLY_MISSING,
        title = stringResource(R.string.clone_history_only_missing),
        subtitle = stringResource(R.string.clone_history_only_missing_desc),
        enabled = enabled,
        onClick = { onSelectionChange(selection.copy(mode = CloneRecoveryMode.ONLY_MISSING)) },
    )
    Spacer(Modifier.height(8.dp))
    CloneHistoryModeOption(
        selected = selection.mode == CloneRecoveryMode.FULL_HISTORY,
        title = stringResource(R.string.clone_history_full),
        subtitle = stringResource(R.string.clone_history_full_desc),
        enabled = enabled,
        onClick = { onSelectionChange(selection.copy(mode = CloneRecoveryMode.FULL_HISTORY)) },
    )
    Spacer(Modifier.height(20.dp))
    Text(
        text = stringResource(R.string.clone_history_data),
        style = MaterialTheme.typography.titleMedium,
    )
    CloneHistoryCategoryRow(
        checked = true,
        enabled = false,
        title = stringResource(R.string.clone_history_glucose),
        subtitle = stringResource(R.string.clone_history_glucose_required),
        onCheckedChange = {},
    )
    CloneHistoryCategoryRow(
        checked = selection.includeJournal && journalSupported,
        enabled = enabled && journalSupported,
        title = stringResource(R.string.clone_history_journal),
        subtitle = stringResource(
            if (journalSupported) R.string.clone_history_journal_optional
            else R.string.clone_history_journal_unsupported
        ),
        onCheckedChange = { include ->
            onSelectionChange(selection.copy(includeJournal = include))
        },
    )
}

@Composable
private fun CloneHistoryModeOption(
    selected: Boolean,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onClick, enabled = enabled)
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CloneHistoryCategoryRow(
    checked: Boolean,
    enabled: Boolean,
    title: String,
    subtitle: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) {
            onCheckedChange(!checked)
        }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CloneHistoryRecoveryProgress(
    status: CloneHistoryRecoveryUiStatus,
    actionRunning: Boolean,
    error: String?,
    onCancel: () -> Unit,
    onStartAnother: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val state = status.outgoing
    val manifest = status.manifest
    val phaseText = cloneHistoryPhaseText(state.phase)
    val progress = manifest?.compressedBytes?.takeIf { it > 0L }?.let { total ->
        (state.nextOffset.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }

    Text(phaseText, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(10.dp))
    if (progress != null && state.phase == CloneOutgoingPhase.PUTTING_PACKAGE) {
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    } else if (!state.phase.isTerminal) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    manifest?.let {
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(
                R.string.clone_history_package_summary,
                Formatter.formatShortFileSize(context, state.nextOffset.coerceAtMost(it.compressedBytes)),
                Formatter.formatShortFileSize(context, it.compressedBytes),
                it.recordCounts.values.sum(),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val visibleError = actionErrorText(state, error)
    if (!visibleError.isNullOrBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = visibleError,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Spacer(Modifier.height(20.dp))
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))
    when (state.phase) {
        CloneOutgoingPhase.COMPLETED -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
        ) {
            OutlinedButton(onClick = onStartAnother, enabled = !actionRunning) {
                Text(stringResource(R.string.clone_history_start_another))
            }
            Button(onClick = onDone) { Text(stringResource(R.string.clone_history_done)) }
        }
        CloneOutgoingPhase.CANCELLED,
        CloneOutgoingPhase.FAILED -> Button(
            onClick = onStartAnother,
            enabled = !actionRunning,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.clone_history_try_again)) }
        else -> {
            val cancellable = state.jobId != null && state.phase in setOf(
                CloneOutgoingPhase.PREPARING,
                CloneOutgoingPhase.PROBING,
                CloneOutgoingPhase.PUTTING_MANIFEST,
                CloneOutgoingPhase.PUTTING_PACKAGE,
            )
            if (cancellable) {
                OutlinedButton(
                    onClick = onCancel,
                    enabled = !actionRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.clone_history_cancel_transfer)) }
            }
        }
    }
}

private fun actionErrorText(state: CloneOutgoingState, actionError: String?): String? =
    actionError ?: state.error.takeIf { state.phase == CloneOutgoingPhase.FAILED }

@Composable
private fun cloneHistoryPhaseText(phase: CloneOutgoingPhase): String = stringResource(
    when (phase) {
        CloneOutgoingPhase.PROBING -> R.string.clone_history_checking_receiver
        CloneOutgoingPhase.PROBE_READY -> R.string.clone_history_ready
        CloneOutgoingPhase.PREPARING -> R.string.clone_history_preparing
        CloneOutgoingPhase.PUTTING_MANIFEST,
        CloneOutgoingPhase.PUTTING_PACKAGE -> R.string.clone_history_sending
        CloneOutgoingPhase.PUTTING_COMMIT,
        CloneOutgoingPhase.POLLING_STATUS -> R.string.clone_history_finishing
        CloneOutgoingPhase.PUTTING_CANCEL,
        CloneOutgoingPhase.POLLING_CANCEL -> R.string.clone_history_cancelling
        CloneOutgoingPhase.COMPLETED -> R.string.clone_history_completed
        CloneOutgoingPhase.CANCELLED -> R.string.clone_history_cancelled
        CloneOutgoingPhase.FAILED -> R.string.clone_history_failed
    }
)
