package tk.glucodata.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.R
import tk.glucodata.drivers.libre3.Libre3IdentityProvisioning
import tk.glucodata.drivers.libre3.Libre3IdentitySource
import tk.glucodata.drivers.libre3.Libre3ManagedNfc
import tk.glucodata.drivers.libre3.Libre3Persistence
import tk.glucodata.drivers.libre3.Libre3ReceiverId
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.SettingsSwitchItem

/**
 * Settings for the Kotlin-managed Libre 3 driver: whether Libre 3 NFC scans use it, where the
 * NFC receiver ID comes from, and the app identity sets it authorizes with (downloaded from a
 * digest-pinned source, or imported from a file; the app ships none).
 */
@Composable
fun Libre3ManagedDriverSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(Libre3Persistence.isEnabled(context)) }
    var labels by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedVersion by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var useAccountReceiver by remember {
        mutableStateOf(Libre3Persistence.getReceiverSource(context) == Libre3Persistence.RECEIVER_FROM_LIBREVIEW_ACCOUNT)
    }
    var customUrl by remember { mutableStateOf(Libre3Persistence.customIdentitySource(context)?.urls?.firstOrNull() ?: "") }
    var customDigest by remember { mutableStateOf(Libre3Persistence.customIdentitySource(context)?.sha256 ?: "") }

    LaunchedEffect(reload) {
        val (version, list) = withContext(Dispatchers.IO) {
            Libre3Persistence.selectedIdentityVersion(context) to Libre3Persistence.loadIdentities(context).map { it.label }
        }
        selectedVersion = version
        labels = list
    }

    // Recomputed on every composition: the LibreView account ID can change while this is shown.
    val receiver = Libre3ManagedNfc.receiverId(context)
    val receiverText = if (receiver == 0L) stringResource(R.string.libre3_managed_receiver_none)
    else Libre3ReceiverId.toWireHex(receiver)

    val importFailed = stringResource(R.string.libre3_managed_identity_import_failed)
    val importDone = stringResource(R.string.libre3_managed_identity_imported)
    fun reportResult(result: Result<List<*>>) {
        result.onSuccess {
            Toast.makeText(context, importDone.format(it.size), Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, importFailed.format(it.message ?: it.javaClass.simpleName), Toast.LENGTH_LONG).show()
        }
        reload++
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // Bounded read: the picker accepts any file, and a huge one must not be buffered.
                    val bytes = context.contentResolver.openInputStream(uri)?.use { Libre3IdentityProvisioning.readBounded(it) }
                        ?: error("cannot open file")
                    Libre3Persistence.importIdentities(context, bytes)
                }
            }
            reportResult(result)
        }
    }

    fun download(source: Libre3IdentitySource) {
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Libre3Persistence.provisionIdentities(context, source) } }
            busy = false
            reportResult(result)
        }
    }

    SectionLabel(text = stringResource(R.string.libre3_managed_title))
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SettingsSwitchItem(
            title = stringResource(R.string.libre3_managed_enable),
            subtitle = stringResource(R.string.libre3_managed_enable_desc),
            checked = enabled,
            onCheckedChange = {
                enabled = it
                Libre3Persistence.setEnabled(context, it)
                val source = Libre3Persistence.effectiveIdentitySource(context)
                if (it && labels.isEmpty() && source != null && !busy) download(source)
            },
            icon = Icons.Default.Nfc,
            iconTint = MaterialTheme.colorScheme.secondary,
            position = CardPosition.TOP,
        )
        SettingsSwitchItem(
            title = stringResource(R.string.libre3_managed_receiver_account),
            subtitle = stringResource(R.string.libre3_managed_receiver_desc, receiverText),
            checked = useAccountReceiver,
            onCheckedChange = {
                useAccountReceiver = it
                Libre3Persistence.setReceiverSource(
                    context,
                    if (it) Libre3Persistence.RECEIVER_FROM_LIBREVIEW_ACCOUNT else Libre3Persistence.RECEIVER_OWN,
                )
            },
            icon = Icons.Default.Key,
            iconTint = MaterialTheme.colorScheme.secondary,
            position = CardPosition.BOTTOM,
        )
    }
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (labels.isEmpty()) {
                    stringResource(R.string.libre3_managed_identity_none)
                } else {
                    stringResource(R.string.libre3_managed_identity_list, selectedVersion ?: "", labels.joinToString())
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.libre3_managed_identity_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val source = Libre3Persistence.effectiveIdentitySource(context)
            if (source != null) {
                Text(
                    text = stringResource(
                        R.string.libre3_managed_identity_source,
                        source.version,
                        source.sha256.take(12),
                        source.urls.joinToString { runCatching { java.net.URL(it).host }.getOrDefault(it) },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (source != null) {
                    FilledTonalButton(
                        onClick = { download(source) },
                        enabled = !busy,
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(R.string.libre3_managed_identity_download))
                    }
                }
                FilledTonalButton(
                    onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    enabled = !busy,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(R.string.libre3_managed_identity_import))
                }
                if (labels.isNotEmpty()) {
                    OutlinedButton(
                        onClick = {
                            // Also drops each sensor's cached kAuth, so nothing keeps authorizing
                            // with a removed identity.
                            Libre3Persistence.clearIdentities(context)
                            reload++
                        },
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(R.string.libre3_managed_identity_remove))
                    }
                }
            }
            OutlinedTextField(
                value = customUrl,
                onValueChange = { customUrl = it.trim() },
                label = { Text(stringResource(R.string.libre3_managed_source_url)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = customDigest,
                onValueChange = { customDigest = it.trim() },
                label = { Text(stringResource(R.string.libre3_managed_source_digest)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val invalidSource = stringResource(R.string.libre3_managed_source_invalid)
            OutlinedButton(
                onClick = {
                    if (customUrl.isBlank() && customDigest.isBlank()) {
                        Libre3Persistence.setCustomIdentitySource(context, null)
                        reload++
                    } else {
                        val custom = Libre3IdentitySource.custom(customUrl, customDigest)
                        if (custom == null) {
                            Toast.makeText(context, invalidSource, Toast.LENGTH_LONG).show()
                        } else {
                            Libre3Persistence.setCustomIdentitySource(context, custom)
                            download(custom)
                        }
                    }
                },
                enabled = !busy,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(R.string.libre3_managed_source_save))
            }
        }
    }
}
