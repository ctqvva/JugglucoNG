package tk.glucodata.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.R
import tk.glucodata.UiRefreshBus
import tk.glucodata.alerts.DualSensorConsensus
import tk.glucodata.combined.CombinedSensorRegistry
import tk.glucodata.ui.components.MasterSwitchCard
import tk.glucodata.ui.viewmodel.SensorInfo

@Composable
internal fun SensorCombinationCard(sensors: List<SensorInfo>) {
    val revision by CombinedSensorRegistry.revision.collectAsStateWithLifecycle()
    val dataRevision by UiRefreshBus.revision.collectAsStateWithLifecycle(initialValue = 0L)
    val config = remember(revision) { CombinedSensorRegistry.config() }
    val physical = sensors.filterNot { CombinedSensorRegistry.isCombined(it.serial) }
    val selected = physical.filter { it.isSelectedForDisplay }.map { it.serial }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var saving by remember { mutableStateOf(false) }
    val result = remember(dataRevision) { CombinedSensorRegistry.result() }
    Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MasterSwitchCard(
            title = stringResource(R.string.combine_sensors_title),
            subtitle = stringResource(R.string.experimental),
            checked = config.enabled,
            enabled = !saving && (config.enabled || selected.size == 2),
            icon = Icons.Default.MergeType,
            onCheckedChange = { enabled ->
                saving = true
                scope.launch {
                    try { withContext(Dispatchers.IO) { CombinedSensorRegistry.setEnabled(enabled, selected) } }
                    catch (t: Exception) {
                        Toast.makeText(context, R.string.savefailed, Toast.LENGTH_LONG).show()
                    } finally { saving = false }
                }
            }
        )
        if (!config.enabled) Text(stringResource(R.string.combine_sensors_help),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
        val pair = if (config.enabled) config.inputs else selected
        if (pair.isNotEmpty()) Text(pair.joinToString(" + "),
            style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp))
        val status = when {
            !config.enabled -> R.string.combine_sensors_select
            !CombinedSensorRegistry.hasFreshOutput() -> R.string.no_data_available
            result?.estimateMgdl != null -> R.string.combine_sensors_active
            result?.status == DualSensorConsensus.Status.WAITING_FOR_PEER -> R.string.combine_sensors_waiting
            else -> R.string.combine_sensors_fallback
        }
        Text(stringResource(status), style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp))
    }
}
