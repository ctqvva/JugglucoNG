@file:OptIn(ExperimentalMaterial3Api::class)

package tk.glucodata.ui

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import tk.glucodata.CloneIceNetworkConfig
import tk.glucodata.R
import tk.glucodata.ui.components.*

internal data class TurnEndpoint(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
)

/** Presentation only: rendering or opening an editor never touches native networking. */
@Composable
internal fun HybridSettingsContent(
    config: CloneIceNetworkConfig,
    turn: TurnEndpoint?,
    onBack: () -> Unit,
    onLocalDiscovery: (Boolean) -> Unit,
    onTurnForStun: (Boolean) -> Unit,
    onSaveTurn: (TurnEndpoint?) -> Boolean,
    onSaveRendezvous: (String, Int, Boolean) -> Boolean,
) {
    var showHelp by rememberSaveable { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.tertiary
    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.clone_network_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.navigate_back))
                    }
                },
                actions = {
                    IconButton(onClick = { showHelp = true }) {
                        Icon(Icons.Default.HelpOutline, stringResource(R.string.help))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SectionLabel(stringResource(R.string.clone_servers), topPadding = 8.dp)
            TurnServerSetting(endpoint = turn, onCommit = onSaveTurn, accent = accent)
            Spacer(Modifier.height(2.dp))
            RendezvousServerSetting(config = config, onCommit = onSaveRendezvous, accent = accent)

            SectionLabel(stringResource(R.string.clone_connection_options))
            SettingsSwitchItem(
                title = stringResource(R.string.clone_local_short),
                subtitle = stringResource(R.string.clone_local_short_desc),
                icon = Icons.Default.Lan,
                iconTint = accent,
                checked = config.useLocalDiscovery,
                onCheckedChange = onLocalDiscovery,
                position = CardPosition.TOP,
            )
            Spacer(Modifier.height(2.dp))
            SettingsSwitchItem(
                title = stringResource(R.string.clone_stun_short),
                subtitle = stringResource(if (turn == null) R.string.clone_stun_needs_server else R.string.clone_stun_short_desc),
                icon = Icons.Default.Hub,
                iconTint = accent,
                checked = config.useTurnForStun,
                enabled = turn != null,
                onCheckedChange = onTurnForStun,
                position = CardPosition.BOTTOM,
            )
            Text(
                stringResource(R.string.clone_switches_apply),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            )
        }
    }
    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            icon = { Icon(Icons.Default.Hub, null) },
            title = { Text(stringResource(R.string.clone_network_title)) },
            text = {
                Text(
                    stringResource(R.string.turn_help_how_body),
                    Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text(stringResource(R.string.ok)) } },
        )
    }
}

/**
 * Both servers are the same choice -- use your own instead of the app's -- so
 * they are the same control. Verify sits outside the card as a row of its own:
 * it is a separate decision about the server you just named, not one of its
 * fields.
 */
@Composable
private fun ColumnScope.RendezvousServerSetting(
    config: CloneIceNetworkConfig,
    onCommit: (String, Int, Boolean) -> Boolean,
    accent: androidx.compose.ui.graphics.Color,
) {
    var custom by rememberSaveable { mutableStateOf(config.rendezvousHost.isNotEmpty()) }
    var host by rememberSaveable { mutableStateOf(config.rendezvousHost) }
    var port by rememberSaveable { mutableStateOf(config.rendezvousPort.toString()) }
    var verify by rememberSaveable { mutableStateOf(config.verifyRendezvousCertificate) }
    val validPort = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val validHost = host.isNotBlank() && host.trim().length <= CloneIceNetworkConfig.MAX_HOST_LENGTH

    fun commit() {
        if (!custom) {
            onCommit("", CloneIceNetworkConfig.DEFAULT_RENDEZVOUS_PORT, true)
        } else if (validHost && validPort != null) {
            onCommit(host.trim(), validPort, verify)
        }
    }

    DisclosingSwitchCard(
        title = stringResource(R.string.use_custom_rendezvous_server),
        subtitle = formatNetworkEndpoint(config.rendezvousHost, config.rendezvousPort)
            ?: stringResource(R.string.clone_server_default),
        icon = Icons.Default.Dns,
        iconTint = accent,
        checked = custom,
        onCheckedChange = { custom = it; commit() },
        position = if (custom) CardPosition.MIDDLE else CardPosition.BOTTOM,
    ) {
        CommittingTextField(
            value = host,
            onValueChange = { host = it },
            label = stringResource(R.string.hostname),
            isError = host.isNotEmpty() && !validHost,
            onCommit = ::commit,
        )
        CommittingTextField(
            value = port,
            onValueChange = { port = it },
            label = stringResource(R.string.port),
            isError = validPort == null,
            keyboardType = KeyboardType.Number,
            onCommit = ::commit,
        )
    }
    AnimatedVisibility(
        visible = custom,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Column {
            Spacer(Modifier.height(2.dp))
            SettingsSwitchItem(
                title = stringResource(R.string.verify_rendezvous_certificate),
                subtitle = if (verify) null else stringResource(R.string.verify_rendezvous_certificate_summary),
                subtitleStyle = if (verify) null else MaterialTheme.typography.bodySmall,
                icon = Icons.Default.VerifiedUser,
                iconTint = if (verify) accent else MaterialTheme.colorScheme.error,
                checked = verify,
                onCheckedChange = { verify = it; commit() },
                position = CardPosition.BOTTOM,
            )
        }
    }
}

