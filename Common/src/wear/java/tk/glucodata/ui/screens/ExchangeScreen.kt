package tk.glucodata.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import kotlinx.coroutines.launch
import tk.glucodata.ExchangeToggles
import tk.glucodata.R
import tk.glucodata.UiRefreshBus
import tk.glucodata.WearToggleSync
import tk.glucodata.ui.WearSectionTitle

/**
 * On/off for the exchange outputs that are a plain switch.
 *
 * The phone is the authority: the outputs only ever run there, so a switch here
 * asks it to change and renders what it reports back. A flip that does not take
 * therefore returns on its own rather than showing a state that is not real.
 *
 * Outputs configured by a URL or a recipient list — Nightscout, the outbound
 * API, the Juggluco / patched-Libre / EverSense broadcasts — are not here: for
 * those "off" would mean discarding configuration, which a switch on a watch
 * should not do silently.
 */
@Composable
fun ExchangeScreen() {
    var revision by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        WearToggleSync.requestState()
        launch { UiRefreshBus.revision.collect { revision++ } }
    }

    ScreenScaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 28.dp),
        ) {
            item { WearSectionTitle(stringResource(R.string.wear_exchange_title)) }
            items(ExchangeToggles.all) { toggle ->
                val known = remember(revision, toggle.id) {
                    WearToggleSync.knownEnabled(WearToggleSync.SCOPE_EXCHANGE, toggle.id)
                }
                SwitchButton(
                    checked = known == true,
                    // Until the phone has answered there is nothing truthful to
                    // show, so the switch stays inert rather than guessing.
                    enabled = known != null,
                    onCheckedChange = { on ->
                        WearToggleSync.request(WearToggleSync.SCOPE_EXCHANGE, toggle.id, on)
                    },
                    label = { Text(stringResource(toggle.labelResId)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text(
                    text = stringResource(R.string.wear_exchange_phone_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
                )
            }
        }
    }
}
