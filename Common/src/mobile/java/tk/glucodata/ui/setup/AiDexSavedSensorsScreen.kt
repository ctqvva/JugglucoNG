package tk.glucodata.ui.setup

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.R
import tk.glucodata.RestrictedMode
import tk.glucodata.drivers.aidex.AiDexManagedSensorIdentityAdapter
import tk.glucodata.drivers.aidex.AiDexPairingMaterialFile
import tk.glucodata.drivers.aidex.AiDexProvisioningStore
import tk.glucodata.drivers.aidex.bloodcontrol.BloodControlAidex

internal fun loadAiDexSavedKeySensors(
    context: Context,
    known: List<AiDexKeySensor>,
    selectedSerials: List<String> = emptyList(),
): List<AiDexSavedKeySensor> {
    val managed = AiDexManagedSensorIdentityAdapter.persistedSensorIds(context)
        .mapNotNull(AiDexPairingMaterialFile::normalizeSerial).toSet()
    return AiDexProvisioningStore.savedSerials(context,
        known.map { it.serial } + managed + selectedSerials + BloodControlAidex.cachedSerials(context))
        .map { serial -> AiDexSavedKeySensor(serial, serial in managed,
            known.any { it.serial == serial && it.connected }) }
}

/** Optional management page. Retrieval and replacement remain on the API/account page. */
@Composable
internal fun AiDexSavedSensorsScreen(
    ui: WizardUiMetrics,
    knownSensors: List<AiDexKeySensor>,
    selectedSerial: String,
    onSelect: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fullModeGuard = tk.glucodata.ui.restricted.rememberFullModeGuard()
    var saved by remember { mutableStateOf<List<AiDexSavedKeySensor>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }

    LaunchedEffect(refresh, knownSensors, selectedSerial) {
        saved = withContext(Dispatchers.IO) {
            loadAiDexSavedKeySensors(context, knownSensors, listOf(selectedSerial))
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val serial = pendingExport
        pendingExport = null
        if (uri != null && serial != null && !RestrictedMode.isLocked()) scope.launch {
            val exported = withContext(Dispatchers.IO) {
                AiDexKeyDocuments.exportKeys(context, serial, uri)
            }
            if (RestrictedMode.isLocked()) return@launch
            status = context.getString(if (exported) R.string.ottai_save_ok else R.string.ottai_save_nothing)
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !RestrictedMode.isLocked()) scope.launch {
            busy = true
            val serial = withContext(Dispatchers.IO) {
                AiDexKeyDocuments.importKeys(context, uri)
            }
            busy = false
            if (RestrictedMode.isLocked()) return@launch
            refresh++
            if (serial != null) onSelect(serial)
            else status = context.getString(R.string.aidex_key_import_failed)
        }
    }

    pendingDelete?.let { serial ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.ottai_delete_title)) },
            text = { Text(stringResource(R.string.aidex_delete_keys_message, serial)) },
            confirmButton = {
                Button(onClick = { fullModeGuard {
                    pendingDelete = null
                    busy = true
                    scope.launch {
                        val removed = withContext(Dispatchers.IO) {
                            val managed = AiDexManagedSensorIdentityAdapter.persistedSensorIds(context)
                                .any { AiDexPairingMaterialFile.normalizeSerial(it) == serial }
                            !managed && AiDexProvisioningStore.removeSaved(context, serial)
                        }
                        busy = false
                        refresh++
                        status = if (removed) context.getString(R.string.ottai_delete_done, serial)
                            else context.getString(R.string.error)
                    }
                } }, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.ContentPadding,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingDelete = null }, shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.ContentPadding) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = ui.horizontalPadding).padding(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AiDexSavedSensorsSection(saved, !busy,
            onSelect = onSelect,
            onExport = { serial -> fullModeGuard {
                pendingExport = serial
                exportLauncher.launch("aidex_$serial.json")
            } },
            onDelete = { serial -> fullModeGuard { pendingDelete = serial } },
        )
        OttaiImportButton(onClick = { fullModeGuard {
            importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
        } }, enabled = !busy, label = stringResource(R.string.ottai_entry_import_title))
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodyMedium)
    }
}
