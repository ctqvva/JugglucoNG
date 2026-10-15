package tk.glucodata.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MergeType
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
import tk.glucodata.alerts.DualSensorConsensus
import tk.glucodata.combined.CombinedSensorRegistry
import tk.glucodata.ui.components.MasterSwitchCard
import tk.glucodata.ui.viewmodel.SensorInfo

@Composable
internal fun SensorCombinationCard(sensors: List<SensorInfo>) {
    val revision by CombinedSensorRegistry.revision.collectAsStateWithLifecycle()
    val config = remember(revision) { CombinedSensorRegistry.config() }
    val physical = sensors.filterNot { CombinedSensorRegistry.isCombined(it.serial) }
    val selected = physical.filter { it.isSelectedForDisplay }.map { it.serial }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var saving by remember { mutableStateOf(false) }
    val result = remember(revision) { CombinedSensorRegistry.result() }
    Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MasterSwitchCard(
            title = stringResource(R.string.combine_sensors_title),
            subtitle = stringResource(when {
                !config.enabled && selected.size < 2 -> R.string.combine_sensors_select
                !config.enabled -> R.string.experimental
                !CombinedSensorRegistry.hasFreshOutput() -> R.string.no_data_available
                result?.status == DualSensorConsensus.Status.WAITING_FOR_PEER -> R.string.combine_sensors_waiting
                result?.estimateMgdl != null -> R.string.combine_sensors_active
                else -> R.string.combine_sensors_fallback
            }),
            checked = config.enabled,
            enabled = !saving && (config.enabled || selected.size >= 2),
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
    }
}
