package tk.glucodata.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import tk.glucodata.BuildConfig
import tk.glucodata.R
import tk.glucodata.ui.WearNavigationRow
import tk.glucodata.ui.WearSectionTitle

@Composable
fun SettingsScreen(
    onOpenAlerts: () -> Unit,
    onOpenSensor: () -> Unit,
    onOpenExchange: () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val deltaSettings = androidx.compose.runtime.remember(context) { tk.glucodata.settings.DisplayDeltaSettings(context) }
    val deltaState by deltaSettings.state.collectAsState(tk.glucodata.settings.DisplayDeltaSettings.State())
    ScreenScaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 22.dp),
        ) {
            item {
                WearSectionTitle(stringResource(R.string.settings))
            }
            item {
                WearNavigationRow(stringResource(R.string.alarms), onClick = onOpenAlerts)
            }
            item {
                WearNavigationRow(stringResource(R.string.sensor), onClick = onOpenSensor)
            }
            // Reading colours are no longer a watch-local choice: the palette,
            // the band overrides and the "colour values by range" switch all
            // arrive from the phone, so the two surfaces cannot disagree.
            // Same for the predictive simulation, which is shown here only so
            // the state is visible from the wrist.
            item {
                val known = tk.glucodata.WearToggleSync
                    .knownEnabled(tk.glucodata.WearToggleSync.SCOPE_PREF, "prediction")
                androidx.wear.compose.material3.SwitchButton(
                    checked = known ?: tk.glucodata.ui.WearPrediction.isEnabled(),
                    onCheckedChange = { on ->
                        tk.glucodata.WearToggleSync
                            .request(tk.glucodata.WearToggleSync.SCOPE_PREF, "prediction", on)
                    },
                    label = { Text(stringResource(R.string.wear_prediction_title)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                androidx.wear.compose.material3.SwitchButton(
                    checked = deltaState.wearEnabled,
                    onCheckedChange = { on ->
                        deltaSettings.setWearEnabled(on)
                    },
                    label = { Text(stringResource(R.string.display_show_delta_title)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                WearNavigationRow(
                    stringResource(R.string.wear_exchange_title),
                    onClick = onOpenExchange,
                )
            }
            item {
                Text(
                    text = BuildConfig.VERSION_NAME,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
