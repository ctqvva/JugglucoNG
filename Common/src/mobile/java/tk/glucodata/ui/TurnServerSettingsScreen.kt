@file:OptIn(ExperimentalMaterial3Api::class)

package tk.glucodata.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import tk.glucodata.Natives
import tk.glucodata.R
import tk.glucodata.CloneIceNetworkConfig
import tk.glucodata.CloneIceNetworkConfigStore
import tk.glucodata.ui.components.*

@Composable
fun TurnServerSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val isAbsent = Natives.TurnServerNR() == 0
    val initialIceConfig = remember { CloneIceNetworkConfigStore.load(context) }

    var host by remember { mutableStateOf(if (isAbsent) "" else Natives.getTurnHost(0) ?: "") }
    var user by remember { mutableStateOf(if (isAbsent) "" else Natives.getTurnUser(0) ?: "") }
    var password by remember { mutableStateOf(if (isAbsent) "" else Natives.getTurnPassword(0) ?: "") }
    var port by remember { mutableStateOf(if (isAbsent) "3478" else Natives.getTurnPort(0).toString()) }
    var passwordVisible by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(isAbsent) }
    var useTurnForStun by remember {
        mutableStateOf(if (isAbsent) true else initialIceConfig.useTurnForStun)
    }
    var rendezvousHost by remember { mutableStateOf(initialIceConfig.rendezvousHost) }
    var rendezvousPort by remember { mutableStateOf(initialIceConfig.rendezvousPort.toString()) }
    var useCustomRendezvous by remember {
        mutableStateOf(initialIceConfig.rendezvousHost.isNotEmpty())
    }
    var verifyRendezvousCertificate by remember {
        mutableStateOf(initialIceConfig.verifyRendezvousCertificate)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.turnserver)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Help toggle
            SettingsItem(
                title = "What is TURN?",
                subtitle = if (showHelp) "Tap to hide" else "Tap to learn more",
                icon = Icons.Filled.HelpOutline,
                iconTint = MaterialTheme.colorScheme.tertiary,
                position = CardPosition.SINGLE,
                onClick = { showHelp = !showHelp }
            )

            AnimatedVisibility(visible = showHelp, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    shape = cardShape(CardPosition.SINGLE),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        HelpBlock("How it works", "When two devices connect using ICE, they try direct peer-to-peer first. If firewalls block this, a TURN server relays data between them.")
                        HelpBlock("Setup options",
                            "Self-hosted: Install coturn on a VPS. Port 3478, UDP.\n" +
                            "Free tier: metered.ca offers free TURN.\n" +
                            "Both devices must share the same TURN config."
                        )
                    }
                }
            }

            // Server
            SectionLabel("Server")
            OutlinedTextField(
                value = host, onValueChange = { host = it },
                label = { Text(stringResource(R.string.hostname)) },
                supportingText = { Text("e.g. turn.myserver.com or 203.0.113.5") },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            OutlinedTextField(
                value = port, onValueChange = { port = it },
                label = { Text(stringResource(R.string.port)) },
                supportingText = { Text(stringResource(R.string.turn_udp_port_default)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            // Credentials
            SectionLabel("Credentials")
            OutlinedTextField(
                value = user, onValueChange = { user = it },
                label = { Text(stringResource(R.string.username)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text(stringResource(R.string.password)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, contentDescription = null)
                    }
                }
            )

            SectionLabel(stringResource(R.string.clone_ice_services))
            SettingsSwitchItem(
                title = stringResource(R.string.use_turn_for_stun),
                subtitle = stringResource(R.string.use_turn_for_stun_summary),
                checked = useTurnForStun,
                onCheckedChange = { useTurnForStun = it },
                position = CardPosition.SINGLE,
            )
            Spacer(Modifier.height(8.dp))
            SettingsSwitchItem(
                title = stringResource(R.string.use_custom_rendezvous_server),
                subtitle = stringResource(R.string.use_custom_rendezvous_server_summary),
                checked = useCustomRendezvous,
                onCheckedChange = { useCustomRendezvous = it },
                position = CardPosition.SINGLE,
            )
            AnimatedVisibility(
                visible = useCustomRendezvous,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = rendezvousHost,
                        onValueChange = { rendezvousHost = it },
                        label = { Text(stringResource(R.string.rendezvous_server)) },
                        supportingText = { Text(stringResource(R.string.rendezvous_host_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = rendezvousPort,
                        onValueChange = { rendezvousPort = it },
                        label = { Text(stringResource(R.string.rendezvous_port)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.verify_rendezvous_certificate),
                        subtitle = stringResource(R.string.verify_rendezvous_certificate_summary),
                        checked = verifyRendezvousCertificate,
                        onCheckedChange = { verifyRendezvousCertificate = it },
                        position = CardPosition.SINGLE,
                    )
                }
            }

            // Actions
            Spacer(Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!isAbsent) {
                    OutlinedButton(
                        onClick = {
                            Natives.deleteTurnServer(0)
                            val currentIceConfig = CloneIceNetworkConfigStore.load(context)
                            if (currentIceConfig.useTurnForStun) {
                                CloneIceNetworkConfigStore.save(
                                    context,
                                    currentIceConfig.copy(useTurnForStun = false),
                                )
                            }
                            Natives.resetnetwork()
                            tk.glucodata.Applic.wakemirrors()
                            navController.popBackStack()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text(stringResource(R.string.delete)) }
                }
                Button(
                    onClick = {
                        val cleanTurnHost = host.trim()
                        val cleanRendezvousHost = if (useCustomRendezvous) {
                            rendezvousHost.trim()
                        } else {
                            ""
                        }
                        val portNum = port.toIntOrNull()
                        val rendezvousPortNum = if (cleanRendezvousHost.isEmpty()) {
                            CloneIceNetworkConfig.DEFAULT_RENDEZVOUS_PORT
                        } else {
                            rendezvousPort.toIntOrNull()
                        }
                        if (portNum == null || portNum !in 1..65535 || rendezvousPortNum == null ||
                            rendezvousPortNum !in 1..65535) {
                            Toast.makeText(context, context.getString(R.string.portrange), Toast.LENGTH_LONG).show()
                            return@Button
                        }
                        if (cleanTurnHost.length > 191 || cleanRendezvousHost.length > CloneIceNetworkConfig.MAX_HOST_LENGTH) {
                            Toast.makeText(context, context.getString(R.string.mirror_host_error_hostname_too_long), Toast.LENGTH_LONG).show()
                            return@Button
                        }
                        if (useTurnForStun && cleanTurnHost.isEmpty()) {
                            Toast.makeText(context, context.getString(R.string.turn_required_for_stun), Toast.LENGTH_LONG).show()
                            return@Button
                        }
                        if (useCustomRendezvous && cleanRendezvousHost.isEmpty()) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.rendezvous_host_required),
                                Toast.LENGTH_LONG,
                            ).show()
                            return@Button
                        }
                        val previousTurn = if (Natives.TurnServerNR() > 0) {
                            arrayOf(
                                Natives.getTurnHost(0).orEmpty(),
                                Natives.getTurnPort(0).toString(),
                                Natives.getTurnUser(0).orEmpty(),
                                Natives.getTurnPassword(0).orEmpty(),
                            )
                        } else null
                        Natives.setTurnServer(0, cleanTurnHost, portNum, user, password)
                        val iceSaved = CloneIceNetworkConfigStore.save(
                            context,
                            CloneIceNetworkConfig(
                                rendezvousHost = cleanRendezvousHost,
                                rendezvousPort = rendezvousPortNum,
                                useTurnForStun = useTurnForStun,
                                verifyRendezvousCertificate = !useCustomRendezvous ||
                                    verifyRendezvousCertificate,
                            ),
                        )
                        if (!iceSaved) {
                            if (previousTurn == null) {
                                Natives.deleteTurnServer(0)
                            } else {
                                Natives.setTurnServer(
                                    0,
                                    previousTurn[0],
                                    previousTurn[1].toInt(),
                                    previousTurn[2],
                                    previousTurn[3],
                                )
                            }
                            Toast.makeText(context, context.getString(R.string.savefailed), Toast.LENGTH_LONG).show()
                            return@Button
                        }
                        Natives.resetnetwork()
                        tk.glucodata.Applic.wakemirrors()
                        navController.popBackStack()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.save)) }
            }
        }
    }
}

@Composable
private fun HelpBlock(title: String, body: String) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f))
    }
}
