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
 * Saves itself. Switches are written as they are flipped; they only matter on the next
 * connection attempt. A server is written when the screen is left, and used from the
 * next connection on -- a live connection is never rebuilt behind the user's back. The
 * one thing the user asks for explicitly is that rebuild, through the Apply button a
 * changed server shows while a route is up.
 */
@Composable
fun TurnServerSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(CloneIceNetworkConfigStore.load(context)) }
    var saved by remember { mutableStateOf(HybridDraft.of(config, readTurnEndpoint())) }
    var draft by remember { mutableStateOf(saved) }
    val liveConnection = remember { CloneSensorRegistry.hasLiveCloneConnection() }

    fun reportFailure() {
        Toast.makeText(context, R.string.savefailed, Toast.LENGTH_LONG).show()
    }

    /**
     * Writes what [next] says about the servers, skipping a server whose draft does not
     * parse; a half-typed hostname is not a hostname. Returns what is now saved.
     */
    fun persistServers(next: HybridDraft, reconnect: Boolean): HybridDraft {
        val applyTurn = next.turnValid && next.turnChanged(saved)
        val applyRendezvous = next.rendezvousValid && next.rendezvousChanged(saved)
        if (!applyTurn && !applyRendezvous) return saved
        val effective = if (applyTurn && applyRendezvous) next else if (applyTurn) {
            saved.copy(customTurn = next.customTurn, turnHost = next.turnHost, turnPort = next.turnPort,
                turnUser = next.turnUser, turnPassword = next.turnPassword, useTurnForStun = next.useTurnForStun)
        } else {
            saved.copy(customRendezvous = next.customRendezvous, rendezvousHost = next.rendezvousHost,
                rendezvousPort = next.rendezvousPort, verifyRendezvousCertificate = next.verifyRendezvousCertificate)
        }
        val previousTurn = saved.turnEndpoint
        val nextTurn = effective.turnEndpoint
        val nextConfig = effective.toConfig(config)
        writeTurnEndpoint(nextTurn)
        if (readTurnEndpoint() != nextTurn || !CloneIceNetworkConfigStore.save(context, nextConfig)) {
            writeTurnEndpoint(previousTurn)
            reportFailure()
            return saved
        }
        config = nextConfig
        if (reconnect) Natives.resetnetwork()
        Applic.wakemirrors()
        return HybridDraft.of(nextConfig, nextTurn)
    }

    fun persistSwitches(next: HybridDraft) {
        val nextConfig = config.copy(
            useLocalDiscovery = next.useLocalDiscovery,
            useTurnForStun = next.useTurnForStun && saved.turnEndpoint != null,
            preferIPv4 = next.preferIPv4,
        )
        if (CloneIceNetworkConfigStore.save(context, nextConfig)) {
            config = nextConfig
            saved = saved.copy(
                useLocalDiscovery = nextConfig.useLocalDiscovery,
                useTurnForStun = nextConfig.useTurnForStun,
                preferIPv4 = nextConfig.preferIPv4,
            )
        } else {
            reportFailure()
        }
    }

    // Leaving the screen is the save. Whatever parses is written; a route that is up
    // keeps its current server until it next reconnects, which is the whole point.
    DisposableEffect(Unit) {
        onDispose { persistServers(draft, reconnect = false) }
    }

    HybridSettingsContent(
        draft = draft,
        saved = saved,
        liveConnection = liveConnection,
        onDraftChange = { next ->
            draft = next
            if (next.switchesChanged(saved)) persistSwitches(next)
        },
        onApplyTurn = {
            saved = persistServers(draft.copy(
                customRendezvous = saved.customRendezvous, rendezvousHost = saved.rendezvousHost,
                rendezvousPort = saved.rendezvousPort, verifyRendezvousCertificate = saved.verifyRendezvousCertificate,
            ), reconnect = true)
            draft = draft.copy(useTurnForStun = saved.useTurnForStun)
        },
        onApplyRendezvous = {
            saved = persistServers(draft.copy(
                customTurn = saved.customTurn, turnHost = saved.turnHost, turnPort = saved.turnPort,
                turnUser = saved.turnUser, turnPassword = saved.turnPassword,
            ), reconnect = true)
        },
        onBack = { navController.popBackStack() },
    )
}
