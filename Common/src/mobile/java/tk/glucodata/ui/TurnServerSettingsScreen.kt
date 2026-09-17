package tk.glucodata.ui

import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import tk.glucodata.Applic
import tk.glucodata.CloneIceNetworkConfigStore
import tk.glucodata.CloneSensorRegistry
import tk.glucodata.Natives
import tk.glucodata.R

private fun readTurnEndpoint(): TurnEndpoint? = if (Natives.TurnServerNR() == 0) null else TurnEndpoint(
    Natives.getTurnHost(0).orEmpty(), Natives.getTurnPort(0),
    Natives.getTurnUser(0).orEmpty(), Natives.getTurnPassword(0).orEmpty(),
)

private fun writeTurnEndpoint(endpoint: TurnEndpoint?) {
    if (endpoint == null) Natives.deleteTurnServer(0)
    else Natives.setTurnServer(0, endpoint.host, endpoint.port, endpoint.username, endpoint.password)
}

/**
 * The screen edits a draft and applies it in one step. Switches take effect on the next
 * connection attempt and never disturb a live one; a changed server does, once, when
 * Apply is pressed -- not on every keystroke or focus change on the way there.
 */
@Composable
fun TurnServerSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(CloneIceNetworkConfigStore.load(context)) }
    var saved by remember { mutableStateOf(HybridDraft.of(config, readTurnEndpoint())) }
    var draft by remember { mutableStateOf(saved) }
    // Read once per entry: the button's wording is what it is about to do, and a route
    // that changes mid-edit is not worth a recomposition storm to track.
    val liveConnection = remember { CloneSensorRegistry.hasLiveCloneConnection() }

    HybridSettingsContent(
        draft = draft,
        saved = saved,
        liveConnection = liveConnection,
        onDraftChange = { draft = it },
        onBack = { navController.popBackStack() },
        onApply = {
            val previousTurn = saved.turnEndpoint
            val nextTurn = draft.turnEndpoint
            val nextConfig = draft.toConfig(config)
            val reconnect = draft.changesServers(saved)
            writeTurnEndpoint(nextTurn)
            if (readTurnEndpoint() != nextTurn || !CloneIceNetworkConfigStore.save(context, nextConfig)) {
                writeTurnEndpoint(previousTurn)
                Toast.makeText(context, R.string.savefailed, Toast.LENGTH_LONG).show()
            } else {
                config = nextConfig
                saved = HybridDraft.of(nextConfig, nextTurn)
                draft = saved
                if (reconnect) Natives.resetnetwork()
                Applic.wakemirrors()
            }
        },
    )
}
