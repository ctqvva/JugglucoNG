package tk.glucodata.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import tk.glucodata.drivers.libre3.Libre3ManagedNfc
import tk.glucodata.drivers.libre3.Libre3Persistence
import tk.glucodata.drivers.libre3.Libre3ReceiverId
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.SettingsSwitchItem

/**
 * Settings for the Kotlin-managed Libre 3 driver: whether Libre 3 NFC scans use it, the app
 * identity it authorizes with (imported from a file; the app ships none), and where the NFC
 * receiver ID comes from.
 */
@Composable
fun Libre3ManagedDriverSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(Libre3Persistence.isEnabled(context)) }
    var labels by remember { mutableStateOf<List<String>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var useAccountReceiver by remember {
        mutableStateOf(Libre3Persistence.getReceiverSource(context) == Libre3Persistence.RECEIVER_FROM_LIBREVIEW_ACCOUNT)
    }
    var receiverHex by remember { mutableStateOf(Libre3ReceiverId.toWireHex(Libre3ManagedNfc.receiverId(context))) }

    fun refreshIdentities() {
        scope.launch {
            labels = withContext(Dispatchers.IO) { Libre3Persistence.loadIdentities(context).map { it.label } }
            loaded = true
        }
    }
    if (!loaded) refreshIdentities()

    val importFailed = stringResource(R.string.libre3_managed_identity_import_failed)
    val importDone = stringResource(R.string.libre3_managed_identity_imported)
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)?.use { stream ->
                        val bytes = stream.readBytes()
                        require(bytes.size <= 64 * 1024) { "file too large" }
                        String(bytes, Charsets.UTF_8)
                    } ?: error("cannot open file")
                    Libre3Persistence.importIdentities(context, text).map { it.label }
                }
            }
            result.onSuccess {
                labels = it
                Toast.makeText(context, importDone.format(it.size), Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, importFailed.format(it.message ?: it.javaClass.simpleName), Toast.LENGTH_LONG).show()
            }
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
            },
            icon = Icons.Default.Nfc,
            iconTint = MaterialTheme.colorScheme.secondary,
            position = CardPosition.TOP,
        )
        SettingsSwitchItem(
            title = stringResource(R.string.libre3_managed_receiver_account),
            subtitle = stringResource(R.string.libre3_managed_receiver_desc, receiverHex),
            checked = useAccountReceiver,
            onCheckedChange = {
                useAccountReceiver = it
                Libre3Persistence.setReceiverSource(
                    context,
                    if (it) Libre3Persistence.RECEIVER_FROM_LIBREVIEW_ACCOUNT else Libre3Persistence.RECEIVER_OWN,
                )
                receiverHex = Libre3ReceiverId.toWireHex(Libre3ManagedNfc.receiverId(context))
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
                    stringResource(R.string.libre3_managed_identity_list, labels.joinToString())
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.libre3_managed_identity_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                    Text(stringResource(R.string.libre3_managed_identity_import))
                }
                if (labels.isNotEmpty()) {
                    OutlinedButton(onClick = {
                        Libre3Persistence.clearIdentities(context)
                        labels = emptyList()
                    }) {
                        Text(stringResource(R.string.libre3_managed_identity_remove))
                    }
                }
            }
        }
    }
}
