package tk.glucodata.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import tk.glucodata.Applic
import tk.glucodata.R
import tk.glucodata.alerts.AlertRepository
import tk.glucodata.alerts.AlertType
import tk.glucodata.UiRefreshBus
import tk.glucodata.WearToggleSync
import tk.glucodata.alerts.SnoozeManager
import tk.glucodata.ui.WearSectionTitle

/**
 * Alert enable/disable, thresholds and snooze cancel. Threshold *editing* stays
 * on the phone.
 *
 * Enabling used to write the watch's own AlertRepository and stop there — a
 * device-local preferences file the phone never reads — so turning an alert off
 * here left it firing on the phone, and the two screens disagreed for good. The
 * switch now asks the phone, which applies it, writes its own copy and reports
 * back; the watch's copy follows from that reply, so both fire the same set.
 */
@Composable
fun AlertsScreen() {
    val isMmol = remember { runCatching { Applic.unit == 1 }.getOrDefault(false) }
    var revision by remember { mutableIntStateOf(0) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        WearToggleSync.requestState()
        UiRefreshBus.revision.collect { revision++ }
    }

    ScreenScaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 22.dp),
        ) {
            item {
                WearSectionTitle(stringResource(R.string.alarms))
            }
            items(AlertType.settingsEntries) { type ->
                val config = remember(type, revision) {
                    runCatching { AlertRepository.loadConfig(type) }.getOrNull()
                } ?: return@items
                val snoozed = remember(type, revision) {
                    runCatching { SnoozeManager.isSnoozed(type) }.getOrDefault(false)
                }
                // The phone's reply is the truth; until it has one, show what
                // this device holds so the list is never blank.
                val known = remember(type, revision) {
                    WearToggleSync.knownEnabled(WearToggleSync.SCOPE_ALERT, type.id.toString())
                }
                SwitchButton(
                    checked = known ?: config.enabled,
                    onCheckedChange = { on ->
                        WearToggleSync.request(WearToggleSync.SCOPE_ALERT, type.id.toString(), on)
                    },
                    label = { Text(stringResource(type.nameResId)) },
                    secondaryLabel = config.threshold?.let { threshold ->
                        {
                            Text(
                                if (isMmol) {
                                    String.format(java.util.Locale.US, "%.1f", threshold)
                                } else {
                                    threshold.toInt().toString()
                                }
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (snoozed) {
                    Button(
                        onClick = {
                            runCatching { SnoozeManager.clearSnooze(type) }
                            revision++
                        },
                        label = { Text(stringResource(R.string.cancel)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
