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

/**
 * Everything the screen edits, as the user typed it. Nothing here reaches native or the
 * store until [HybridSettingsContent]'s Apply, so a field half-typed or a switch flipped
 * and flipped back costs no reconnection. The port fields stay strings on purpose: a
 * draft has to be able to hold "" while the user is typing.
 */
internal data class HybridDraft(
    val customTurn: Boolean,
    val turnHost: String,
    val turnPort: String,
    val turnUser: String,
    val turnPassword: String,
    val customRendezvous: Boolean,
    val rendezvousHost: String,
    val rendezvousPort: String,
    val verifyRendezvousCertificate: Boolean,
    val useLocalDiscovery: Boolean,
    val useTurnForStun: Boolean,
    val preferIPv4: Boolean,
) {
    val turnPortValue: Int? get() = turnPort.toIntOrNull()?.takeIf { it in 1..65535 }
    val rendezvousPortValue: Int? get() = rendezvousPort.toIntOrNull()?.takeIf { it in 1..65535 }

    val turnHostValid: Boolean get() = TurnServerInputPolicy.fitsNativeBuffer(turnHost.trim(), TurnServerInputPolicy.HOST_BYTES)
    val turnUserValid: Boolean get() = TurnServerInputPolicy.fitsNativeBuffer(turnUser, TurnServerInputPolicy.USERNAME_BYTES)
    val turnPasswordValid: Boolean get() = TurnServerInputPolicy.fitsNativeBuffer(turnPassword, TurnServerInputPolicy.PASSWORD_BYTES)
    val rendezvousHostValid: Boolean
        get() = rendezvousHost.isNotBlank() && rendezvousHost.trim().length <= CloneIceNetworkConfig.MAX_HOST_LENGTH

    /** The TURN endpoint this draft describes, or null for the app's own. */
    val turnEndpoint: TurnEndpoint?
        get() = if (customTurn && turnHost.isNotBlank()) {
            turnPortValue?.let { TurnEndpoint(turnHost.trim(), it, turnUser, turnPassword) }
        } else {
            null
        }

    /** The network config this draft describes, over [base] for what the screen does not edit. */
    fun toConfig(base: CloneIceNetworkConfig): CloneIceNetworkConfig = base.copy(
        rendezvousHost = if (customRendezvous) rendezvousHost.trim() else "",
        rendezvousPort = if (customRendezvous) rendezvousPortValue ?: CloneIceNetworkConfig.DEFAULT_RENDEZVOUS_PORT else CloneIceNetworkConfig.DEFAULT_RENDEZVOUS_PORT,
        verifyRendezvousCertificate = if (customRendezvous) verifyRendezvousCertificate else true,
        useLocalDiscovery = useLocalDiscovery,
        useTurnForStun = useTurnForStun && turnEndpoint != null,
        preferIPv4 = preferIPv4,
    )

    /** Whether this draft names a different TURN server than [saved]. */
    fun turnChanged(saved: HybridDraft): Boolean = turnEndpoint != saved.turnEndpoint

    /** Whether this draft names a different rendezvous server, or verifies it differently. */
    fun rendezvousChanged(saved: HybridDraft): Boolean {
        val mine = toConfig(CloneIceNetworkConfig())
        val theirs = saved.toConfig(CloneIceNetworkConfig())
        return mine.rendezvousHost != theirs.rendezvousHost ||
            mine.rendezvousPort != theirs.rendezvousPort ||
            mine.verifyRendezvousCertificate != theirs.verifyRendezvousCertificate
    }

    /** The switches, which never cost a connection and are saved as they are flipped. */
    fun switchesChanged(saved: HybridDraft): Boolean =
        useLocalDiscovery != saved.useLocalDiscovery ||
            useTurnForStun != saved.useTurnForStun ||
            preferIPv4 != saved.preferIPv4

    val turnValid: Boolean
        get() = !customTurn || (turnHost.isNotBlank() && turnHostValid && turnPortValue != null && turnUserValid && turnPasswordValid)
    val rendezvousValid: Boolean
        get() = !customRendezvous || (rendezvousHostValid && rendezvousPortValue != null)

    companion object {
        fun of(config: CloneIceNetworkConfig, turn: TurnEndpoint?): HybridDraft = HybridDraft(
            customTurn = turn != null,
            turnHost = turn?.host.orEmpty(),
            turnPort = (turn?.port ?: 3478).toString(),
            turnUser = turn?.username.orEmpty(),
            turnPassword = turn?.password.orEmpty(),
            customRendezvous = config.rendezvousHost.isNotEmpty(),
            rendezvousHost = config.rendezvousHost,
            rendezvousPort = config.rendezvousPort.toString(),
            verifyRendezvousCertificate = config.verifyRendezvousCertificate,
            useLocalDiscovery = config.useLocalDiscovery,
            useTurnForStun = config.useTurnForStun,
            preferIPv4 = config.preferIPv4,
        )
    }
}

