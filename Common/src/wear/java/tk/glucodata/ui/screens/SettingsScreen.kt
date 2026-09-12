package tk.glucodata.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
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
                WearNavigationRow(
                    stringResource(R.string.wear_exchange_title),
                    onClick = onOpenExchange,
                )
            }
            // Trace logging had no switch on the watch at all, so a wrist-side problem could
            // not be captured: the native log holds its startup header and then stops, because
            // the Java guards mirror a native switch only the phone could flip.
            if (BuildConfig.doLog == 1) {
                item {
                    val loggingState = androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(
                            runCatching { tk.glucodata.Natives.islogging() }.getOrDefault(false)
                        )
                    }
                    val logging = loggingState.value
                    androidx.wear.compose.material3.SwitchButton(
                        checked = logging,
                        onCheckedChange = { on ->
                            loggingState.value = on
                            runCatching {
                                tk.glucodata.Natives.dolog(on)
                                // The Java-side guards cache the native switch; without this
                                // refresh they stay short-circuited and the trace stays empty.
                                tk.glucodata.Log.refreshDoLog()
                            }
                        },
                        label = {
                            Text(
                                stringResource(
                                    if (logging) R.string.debug_record_on else R.string.debug_record_off
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
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
