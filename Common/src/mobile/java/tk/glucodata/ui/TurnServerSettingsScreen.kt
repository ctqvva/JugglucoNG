package tk.glucodata.ui

import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import tk.glucodata.Applic
import tk.glucodata.CloneIceNetworkConfigStore
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

@Composable
fun TurnServerSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(CloneIceNetworkConfigStore.load(context)) }
    var turn by remember { mutableStateOf(readTurnEndpoint()) }
    fun reportFailure() {
        Toast.makeText(context, R.string.savefailed, Toast.LENGTH_LONG).show()
    }
    HybridSettingsContent(
        config = config,
        turn = turn,
        onBack = { navController.popBackStack() },
        onLocalDiscovery = { enabled ->
            val next = config.copy(useLocalDiscovery = enabled)
            if (CloneIceNetworkConfigStore.save(context, next)) config = next else reportFailure()
        },
        onTurnForStun = { enabled ->
            val next = config.copy(useTurnForStun = enabled)
            if (CloneIceNetworkConfigStore.save(context, next)) config = next else reportFailure()
        },
        onPreferIPv4 = { enabled ->
            val next = config.copy(preferIPv4 = enabled)
            if (CloneIceNetworkConfigStore.save(context, next)) config = next else reportFailure()
        },
        onSaveTurn = { nextTurn ->
            val previous = turn
            val nextConfig = config.copy(useTurnForStun = nextTurn != null && config.useTurnForStun)
            writeTurnEndpoint(nextTurn)
            if (readTurnEndpoint() != nextTurn || !CloneIceNetworkConfigStore.save(context, nextConfig)) {
                writeTurnEndpoint(previous)
                reportFailure()
                false
            } else {
                turn = nextTurn
                config = nextConfig
                if (previous != nextTurn) Natives.resetnetwork()
                Applic.wakemirrors()
                true
            }
        },
        onSaveRendezvous = { host, port, verify ->
            val next = config.copy(rendezvousHost = host, rendezvousPort = port, verifyRendezvousCertificate = verify)
            val endpointChanged = config.rendezvousHost != host || config.rendezvousPort != port ||
                config.verifyRendezvousCertificate != verify
            if (CloneIceNetworkConfigStore.save(context, next)) {
                config = next
                if (endpointChanged) Natives.resetnetwork()
                Applic.wakemirrors()
                true
            } else {
                reportFailure()
                false
            }
        },
    )
}