/**
 * Presentation only: nothing here touches native networking. The screen edits a [draft]
 * that the owner saves on its own; the one thing a user has to ask for is rebuilding a
 * live connection through a server they just changed, and that is the only time a
 * button appears -- inside the server it belongs to.
 */
@Composable
internal fun HybridSettingsContent(
    draft: HybridDraft,
    saved: HybridDraft,
    liveConnection: Boolean,
    onDraftChange: (HybridDraft) -> Unit,
    onApplyTurn: () -> Unit,
    onApplyRendezvous: () -> Unit,
    onBack: () -> Unit,
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
            TurnServerSetting(
                draft = draft,
                saved = saved,
                onDraftChange = onDraftChange,
                showApply = liveConnection && draft.turnValid && draft.turnChanged(saved),
                onApply = onApplyTurn,
                accent = accent,
            )
            Spacer(Modifier.height(2.dp))
            RendezvousServerSetting(
                draft = draft,
                saved = saved,
                onDraftChange = onDraftChange,
                showApply = liveConnection && draft.rendezvousValid && draft.rendezvousChanged(saved),
                onApply = onApplyRendezvous,
                accent = accent,
            )

            SectionLabel(stringResource(R.string.clone_connection_options))
            SettingsSwitchItem(
                title = stringResource(R.string.clone_local_short),
                subtitle = stringResource(R.string.clone_local_short_desc),
                icon = Icons.Default.Lan,
                iconTint = accent,
                checked = draft.useLocalDiscovery,
                onCheckedChange = { onDraftChange(draft.copy(useLocalDiscovery = it)) },
                position = CardPosition.TOP,
            )
            Spacer(Modifier.height(2.dp))
            val turnAvailable = draft.turnEndpoint != null
            SettingsSwitchItem(
                title = stringResource(R.string.clone_stun_short),
                subtitle = stringResource(if (turnAvailable) R.string.clone_stun_short_desc else R.string.clone_stun_needs_server),
                icon = Icons.Default.Hub,
                iconTint = accent,
                checked = draft.useTurnForStun && turnAvailable,
                enabled = turnAvailable,
                onCheckedChange = { onDraftChange(draft.copy(useTurnForStun = it)) },
                position = CardPosition.MIDDLE,
            )
            Spacer(Modifier.height(2.dp))
            SettingsSwitchItem(
                title = stringResource(R.string.clone_prefer_ipv4),
                subtitle = stringResource(R.string.clone_prefer_ipv4_summary),
                icon = Icons.Default.SettingsEthernet,
                iconTint = accent,
                checked = draft.preferIPv4,
                onCheckedChange = { onDraftChange(draft.copy(preferIPv4 = it)) },
                position = CardPosition.BOTTOM,
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
 * The one action on this screen. It exists only while pressing it does something the
 * screen would not do on its own: the server above was changed, and a Clone route is up
 * that would otherwise keep using the old one until it next reconnected.
 */
@Composable
private fun ApplyRow(visible: Boolean, onApply: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(onClick = onApply) {
                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.clone_apply))
            }
        }
    }
}

/**
 * Both servers are the same choice -- use your own instead of the app's -- so they are
 * the same control. Verify sits outside the card as a row of its own: it is a separate
 * decision about the server you just named, not one of its fields.
 */
