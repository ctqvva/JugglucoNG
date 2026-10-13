package tk.glucodata.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import tk.glucodata.R
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.CompactSheetDragHandle
import tk.glucodata.ui.components.SettingsSwitchItem
import tk.glucodata.ui.components.StableModalBottomSheet

/** Separate AiDex sheet: no changes to Sibionics model choices or behavior. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AiDexAlgorithmSheet(serial: String, selection: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    var mode by remember(serial, selection) { mutableIntStateOf(selection) }
    val context = LocalContext.current
    var ribbon by remember { mutableStateOf(GlucoseUncertaintyDisplay.isRibbonEnabled(context)) }
    StableModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { CompactSheetDragHandle() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text(stringResource(R.string.auto_calibration_mode), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    Triple(0, R.string.aidex_stock_calibration, R.string.aidex_stock_calibration_desc),
                    Triple(8, R.string.sibionics_adaptive_v2, R.string.aidex_adaptive_v2_desc),
                ).forEachIndexed { index, (base, title, description) ->
                    val selected = mode and 8 == base
                    Surface(shape = if (index == 0) RoundedCornerShape(12.dp, 12.dp, 4.dp, 4.dp)
                        else RoundedCornerShape(4.dp, 4.dp, 12.dp, 12.dp),
                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
                        Row(Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton,
                            onClick = {
                                val next = if (base == 0) 0 else 8 or (mode and 1)
                                if (next != mode) { mode = next; onSelect(next) }
                            }).padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(description), style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(16.dp))
                            RadioButton(selected, onClick = null)
                        }
                    }
                }
            }
            AnimatedVisibility(visible = mode and 8 != 0) {
                Column {
                    Spacer(Modifier.height(16.dp))
                    SettingsSwitchItem(title = stringResource(R.string.calibration),
                        subtitle = stringResource(R.string.sibionics_stock_algorithm_detail),
                        subtitleStyle = MaterialTheme.typography.bodySmall,
                        checked = mode and 1 != 0,
                        onCheckedChange = { enabled ->
                            mode = if (enabled) 9 else 8
                            onSelect(mode)
                        }, icon = Icons.Default.Science, iconTint = MaterialTheme.colorScheme.primary,
                        position = CardPosition.TOP)
                    Spacer(Modifier.height(4.dp))
                    SettingsSwitchItem(title = stringResource(R.string.sibionics_uncertainty_ribbon),
                        subtitle = stringResource(R.string.sibionics_uncertainty_ribbon_desc),
                        subtitleStyle = MaterialTheme.typography.bodySmall, checked = ribbon,
                        onCheckedChange = { ribbon = it; GlucoseUncertaintyDisplay.setRibbonEnabled(context, it) },
                        icon = Icons.Default.Insights, iconTint = MaterialTheme.colorScheme.tertiary,
                        position = CardPosition.BOTTOM)
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onDismiss, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.close)) }
        }
    }
}