@Composable
private fun TurnServerSetting(
    endpoint: TurnEndpoint?,
    onCommit: (TurnEndpoint?) -> Boolean,
    accent: androidx.compose.ui.graphics.Color,
) {
    var custom by rememberSaveable { mutableStateOf(endpoint != null) }
    var host by rememberSaveable { mutableStateOf(endpoint?.host.orEmpty()) }
    var port by rememberSaveable { mutableStateOf((endpoint?.port ?: 3478).toString()) }
    var user by rememberSaveable { mutableStateOf(endpoint?.username.orEmpty()) }
    var password by rememberSaveable { mutableStateOf(endpoint?.password.orEmpty()) }
    var visible by rememberSaveable { mutableStateOf(false) }
    val validPort = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val validHost = TurnServerInputPolicy.fitsNativeBuffer(host.trim(), TurnServerInputPolicy.HOST_BYTES)
    val validUser = TurnServerInputPolicy.fitsNativeBuffer(user, TurnServerInputPolicy.USERNAME_BYTES)
    val validPassword = TurnServerInputPolicy.fitsNativeBuffer(password, TurnServerInputPolicy.PASSWORD_BYTES)

    fun commit() {
        if (!custom || host.isBlank()) {
            if (endpoint != null) onCommit(null)
        } else if (validPort != null && validHost && validUser && validPassword) {
            onCommit(TurnEndpoint(host.trim(), validPort, user, password))
        }
    }

    DisclosingSwitchCard(
        title = stringResource(R.string.use_custom_turn_server),
        subtitle = endpoint?.let { formatNetworkEndpoint(it.host, it.port) }
            ?: stringResource(R.string.mirror_app_turn_server),
        icon = Icons.Default.CloudQueue,
        iconTint = accent,
        checked = custom,
        onCheckedChange = { custom = it; commit() },
        position = CardPosition.TOP,
    ) {
        CommittingTextField(
            value = host,
            onValueChange = { host = it },
            label = stringResource(R.string.hostname),
            placeholder = "turn.example.org",
            isError = !validHost,
            onCommit = ::commit,
        )
        CommittingTextField(
            value = port,
            onValueChange = { port = it },
            label = stringResource(R.string.port),
            isError = validPort == null,
            keyboardType = KeyboardType.Number,
            onCommit = ::commit,
        )
        CommittingTextField(
            value = user,
            onValueChange = { user = it },
            label = stringResource(R.string.username),
            isError = !validUser,
            onCommit = ::commit,
        )
        CommittingTextField(
            value = password,
            onValueChange = { password = it },
            label = stringResource(R.string.password),
            isError = !validPassword,
            onCommit = ::commit,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        stringResource(if (visible) R.string.hide_password else R.string.show_password),
                    )
                }
            },
        )
        if (!validHost || !validUser || !validPassword) {
            Text(
                stringResource(if (!validHost) R.string.mirror_host_error_hostname_too_long else R.string.turn_credentials_too_long),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Nothing on this page has a Save, so a field writes its value when it loses
 * focus rather than on every keystroke -- half a hostname is not a hostname.
 */
@Composable
private fun CommittingTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    onCommit: () -> Unit,
    isError: Boolean = false,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    var wasFocused by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { state ->
                if (wasFocused && !state.isFocused) onCommit()
                wasFocused = state.isFocused
            },
    )
}