@Composable
private fun ColumnScope.RendezvousServerSetting(
    draft: HybridDraft,
    saved: HybridDraft,
    onDraftChange: (HybridDraft) -> Unit,
    showApply: Boolean,
    onApply: () -> Unit,
    accent: androidx.compose.ui.graphics.Color,
) {
    DisclosingSwitchCard(
        title = stringResource(R.string.use_custom_rendezvous_server),
        subtitle = formatNetworkEndpoint(saved.toConfig(CloneIceNetworkConfig()).rendezvousHost, saved.rendezvousPortValue ?: CloneIceNetworkConfig.DEFAULT_RENDEZVOUS_PORT)
            ?: stringResource(R.string.clone_server_default),
        icon = Icons.Default.Dns,
        iconTint = accent,
        checked = draft.customRendezvous,
        onCheckedChange = { onDraftChange(draft.copy(customRendezvous = it)) },
        position = if (draft.customRendezvous) CardPosition.MIDDLE else CardPosition.BOTTOM,
    ) {
        DraftTextField(
            value = draft.rendezvousHost,
            onValueChange = { onDraftChange(draft.copy(rendezvousHost = it)) },
            label = stringResource(R.string.hostname),
            isError = draft.rendezvousHost.isNotEmpty() && !draft.rendezvousHostValid,
        )
        DraftTextField(
            value = draft.rendezvousPort,
            onValueChange = { onDraftChange(draft.copy(rendezvousPort = it)) },
            label = stringResource(R.string.port),
            isError = draft.rendezvousPortValue == null,
            keyboardType = KeyboardType.Number,
        )
        ApplyRow(visible = showApply, onApply = onApply)
    }
    AnimatedVisibility(
        visible = draft.customRendezvous,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Column {
            Spacer(Modifier.height(2.dp))
            val verify = draft.verifyRendezvousCertificate
            SettingsSwitchItem(
                title = stringResource(R.string.verify_rendezvous_certificate),
                subtitle = if (verify) null else stringResource(R.string.verify_rendezvous_certificate_summary),
                subtitleStyle = if (verify) null else MaterialTheme.typography.bodySmall,
                icon = Icons.Default.VerifiedUser,
                iconTint = if (verify) accent else MaterialTheme.colorScheme.error,
                checked = verify,
                onCheckedChange = { onDraftChange(draft.copy(verifyRendezvousCertificate = it)) },
                position = CardPosition.BOTTOM,
            )
        }
    }
}

@Composable
private fun TurnServerSetting(
    draft: HybridDraft,
    saved: HybridDraft,
    onDraftChange: (HybridDraft) -> Unit,
    showApply: Boolean,
    onApply: () -> Unit,
    accent: androidx.compose.ui.graphics.Color,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    DisclosingSwitchCard(
        title = stringResource(R.string.use_custom_turn_server),
        subtitle = saved.turnEndpoint?.let { formatNetworkEndpoint(it.host, it.port) }
            ?: stringResource(R.string.mirror_app_turn_server),
        icon = Icons.Default.CloudQueue,
        iconTint = accent,
        checked = draft.customTurn,
        onCheckedChange = { onDraftChange(draft.copy(customTurn = it)) },
        position = CardPosition.TOP,
    ) {
        DraftTextField(
            value = draft.turnHost,
            onValueChange = { onDraftChange(draft.copy(turnHost = it)) },
            label = stringResource(R.string.hostname),
            placeholder = "turn.example.org",
            isError = !draft.turnHostValid,
        )
        DraftTextField(
            value = draft.turnPort,
            onValueChange = { onDraftChange(draft.copy(turnPort = it)) },
            label = stringResource(R.string.port),
            isError = draft.turnPortValue == null,
            keyboardType = KeyboardType.Number,
        )
        DraftTextField(
            value = draft.turnUser,
            onValueChange = { onDraftChange(draft.copy(turnUser = it)) },
            label = stringResource(R.string.username),
            isError = !draft.turnUserValid,
        )
        DraftTextField(
            value = draft.turnPassword,
            onValueChange = { onDraftChange(draft.copy(turnPassword = it)) },
            label = stringResource(R.string.password),
            isError = !draft.turnPasswordValid,
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
        if (!draft.turnHostValid || !draft.turnUserValid || !draft.turnPasswordValid) {
            Text(
                stringResource(if (!draft.turnHostValid) R.string.mirror_host_error_hostname_too_long else R.string.turn_credentials_too_long),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        ApplyRow(visible = showApply, onApply = onApply)
    }
}

@Composable
private fun DraftTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isError: Boolean = false,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
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
        modifier = Modifier.fillMaxWidth(),
    )
}
