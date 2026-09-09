@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package tk.glucodata.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationImportant
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.DateFormat
import java.util.Collections
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.webserver.WebServerCertificate
import tk.glucodata.Applic
import tk.glucodata.AutoSensorSwitch
import tk.glucodata.GoogleServices
import tk.glucodata.Natives
import tk.glucodata.Notify
import tk.glucodata.R
import tk.glucodata.SuperGattCallback
import tk.glucodata.WatchInterop
import tk.glucodata.WearSensorClaimState
import tk.glucodata.WearSensorClaimStatus
import tk.glucodata.watchdrip
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.MasterSwitchCard
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.SettingsItem
import tk.glucodata.ui.components.SettingsSwitchItem
import tk.glucodata.ui.components.StyledSwitch
import tk.glucodata.ui.components.cardShape
import androidx.compose.foundation.text.KeyboardActions

@Composable
fun WatchSettingsScreen(navController: NavController) {
    val context = LocalContext.current

    var watchdripEnabled by rememberSaveable { mutableStateOf(Natives.getwatchdrip()) }
    var gadgetBridgeEnabled by rememberSaveable { mutableStateOf(Natives.getgadgetbridge()) }
    var notifyEnabled by rememberSaveable { mutableStateOf(WatchInterop.isNotifyEnabled()) }
    var separateEnabled by rememberSaveable { mutableStateOf(Natives.getSeparate()) }
    var wearOsEnabled by rememberSaveable { mutableStateOf(WatchInterop.isWearOsEnabled()) }
    var kerfstokEnabled by rememberSaveable { mutableStateOf(Natives.getusegarmin()) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    val gmsAvailable = remember { GoogleServices.isPlayServicesAvailable(Applic.app) }
    val wearConfigEnabled = wearOsEnabled && gmsAvailable
    val garminStatusEnabled = kerfstokEnabled

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.watches)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { showHelp = true }) {
                        Icon(Icons.Filled.Info, contentDescription = stringResource(R.string.helpname))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item("watch_transport_section") {
                SectionLabel("Transport", topPadding = 0.dp)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SettingsSwitchItem(
                        title = "Watchdrip",
                        checked = watchdripEnabled,
                        icon = Icons.Filled.Watch,
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        position = CardPosition.TOP,
                        onCheckedChange = {
                            watchdripEnabled = it
                            Natives.setwatchdrip(it)
                            tk.glucodata.WearToggleSync.push()
                            watchdrip.set(it)
                        }
                    )
                    SettingsSwitchItem(
                        title = "GadgetBridge",
                        checked = gadgetBridgeEnabled,
                        icon = Icons.Filled.Hub,
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        position = CardPosition.MIDDLE,
                        onCheckedChange = {
                            gadgetBridgeEnabled = it
                            Natives.setgadgetbridge(it)
                            SuperGattCallback.doGadgetbridge = it
                            // The watch mirrors these switches; tell it now
                            // rather than leave it a cycle behind.
                            tk.glucodata.WearToggleSync.push()
                        }
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.notify),
                        checked = notifyEnabled,
                        icon = Icons.Filled.NotificationImportant,
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        position = CardPosition.MIDDLE,
                        onCheckedChange = {
                            notifyEnabled = it
                            WatchInterop.setNotifyEnabled(it)
                        }
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.separate),
                        checked = separateEnabled,
                        icon = Icons.Filled.Devices,
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        position = CardPosition.BOTTOM,
                        onCheckedChange = {
                            separateEnabled = it
                            Notify.alertseparate = it
                            Natives.setSeparate(it)
                        }
                    )
                }
            }

            item("watch_wearos_section") {
                SectionLabel("WearOS", topPadding = 0.dp)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SettingsSwitchItem(
                        title = "WearOS",
                        subtitle = if (gmsAvailable) "Enable Wear OS message transport" else "Google Play Services unavailable",
                        checked = wearOsEnabled,
                        icon = Icons.Filled.Watch,
                        iconTint = MaterialTheme.colorScheme.primary,
                        position = CardPosition.TOP,
                        onCheckedChange = { enabled ->
                            if (!gmsAvailable && enabled) {
                                Toast.makeText(
                                    context,
                                    "Google Play Services unavailable on this device",
                                    Toast.LENGTH_LONG
                                ).show()
                                return@SettingsSwitchItem
                            }
                            if (WatchInterop.setWearOsEnabled(enabled)) {
                                wearOsEnabled = WatchInterop.isWearOsEnabled()
                            } else {
                                Toast.makeText(context, context.getString(R.string.wentwrong), Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                    SettingsItem(
                        title = stringResource(R.string.config),
                        subtitle = if (wearConfigEnabled) {
                            "WearOS device and routing settings"
                        } else {
                            "Enable WearOS to configure routes"
                        },
                        icon = Icons.Filled.Settings,
                        iconTint = MaterialTheme.colorScheme.primary,
                        position = CardPosition.BOTTOM,
                        modifier = Modifier.alpha(if (wearConfigEnabled) 1f else 0.55f),
                        onClick = if (wearConfigEnabled) {
                            { navController.navigate("settings/watch/wearos-config") }
                        } else {
                            null
                        }
                    )
                }
            }

            item("watch_garmin_section") {
                SectionLabel("Garmin", topPadding = 0.dp)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SettingsSwitchItem(
                        title = "Garmin Connect IQ",
                        subtitle = "Kerfstok transport bridge",
                        checked = kerfstokEnabled,
                        icon = Icons.Filled.CheckCircle,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        position = CardPosition.TOP,
                        onCheckedChange = { enabled ->
                            if (WatchInterop.setKerfstokEnabled(context, enabled)) {
                                kerfstokEnabled = enabled
                            } else {
                                Toast.makeText(context, context.getString(R.string.wentwrong), Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                    SettingsItem(
                        title = stringResource(R.string.status),
                        subtitle = if (garminStatusEnabled) {
                            "Connection status and pairing"
                        } else {
                            "Enable Kerfstok to view status"
                        },
                        icon = Icons.Filled.Link,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        position = CardPosition.BOTTOM,
                        modifier = Modifier.alpha(if (garminStatusEnabled) 1f else 0.55f),
                        onClick = if (garminStatusEnabled) {
                            { navController.navigate("settings/watch/garmin-status") }
                        } else {
                            null
                        }
                    )
                }
            }

        }
    }
    if (showHelp) {
        InAppHelpDialog(
            title = stringResource(R.string.watches),
            lines = listOf(
                "Enable WearOS to use watch message transport in this app.",
                "Use Config to pick a watch node and set direct sensor routing.",
                "Garmin Connect IQ works through Kerfstok and has separate status controls."
            ),
            onDismiss = { showHelp = false }
        )
    }
}

@Composable
fun WearOsConfigScreen(navController: NavController) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    var nodes by remember { mutableStateOf<List<WatchInterop.WearNodeInfo>>(emptyList()) }
    var selectedNodeId by rememberSaveable { mutableStateOf("") }
    var directOnWatch by rememberSaveable { mutableStateOf(false) }
    var enterOnWatch by rememberSaveable { mutableStateOf(false) }
    // Phone-owned and not part of a node's routing, so it is read straight from
    // the setting rather than seeded from what a watch has confirmed.
    var autoSwitch by rememberSaveable { mutableStateOf(AutoSensorSwitch.isEnabled()) }
    var refreshingNodes by remember { mutableStateOf(false) }
    var syncStatus by remember { mutableStateOf(WatchInterop.getWearSyncStatus()) }
    val claimRevision by WearSensorClaimStatus.revision.collectAsState()

    fun applyNodes(latest: List<WatchInterop.WearNodeInfo>) {
        nodes = latest
        if (latest.none { it.id == selectedNodeId }) {
            selectedNodeId = latest.firstOrNull()?.id ?: ""
        }
    }

    suspend fun refreshNodesNow() {
        refreshingNodes = true
        try {
            val latest = withContext(Dispatchers.IO) {
                WatchInterop.refreshWearNodes()
                WatchInterop.getWearNodes()
            }
            applyNodes(latest)
            syncStatus = WatchInterop.getWearSyncStatus()
        } finally {
            refreshingNodes = false
        }
    }

    fun refreshNodes() {
        scope.launch {
            refreshNodesNow()
        }
    }

    LaunchedEffect(claimRevision) {
        refreshNodesNow()
    }

    // Seed the switches from what the user asked for, not from what the watch
    // has confirmed. Direct routing is a two-phase handoff — the watch only
    // claims the sensor after a connected driver accepts a reading — so binding
    // to the confirmed state made every toggle spring straight back to off.
    LaunchedEffect(selectedNodeId, nodes) {
        val selected = nodes.firstOrNull { it.id == selectedNodeId } ?: return@LaunchedEffect
        directOnWatch = when {
            selected.directRequested -> true
            selected.claimState != null -> selected.claimState != WearSensorClaimState.PHONE_OWNS
            selected.directSensorMode >= 0 -> selected.directSensorMode > 0
            else -> false
        }
        enterOnWatch = when {
            selected.enterRequested -> true
            selected.watchNumsMode >= 0 -> selected.watchNumsMode > 0
            else -> false
        }
    }

    val selected = nodes.firstOrNull { it.id == selectedNodeId }
    val selectedAppInstalled = selected?.appInstalled == true
    // Having the app on the watch is the only real precondition. Requiring a
    // known native host first was circular: applying the routing is what creates
    // that host, so both switches sat greyed out forever on a fresh pairing.
    val canSetDirect = selectedAppInstalled
    val canSetNums = selectedAppInstalled
    // Both directions apply immediately. Turning direct mode on used to change
    // nothing until "Sync now" was pressed, and the state was overwritten by the
    // next refresh before the user got there.
    fun updateDirectRouting(enabled: Boolean) {
        directOnWatch = enabled
        val node = selected ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                if (enabled) {
                    WatchInterop.applyStandaloneSensorMode(
                        node.id,
                        node.isGalaxy,
                        directOnWatch = true,
                        enterOnWatch = enterOnWatch,
                    )
                } else {
                    WatchInterop.applyWearNodeRouting(
                        node.id,
                        node.isGalaxy,
                        directOnWatch = false,
                        enterOnWatch = enterOnWatch,
                    )
                }
            }
            if (!ok) {
                directOnWatch = !enabled
                Toast.makeText(context, context.getString(R.string.wentwrong), Toast.LENGTH_SHORT).show()
            }
            refreshNodesNow()
        }
    }

    fun updateAutoSwitch(enabled: Boolean) {
        autoSwitch = enabled
        val node = selected ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                WatchInterop.setAutoSensorSwitch(node.id, enabled)
            }
            if (!ok) {
                // The setting itself is applied on the phone either way; only
                // the push to the watch can fail, and it retries at the next
                // handshake. Say so rather than springing the switch back.
                Toast.makeText(context, context.getString(R.string.wentwrong), Toast.LENGTH_SHORT).show()
            }
            refreshNodesNow()
        }
    }

    fun updateEnterOnWatch(enabled: Boolean) {
        enterOnWatch = enabled
        val node = selected ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                WatchInterop.applyWearNodeRouting(
                    node.id,
                    node.isGalaxy,
                    directOnWatch = directOnWatch,
                    enterOnWatch = enabled,
                )
            }
            if (!ok) {
                enterOnWatch = !enabled
                Toast.makeText(context, context.getString(R.string.wentwrong), Toast.LENGTH_SHORT).show()
            }
            refreshNodesNow()
        }
    }
    fun timeStatus(timeMs: Long): String = if (timeMs <= 0L) {
        "Never"
    } else {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timeMs))
    }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text("WearOS config") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { refreshNodes() }, enabled = !refreshingNodes) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item("wear_nodes") {
                SectionLabel("Watch status", topPadding = 0.dp)
                if (nodes.isEmpty()) {
                    SettingsItem(
                        title = "No Wear OS watches found",
                        subtitle = "Tap refresh after opening Juggluco on the watch",
                        icon = Icons.Filled.BluetoothSearching,
                        iconTint = MaterialTheme.colorScheme.tertiary
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        nodes.forEachIndexed { index, node ->
                            val position = when {
                                nodes.size == 1 -> CardPosition.SINGLE
                                index == 0 -> CardPosition.TOP
                                index == nodes.lastIndex -> CardPosition.BOTTOM
                                else -> CardPosition.MIDDLE
                            }
                            val isSelected = node.id == selectedNodeId
                            val appStatus = if (node.appInstalled) {
                                stringResource(R.string.watchappinstalled)
                            } else {
                                stringResource(R.string.wear_watch_app_missing)
                            }
                            val liveStatus = if (isSelected) {
                                val chunks = syncStatus.lastChunkCount
                                val claimStatus = when (node.claimState) {
                                    WearSensorClaimState.PHONE_OWNS -> stringResource(R.string.wear_claim_state_phone_owns)
                                    WearSensorClaimState.REQUESTING -> stringResource(R.string.wear_claim_state_requesting)
                                    WearSensorClaimState.CONNECTED -> stringResource(R.string.wear_claim_state_connected)
                                    null -> null
                                }
                                "\nHistory served: ${timeStatus(syncStatus.lastServedMs)}${if (chunks > 0) " ($chunks chunks)" else ""}" +
                                    "\nNetwork info: ${timeStatus(syncStatus.lastNetInfoExchangeMs)}" +
                                    (claimStatus?.let { "\n$it" } ?: "")
                            } else ""
                            val appSubtitle = if (node.appInstalled) {
                                "$appStatus • ${node.id}$liveStatus"
                            } else {
                                "$appStatus • ${node.id}\n${stringResource(R.string.wear_watch_app_missing_hint)}"
                            }
                            SettingsItem(
                                title = node.displayName,
                                subtitle = appSubtitle,
                                icon = if (node.isGalaxy) Icons.Filled.Watch else Icons.Filled.Devices,
                                iconTint = when {
                                    !node.appInstalled -> MaterialTheme.colorScheme.error
                                    isSelected -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.tertiary
                                },
                                position = position,
                                onClick = { selectedNodeId = node.id },
                                trailingContent = {
                                    if (isSelected) {
                                        Icon(
                                            Icons.Filled.CheckCircle,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }

            item("wear_routing") {
                SectionLabel("Routing", topPadding = 0.dp)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // Say which phase the handoff is in, so a switch that is on
                    // while the phone still owns Bluetooth reads as "waiting",
                    // not as "broken".
                    val handoffPhase = when {
                        selected?.claimState == WearSensorClaimState.CONNECTED ->
                            stringResource(R.string.wear_claim_state_connected)
                        directOnWatch && selected?.directPending == true ->
                            stringResource(R.string.wear_claim_state_requesting)
                        else -> stringResource(R.string.wear_claim_state_phone_owns)
                    }
                    SettingsItem(
                        title = stringResource(R.string.wear_auto_sensor_switch),
                        subtitle = if (autoSwitch) {
                            stringResource(R.string.wear_auto_sensor_switch_on_desc)
                        } else {
                            stringResource(R.string.wear_auto_sensor_switch_off_desc)
                        },
                        icon = Icons.Filled.SwapHoriz,
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        position = CardPosition.TOP,
                        onClick = if (selected != null && canSetDirect) {
                            { updateAutoSwitch(!autoSwitch) }
                        } else {
                            null
                        },
                        trailingContent = {
                            StyledSwitch(
                                checked = autoSwitch,
                                onCheckedChange = if (selected != null && canSetDirect) {
                                    { updateAutoSwitch(it) }
                                } else {
                                    null
                                },
                                enabled = selected != null && canSetDirect
                            )
                        }
                    )
                    SettingsItem(
                        title = stringResource(R.string.wear_direct_sensor_on_watch),
                        subtitle = (if (directOnWatch) {
                            stringResource(R.string.wear_direct_sensor_watch_desc)
                        } else {
                            stringResource(R.string.wear_direct_sensor_phone_desc)
                        }) + "\n" + handoffPhase,
                        icon = if (directOnWatch) Icons.Filled.Watch else Icons.Filled.PhoneAndroid,
                        iconTint = MaterialTheme.colorScheme.primary,
                        position = CardPosition.MIDDLE,
                        onClick = if (selected != null && canSetDirect) {
                            { updateDirectRouting(!directOnWatch) }
                        } else {
                            null
                        },
                        trailingContent = {
                            StyledSwitch(
                                checked = directOnWatch,
                                onCheckedChange = if (selected != null && canSetDirect) {
                                    { updateDirectRouting(it) }
                                } else {
                                    null
                                },
                                enabled = selected != null && canSetDirect
                            )
                        }
                    )
                    SettingsItem(
                        title = "Enter amounts on watch",
                        subtitle = if (enterOnWatch) {
                            "Entries made on the watch are stored and synced to the phone."
                        } else {
                            "Amounts can only be entered on the phone."
                        },
                        icon = Icons.Filled.Edit,
                        iconTint = MaterialTheme.colorScheme.primary,
                        position = CardPosition.BOTTOM,
                        onClick = if (selected != null && canSetNums) {
                            { updateEnterOnWatch(!enterOnWatch) }
                        } else {
                            null
                        },
                        trailingContent = {
                            StyledSwitch(
                                checked = enterOnWatch,
                                onCheckedChange = if (selected != null && canSetNums) {
                                    { updateEnterOnWatch(it) }
                                } else {
                                    null
                                },
                                enabled = selected != null && canSetNums
                            )
                        }
                    )
                }
            }

            item("wear_actions") {
                SectionLabel("Actions", topPadding = 0.dp)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = {
                            if (selected == null) {
                                Toast.makeText(context, "No watch selected", Toast.LENGTH_SHORT).show()
                            } else {
                                scope.launch {
                                    val routingOk = withContext(Dispatchers.IO) {
                                        WatchInterop.applyStandaloneSensorMode(selected.id, selected.isGalaxy, directOnWatch, enterOnWatch)
                                    }
                                    val ok = routingOk && WatchInterop.syncWearNow()
                                    Toast.makeText(
                                        context,
                                        if (ok) context.getString(R.string.saved) else context.getString(R.string.wentwrong),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    refreshNodes()
                                }
                            }
                        },
                        enabled = selectedAppInstalled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Sync, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Sync now")
                    }
                    OutlinedButton(
                        onClick = {
                            if (selected == null) {
                                Toast.makeText(context, "No watch selected", Toast.LENGTH_SHORT).show()
                            } else {
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) {
                                        WatchInterop.startWearApp(selected.id, selected.isGalaxy)
                                    }
                                    Toast.makeText(
                                        context,
                                        if (ok) "Restart command sent to watch" else context.getString(R.string.wentwrong),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                        enabled = selectedAppInstalled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Restart watch app")
                    }
                    OutlinedButton(
                        onClick = {
                            if (selected == null) {
                                Toast.makeText(context, "No watch selected", Toast.LENGTH_SHORT).show()
                            } else {
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) {
                                        WatchInterop.applyWearDefaults(selected.id, selected.isGalaxy)
                                    }
                                    Toast.makeText(
                                        context,
                                        if (ok) "Defaults sent" else context.getString(R.string.wentwrong),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                        enabled = selectedAppInstalled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Settings, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Send defaults")
                    }
                    TextButton(
                        onClick = { uriHandler.openUri("https://www.juggluco.nl/JugglucoWearOS/intro/index.html") },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.helpname))
                    }
                }
            }
        }
    }
}

@Composable
fun GarminStatusScreen(navController: NavController) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var snapshot by remember { mutableStateOf(WatchInterop.getGarminSnapshot()) }
    var kerfstokDark by rememberSaveable { mutableStateOf(WatchInterop.isKerfstokDarkMode()) }

    fun refreshSnapshot() {
        snapshot = WatchInterop.getGarminSnapshot()
        kerfstokDark = WatchInterop.isKerfstokDarkMode()
    }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text("Garmin status") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { refreshSnapshot() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item("garmin_status") {
                SectionLabel("State", topPadding = 0.dp)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SettingsItem(
                        title = "SDK ready",
                        subtitle = if (snapshot.sdkReady) "Yes" else "No",
                        icon = Icons.Filled.CheckCircle,
                        iconTint = if (snapshot.sdkReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        position = CardPosition.TOP
                    )
                    SettingsItem(
                        title = "Registered",
                        subtitle = if (snapshot.registered) "Yes" else "No",
                        icon = Icons.Filled.Link,
                        iconTint = if (snapshot.registered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        position = CardPosition.MIDDLE
                    )
                    SettingsItem(
                        title = "Last send",
                        subtitle = "${formatEpoch(snapshot.sendTimeMs)} • ${snapshot.sendStatus}",
                        icon = Icons.Filled.Sync,
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        position = CardPosition.MIDDLE
                    )
                    SettingsItem(
                        title = "Last received",
                        subtitle = formatEpoch(snapshot.receivedTimeMs),
                        icon = Icons.Filled.CheckCircle,
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        position = CardPosition.MIDDLE
                    )
                    SettingsItem(
                        title = "Queued messages",
                        subtitle = if (snapshot.waitingQueue) "Waiting" else "Empty",
                        icon = Icons.Filled.Hub,
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        position = CardPosition.BOTTOM
                    )
                }
            }

            item("garmin_actions") {
                SectionLabel("Actions", topPadding = 0.dp)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val ok = WatchInterop.reinitGarmin()
                            Toast.makeText(
                                context,
                                if (ok) "Reinit requested" else context.getString(R.string.wentwrong),
                                Toast.LENGTH_SHORT
                            ).show()
                            refreshSnapshot()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.reinit))
                    }
                    OutlinedButton(
                        onClick = {
                            val ok = WatchInterop.syncGarmin()
                            Toast.makeText(
                                context,
                                if (ok) "Sync requested" else context.getString(R.string.wentwrong),
                                Toast.LENGTH_SHORT
                            ).show()
                            refreshSnapshot()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Sync, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.sync))
                    }
                    OutlinedButton(
                        onClick = {
                            val ok = WatchInterop.sendGarminQueueNext()
                            Toast.makeText(
                                context,
                                if (ok) "Sent next queued message" else context.getString(R.string.wentwrong),
                                Toast.LENGTH_SHORT
                            ).show()
                            refreshSnapshot()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.sendqueue))
                    }
                    SettingsSwitchItem(
                        title = stringResource(R.string.darkmode),
                        subtitle = "Kerfstok watch UI",
                        checked = kerfstokDark,
                        icon = Icons.Filled.Shield,
                        iconTint = MaterialTheme.colorScheme.primary,
                        onCheckedChange = {
                            kerfstokDark = it
                            WatchInterop.setKerfstokDarkMode(it)
                        }
                    )
                    OutlinedButton(
                        onClick = {
                            val ok = WatchInterop.openKerfstokStore(context)
                            if (!ok) {
                                Toast.makeText(context, context.getString(R.string.wentwrong), Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.OpenInNew, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.getkerfstok))
                    }
                    OutlinedButton(
                        onClick = { uriHandler.openUri("https://www.juggluco.nl/Jugglucohelp/garminconfig.html") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Info, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.helpname))
                    }
                }
            }
        }
    }
}

