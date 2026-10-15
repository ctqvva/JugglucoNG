package tk.glucodata.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tk.glucodata.Applic
import tk.glucodata.Notify
import tk.glucodata.R
import tk.glucodata.alerts.DualSensorConsensus
import tk.glucodata.alerts.DualSensorRuntime

@Composable
internal fun DualSensorComparisonCard(modifier: Modifier = Modifier) {
    val display by DualSensorRuntime.display.collectAsStateWithLifecycle()
    if (!display.enabled) return
    val result = display.result
    val disagreeing = result.status == DualSensorConsensus.Status.DISAGREEING
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (disagreeing)
            MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.sensor_comparison_title), style = MaterialTheme.typography.titleSmall)
            if (result.first != null && result.second != null) {
                val isMmol = Applic.unit == 1
                fun format(mgdl: Float) = Notify.glucosestr(if (isMmol) mgdl / 18.0182f else mgdl)
                Text("${result.first.sensorId}: ${format(result.first.mgdl)}\n" +
                    "${result.second.sensorId}: ${format(result.second.mgdl)}",
                    style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.sensor_difference_format, result.differencePercent?.toInt() ?: 0),
                    style = MaterialTheme.typography.labelLarge)
                if (display.showEstimate && result.estimateMgdl != null) {
                    Text(stringResource(R.string.sensor_estimate_value, format(result.estimateMgdl)),
                        style = MaterialTheme.typography.titleMedium)
                }
            }
            val status = when {
                disagreeing -> R.string.sensor_disagreement_guidance
                result.status == DualSensorConsensus.Status.UNAVAILABLE ||
                    result.status == DualSensorConsensus.Status.WAITING_FOR_PEER -> R.string.sensor_comparison_unavailable
                result.status == DualSensorConsensus.Status.LEARNING -> R.string.sensor_comparison_learning
                else -> R.string.sensor_comparison_help
            }
            Text(stringResource(status), style = MaterialTheme.typography.bodySmall)
            if (display.showEstimate) {
                Text(stringResource(R.string.sensor_estimate_help), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