@Composable
fun WebServerSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    var active by rememberSaveable { mutableStateOf(Natives.getusexdripwebserver()) }
    var localOnly by rememberSaveable { mutableStateOf(Natives.getXdripServerLocal()) }
    var apiSecret by rememberSaveable { mutableStateOf(Natives.getApiSecret() ?: "") }
    var showSecret by rememberSaveable { mutableStateOf(false) }
    var sslEnabled by rememberSaveable { mutableStateOf(Natives.getuseSSL()) }
    var sslExpanded by rememberSaveable { mutableStateOf(sslEnabled) }
    var httpPortText by rememberSaveable { mutableStateOf(Natives.getxdripport().toString()) }
    var sslPortText by rememberSaveable { mutableStateOf(Natives.getsslport().toString()) }
    var intervalText by rememberSaveable { mutableStateOf(Natives.getinterval().toString()) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    var certificate by remember { mutableStateOf(WebServerCertificate.installed(context)) }
    var generatingCertificate by remember { mutableStateOf(false) }
    var showCertificateImport by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val privateKeyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val error = WatchInterop.importCertificateFile(context, uri, "privkey.pem")
        if (error != null) {
            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, context.getString(R.string.saved), Toast.LENGTH_SHORT).show()
            WebServerCertificate.clearSelfSignedMarker(context)
            certificate = WebServerCertificate.installed(context)
            if (sslEnabled) {
                val sslError = Natives.setuseSSL(true)
                if (sslError != null) Toast.makeText(context, sslError, Toast.LENGTH_LONG).show()
            }
        }
    }
    val fullChainPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val error = WatchInterop.importCertificateFile(context, uri, "fullchain.pem")
        if (error != null) {
            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, context.getString(R.string.saved), Toast.LENGTH_SHORT).show()
            WebServerCertificate.clearSelfSignedMarker(context)
            certificate = WebServerCertificate.installed(context)
            if (sslEnabled) {
                val sslError = Natives.setuseSSL(true)
                if (sslError != null) Toast.makeText(context, sslError, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun applyCurrentInputs(showErrors: Boolean): Boolean {
        val key = apiSecret.trim()
        if (key.length >= 80) {
            if (showErrors) {
                Toast.makeText(context, "$key${context.getString(R.string.toolongsecret)}80", Toast.LENGTH_LONG).show()
            }
            return false
        }

        val httpPort = httpPortText.toIntOrNull()
        if (httpPort == null) {
            if (showErrors) {
                Toast.makeText(context, "$httpPortText${context.getString(R.string.invalidport)}", Toast.LENGTH_LONG).show()
            }
            return false
        }
        val port = sslPortText.toIntOrNull()
        if (port == null) {
            if (showErrors) {
                Toast.makeText(context, "$sslPortText${context.getString(R.string.invalidport)}", Toast.LENGTH_LONG).show()
            }
            return false
        }
        val receivePort = Natives.getreceiveport().toIntOrNull()
        if (receivePort != null && (port == receivePort || httpPort == receivePort)) {
            if (showErrors) {
                Toast.makeText(context, context.getString(R.string.nomirrorport), Toast.LENGTH_LONG).show()
            }
            return false
        }
        // The two servers listen at the same time, so they cannot share a port.
        // This used to compare against the literal 17580 because that was the
        // only value the HTTP server could ever have.
        if (port == httpPort) {
            if (showErrors) {
                Toast.makeText(context, context.getString(R.string.nohttpport), Toast.LENGTH_LONG).show()
            }
            return false
        }
        if (port !in 1024..65535 || httpPort !in 1024..65535) {
            if (showErrors) {
                Toast.makeText(context, context.getString(R.string.portrange), Toast.LENGTH_LONG).show()
            }
            return false
        }

        val interval = intervalText.toIntOrNull()
        if (interval == null || interval <= 0) {
            if (showErrors) {
                Toast.makeText(context, context.getString(R.string.invalid_interval), Toast.LENGTH_LONG).show()
            }
            return false
        }

        Natives.setApiSecret(key)
        if (port != Natives.getsslport()) {
            Natives.setsslport(port)
        }
        // Rebinds a running server, so only call it when the value moved.
        if (httpPort != Natives.getxdripport()) {
            Natives.setxdripport(httpPort)
        }
        Natives.setinterval(interval)
        Natives.setXdripServerLocal(localOnly)

        if (sslEnabled) {
            val sslError = Natives.setuseSSL(true)
            if (sslError != null) {
                if (showErrors) {
                    Toast.makeText(context, sslError, Toast.LENGTH_LONG).show()
                }
                return false
            }
        }

        return true
    }

    fun buildBaseUrl(host: String): String {
        val scheme = if (sslEnabled) "https" else "http"
        val port = if (sslEnabled) {
            sslPortText.toIntOrNull() ?: Natives.getsslport()
        } else {
            httpPortText.toIntOrNull() ?: Natives.getxdripport()
        }
        val key = apiSecret.trim()
        val keyPrefix = if (key.isEmpty()) "" else "$key/"
        return "$scheme://$host:$port/$keyPrefix"
    }

    /**
     * Writes a fresh self-signed pair, then hands the server back to whatever
     * state it was in. Key generation is slow enough to be visible, so it runs
     * off the main thread and the switch is held disabled meanwhile.
     */
    fun generateCertificate(thenEnableSsl: Boolean) {
        if (generatingCertificate) return
        generatingCertificate = true
        scope.launch {
            val error = withContext(Dispatchers.IO) { WebServerCertificate.generate(context) }
            certificate = WebServerCertificate.installed(context)
            generatingCertificate = false
            if (error != null) {
                Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                return@launch
            }
            Toast.makeText(context, context.getString(R.string.webserver_cert_created), Toast.LENGTH_SHORT).show()
            // A running server holds the old certificate open; re-enabling is
            // what makes it read the new one.
            if (thenEnableSsl || sslEnabled) {
                val sslError = Natives.setuseSSL(true)
                if (sslError != null) {
                    Toast.makeText(context, sslError, Toast.LENGTH_LONG).show()
                } else {
                    sslEnabled = true
                }
            }
        }
    }

    fun setSslEnabled(enabled: Boolean) {
        // Turning HTTPS on used to fail unless the user had already side-loaded
        // two PEM files. Nothing about wanting encrypted traffic implies owning
        // a certificate, so make one.
        if (enabled && !WebServerCertificate.hasCertificate(context)) {
            sslExpanded = true
            generateCertificate(thenEnableSsl = true)
            return
        }
        val err = Natives.setuseSSL(enabled)
        if (err != null) {
            Toast.makeText(context, err, Toast.LENGTH_LONG).show()
        } else {
            sslEnabled = enabled
            if (enabled) {
                sslExpanded = true
            }
        }
    }

    fun openUrl(url: String) {
        if (!applyCurrentInputs(showErrors = true)) return
        uriHandler.openUri(url)
    }

    fun shareUrl(url: String) {
        if (!applyCurrentInputs(showErrors = true)) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.sendto)))
    }

    val localBaseUrl = remember(apiSecret, sslEnabled, sslPortText, httpPortText) { buildBaseUrl("127.0.0.1") }
    val lanHost = remember(apiSecret, sslEnabled, sslPortText, httpPortText) { findLocalIpv4Address() }
    val lanBaseUrl = remember(lanHost, apiSecret, sslEnabled, sslPortText, httpPortText) {
        lanHost?.let { buildBaseUrl(it) }
    }
    val primaryUrl = if (localOnly) localBaseUrl else (lanBaseUrl ?: localBaseUrl)
    val rootUrl = primaryUrl
    val currentUrl = remember(primaryUrl) { "${primaryUrl}api/v1/entries/current" }
    val entriesUrl = remember(primaryUrl) { "${primaryUrl}api/v1/entries?count=36" }
    val reportUrl = remember(primaryUrl) { "${primaryUrl}x/report" }
    val childEnabled = active
    val childAlpha = if (childEnabled) 1f else 0.58f

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.webserver)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { showHelp = true }) {
                        Icon(Icons.Filled.Info, contentDescription = stringResource(R.string.helpname))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item("web_master") {
                MasterSwitchCard(
                    title = stringResource(R.string.active),
                    subtitle = if (active) "Web server is running" else "Web server is paused",
                    checked = active,
                    onCheckedChange = {
                        active = it
                        Natives.setusexdripwebserver(it)
                    },
                    icon = Icons.Filled.Language
                )
            }

            item("web_secret") {
                OutlinedTextField(
                    value = apiSecret,
                    onValueChange = {
                        apiSecret = it
                        if (it.trim().length < 80) {
                            Natives.setApiSecret(it.trim())
                        }
                    },
                    enabled = childEnabled,
                    label = { Text(stringResource(R.string.secret)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(childAlpha),
                    visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { applyCurrentInputs(showErrors = true) }
                    ),
                    trailingIcon = {
                        IconButton(enabled = childEnabled, onClick = { showSecret = !showSecret }) {
                            Icon(
                                imageVector = if (showSecret) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = null
                            )
                        }
                    }
                )
            }

            item("web_network_group") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(childAlpha),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    SettingsSwitchItem(
                        title = stringResource(R.string.localonly),
                        subtitle = "Restrict server to localhost",
                        checked = localOnly,
                        icon = Icons.Filled.Devices,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        position = CardPosition.TOP,
                        enabled = childEnabled,
                        onCheckedChange = {
                            localOnly = it
                            Natives.setXdripServerLocal(it)
                        }
                    )

                    SettingsItem(
                        title = stringResource(R.string.port),
                        icon = Icons.Filled.SettingsEthernet,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        position = CardPosition.MIDDLE,
                        trailingContent = {
                            OutlinedTextField(
                                value = httpPortText,
                                onValueChange = { httpPortText = it },
                                enabled = childEnabled,
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = { applyCurrentInputs(showErrors = true) }
                                ),
                                modifier = Modifier
                                    .width(96.dp)
                                    .height(52.dp)
                            )
                        }
                    )

                    SettingsItem(
                        title = stringResource(R.string.usessl),
                        subtitle = stringResource(R.string.webserver_https_subtitle),
                        icon = Icons.Filled.Lock,
                        iconTint = MaterialTheme.colorScheme.primary,
                        position = CardPosition.MIDDLE,
                        onClick = if (childEnabled) ({ sslExpanded = !sslExpanded }) else null,
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (sslExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(10.dp))
                                StyledSwitch(
                                    checked = sslEnabled,
                                    onCheckedChange = if (childEnabled) ({ setSslEnabled(it) }) else null,
                                    enabled = childEnabled
                                )
                            }
                        }
                    )

                    AnimatedVisibility(visible = sslExpanded) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = cardShape(CardPosition.MIDDLE),
                            color = MaterialTheme.colorScheme.surfaceContainer
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.webserver_certificate),
                                    style = MaterialTheme.typography.labelLarge
                                )
                                val installed = certificate
                                Text(
                                    text = when {
                                        generatingCertificate ->
                                            stringResource(R.string.webserver_cert_generating)
                                        installed == null ->
                                            stringResource(R.string.webserver_cert_none)
                                        installed.selfSigned ->
                                            stringResource(R.string.webserver_cert_selfsigned)
                                        else -> stringResource(R.string.webserver_cert_custom)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (installed != null && !generatingCertificate) {
                                    val expired = installed.isExpired(System.currentTimeMillis())
                                    Text(
                                        text = if (expired) {
                                            stringResource(R.string.webserver_cert_expired)
                                        } else {
                                            stringResource(
                                                R.string.webserver_cert_expires,
                                                formatEpoch(installed.notAfterMillis)
                                            )
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (expired) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                    // DHCP moves phones. A certificate that was
                                    // right when it was written stops matching
                                    // the address people are typing, and the
                                    // browser blames the name rather than the
                                    // lease.
                                    val uncovered = lanHost
                                        ?.takeIf { !localOnly && !installed.covers(it) }
                                    if (uncovered != null) {
                                        Text(
                                            text = stringResource(
                                                R.string.webserver_cert_missing_address,
                                                uncovered
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilledTonalButton(
                                        onClick = { generateCertificate(thenEnableSsl = false) },
                                        enabled = childEnabled && !generatingCertificate,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Filled.Autorenew, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            stringResource(
                                                if (installed == null) {
                                                    R.string.webserver_cert_generate
                                                } else {
                                                    R.string.webserver_cert_regenerate
                                                }
                                            )
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = { showCertificateImport = true },
                                        enabled = childEnabled && !generatingCertificate,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Filled.Shield, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.webserver_cert_import))
                                    }
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = "SSL ${context.getString(R.string.port)}",
                                        style = MaterialTheme.typography.labelLarge,
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = sslPortText,
                                        onValueChange = { sslPortText = it },
                                        enabled = childEnabled,
                                        singleLine = true,
                                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                            keyboardType = KeyboardType.Number,
                                            imeAction = ImeAction.Done
                                        ),
                                        keyboardActions = KeyboardActions(
                                            onDone = { applyCurrentInputs(showErrors = true) }
                                        ),
                                        modifier = Modifier
                                            .width(96.dp)
                                            .height(52.dp)
                                    )
                                }
                            }
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(if (childEnabled) 1f else 0.6f),
                        shape = cardShape(CardPosition.BOTTOM),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                modifier = Modifier
                                    .width(40.dp)
                                    .height(40.dp),
                                shape = cardShape(CardPosition.SINGLE),
                                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Filled.AccessTime,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = stringResource(R.string.interval),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = intervalText,
                                onValueChange = { intervalText = it },
                                enabled = childEnabled,
                                modifier = Modifier
                                    .width(82.dp)
                                    .height(50.dp),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = { applyCurrentInputs(showErrors = true) }
                                )
                            )
                        }
                    }
                }
            }

            item("web_url_card") {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(childAlpha),
                    shape = cardShape(CardPosition.SINGLE),
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = rootUrl,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (childEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.clickable(enabled = childEnabled) { openUrl(rootUrl) }
                                )
                                if (!localOnly && lanBaseUrl == null) {
                                    Text(
                                        text = "Wi-Fi IP unavailable, using loopback URL.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                } else if (!localOnly) {
                                    Text(
                                        text = "Loopback: $localBaseUrl",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            IconButton(
                                enabled = childEnabled,
                                onClick = { shareUrl(rootUrl) }
                            ) {
                                Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.sendto))
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { openUrl(currentUrl) },
                                enabled = childEnabled,
                                modifier = Modifier.weight(1f)
                            ) { Text("Current") }
                            OutlinedButton(
                                onClick = { openUrl(entriesUrl) },
                                enabled = childEnabled,
                                modifier = Modifier.weight(1f)
                            ) { Text("Entries") }
                            OutlinedButton(
                                onClick = { openUrl(reportUrl) },
                                enabled = childEnabled,
                                modifier = Modifier.weight(1f)
                            ) { Text("Report") }
                        }
                    }
                }
            }
        }
    }
    if (showCertificateImport) {
        AlertDialog(
            onDismissRequest = { showCertificateImport = false },
            title = { Text(stringResource(R.string.webserver_cert_import)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.webserver_cert_import_hint),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedButton(
                        onClick = { privateKeyPicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Key, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.privatekey))
                    }
                    OutlinedButton(
                        onClick = { fullChainPicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Shield, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.fullchain))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCertificateImport = false }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }
    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text(stringResource(R.string.webserver)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Nightscout-compatible endpoints are served directly from this phone.")
                    Text("Base URL exposes api/v1/entries/current, api/v1/entries, and x/report paths.")
                    Text("Use Local only for same-device loopback testing. Disable it for LAN.")
                    Text("Enable Use SSL and the app writes its own certificate. Import one instead if you have a CA-issued certificate.")
                }
            },
            confirmButton = {
                TextButton(onClick = { showHelp = false }) {
                    Text(stringResource(R.string.close))
                }
            },
            dismissButton = {
                TextButton(onClick = { uriHandler.openUri("https://www.juggluco.nl/Juggluco/webserver.html") }) {
                    Text(stringResource(R.string.helpname))
                }
            }
        )
    }
}

@Composable
private fun InAppHelpDialog(
    title: String,
    lines: List<String>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                lines.forEach { line ->
                    Text(
                        text = "• $line",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

private fun findLocalIpv4Address(): String? {
    return try {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        interfaces
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { Collections.list(it.inetAddresses).asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull()
            ?.hostAddress
    } catch (_: Throwable) {
        null
    }
}

private fun formatEpoch(epochMs: Long): String {
    if (epochMs <= 0L) return "Never"
    return try {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMs))
    } catch (_: Throwable) {
        "Never"
    }
}
