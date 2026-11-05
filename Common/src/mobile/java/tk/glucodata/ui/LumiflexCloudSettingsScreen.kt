package tk.glucodata.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import java.text.DateFormat
import java.util.Date
import tk.glucodata.R
import tk.glucodata.share.lumiflex.LumiflexCloudClient
import tk.glucodata.share.lumiflex.LumiflexCloudSettings
import tk.glucodata.ui.components.*
import tk.glucodata.ui.util.ConnectedButtonGroup

@Composable
fun LumiflexCloudSettingsScreen(navController: NavController) {
    val model: LumiflexCloudViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    LumiflexCloudSettingsContent(
        state = state, onBack = { navController.popBackStack() },
        onAccount = model::accountChanged, onPassword = model::passwordChanged,
        onSignIn = model::signIn, onSignOut = model::signOut, onEdit = model::editCredentials,
        onEnabled = model::setEnabled, onSync = { model.checkConnection() }, onBind = model::bindSensors,
        onRegistration = model::showRegistration, onPhone = model::phoneChanged, onCode = model::codeChanged,
        onRequestCode = model::requestCode, onVerifyCode = model::verifyCode, onSetPassword = model::setPassword,
        onFollowers = { navController.navigate("settings/lumiflex/followers") },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LumiflexCloudSettingsContent(
    state: LumiflexCloudUiState,
    onBack: () -> Unit = {}, onAccount: (String) -> Unit = {}, onPassword: (String) -> Unit = {},
    onSignIn: () -> Unit = {}, onSignOut: () -> Unit = {}, onEdit: () -> Unit = {},
    onEnabled: (Boolean) -> Unit = {}, onSync: () -> Unit = {}, onBind: () -> Unit = onSync,
    onRegistration: (Boolean) -> Unit = {}, onPhone: (String) -> Unit = {}, onCode: (String) -> Unit = {},
    onRequestCode: () -> Unit = {}, onVerifyCode: () -> Unit = {}, onSetPassword: () -> Unit = {},
    onFollowers: () -> Unit = {},
) {
    var showHelp by remember { mutableStateOf(false) }
    var accountMenu by remember { mutableStateOf(false) }
    var emailLogin by rememberSaveable { mutableStateOf(state.account.contains('@')) }
    val signedIn = state.session.signedIn
    val setup = signedIn && state.passwordSetupRequired
    val working = state.sync.state == LumiflexCloudSettings.SyncState.SYNCING
    val usable = !state.busy && !state.checkingSession
    val status = when {
        state.checkingSession -> R.string.lumiflex_account_checking
        setup -> R.string.lumiflex_account_setup_pending
        state.verificationFailed -> R.string.lumiflex_account_unverified
        else -> R.string.lumiflex_status_signed_in
    }
    if (showHelp) {
        AlertDialog(onDismissRequest = { showHelp = false },
            title = { Text(stringResource(R.string.lumiflex_cloud_title)) },
            text = { Text(stringResource(R.string.lumiflex_session_note)) },
            confirmButton = {
                TextButton(onClick = { showHelp = false }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(android.R.string.ok))
                }
            })
    }
    Scaffold(contentWindowInsets = WindowInsets(0.dp), topBar = {
        AppTopBar(stringResource(R.string.lumiflex_cloud_title), onBack, actions = {
            IconButtonTooltip(stringResource(R.string.lumiflex_help)) {
                IconButton(onClick = { showHelp = true }, shapes = IconButtonDefaults.shapes()) {
                    Icon(Icons.Default.Info, stringResource(R.string.lumiflex_help))
                }
            }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (signedIn) {
                SettingsItem(
                    title = lumiflexAccountLabel(state.session.account), subtitle = stringResource(status),
                    icon = Icons.Default.AccountCircle, onClick = if (usable && !setup) onEdit else null,
                    trailingContent = {
                        if (state.checkingSession) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        Box {
                            IconButton(onClick = { accountMenu = true }, shapes = IconButtonDefaults.shapes()) {
                                Icon(Icons.Default.MoreVert, stringResource(R.string.lumiflex_account_actions))
                            }
                            DropdownMenu(expanded = accountMenu, onDismissRequest = { accountMenu = false }) {
                                if (!setup) DropdownMenuItem(text = { Text(stringResource(R.string.lumiflex_edit_credentials)) },
                                    enabled = !state.busy, onClick = { accountMenu = false; onEdit() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.lumiflex_sign_out)) },
                                    onClick = { accountMenu = false; onSignOut() })
                            }
                        }
                    },
                )
            }
            if (state.sessionExpired && !signedIn) {
                Text(stringResource(R.string.lumiflex_account_expired), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error)
            }
            state.error?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            state.notice?.takeIf { it.isNotBlank() && !setup }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }

            if (state.registration) {
                SectionLabel(stringResource(R.string.lumiflex_create_account), topPadding = 0.dp)
                LumiflexPhoneField(state.phone, onPhone, !state.busy)
                Button(onClick = onRequestCode,
                    enabled = !state.busy && state.resendSeconds == 0 && LumiflexCloudClient.registrationPhone(state.phone) != null,
                    shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    if (state.sendingCode) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (state.resendSeconds > 0) stringResource(R.string.lumiflex_resend_after, state.resendSeconds)
                        else stringResource(R.string.lumiflex_send_code))
                }
                if (state.codeSent) {
                    OutlinedTextField(value = state.code,
                        onValueChange = { onCode(it.filter { char -> char in '0'..'9' }) },
                        label = { Text(stringResource(R.string.lumiflex_verification_code)) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Button(onClick = onVerifyCode, enabled = !state.busy && state.code.isNotBlank(),
                        shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(if (state.busy) R.string.lumiflex_working else R.string.lumiflex_verify_phone))
                    }
                }
                TextButton(onClick = { onRegistration(false) }, enabled = !state.busy, shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.lumiflex_back_to_sign_in)) }
            } else if (!signedIn || state.editing || setup) {
                SectionLabel(stringResource(if (setup) R.string.lumiflex_set_password else R.string.lumiflex_account), topPadding = 0.dp)
                if (!setup) {
                    ConnectedButtonGroup(
                        options = listOf(false, true), selectedOption = emailLogin,
                        onOptionSelected = { if (!state.busy && it != emailLogin) { emailLogin = it; onAccount("") } },
                        label = { Text(stringResource(if (it) R.string.lumiflex_email_mode else R.string.lumiflex_phone_mode)) },
                        modifier = Modifier.fillMaxWidth(), enabled = !state.busy,
                    )
                    if (emailLogin) {
                        OutlinedTextField(value = state.account, onValueChange = onAccount,
                            label = { Text(stringResource(R.string.lumiflex_email_mode)) },
                            singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false))
                    } else LumiflexPhoneField(state.account, onAccount, !state.busy)
                }
                AccountPasswordField(state.password, onPassword, enabled = !state.busy,
                    supportingText = if (setup) stringResource(R.string.lumiflex_password_rules) else null,
                    onDone = if (setup) onSetPassword else onSignIn)
                AdaptiveButtonRow(Modifier.fillMaxWidth()) {
                    Button(onClick = if (setup) onSetPassword else onSignIn,
                        enabled = !state.busy && state.password.isNotBlank() &&
                            (setup || if (emailLogin) state.account.isNotBlank() else LumiflexCloudClient.registrationPhone(state.account) != null),
                        shapes = ButtonDefaults.shapes(), modifier = Modifier.heightIn(min = 48.dp)) {
                        if (state.busy) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(if (state.busy) R.string.lumiflex_working else if (setup) R.string.lumiflex_set_password else R.string.lumiflex_sign_in))
                    }
                    if (!signedIn) OutlinedButton(onClick = { onRegistration(true) }, enabled = !state.busy,
                        shapes = ButtonDefaults.shapes(), modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.lumiflex_create_account))
                    }
                }
            }

            if (signedIn && !setup && !state.registration) {
                SectionLabel(stringResource(R.string.lumiflex_cloud_controls), topPadding = 0.dp)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SettingsSwitchItem(title = stringResource(R.string.lumiflex_auto_upload),
                        checked = state.enabled, onCheckedChange = onEnabled, enabled = !state.busy,
                        icon = Icons.Default.CloudUpload, position = CardPosition.TOP)
                    SettingsItem(title = stringResource(R.string.lumiflex_sensor),
                        subtitle = state.sync.boundSensors.takeIf { it.isNotEmpty() }?.joinToString { "X-${it.removePrefix("X-")}" }
                            ?: stringResource(R.string.lumiflex_connect_sensor),
                        icon = Icons.Default.Sensors, position = CardPosition.MIDDLE,
                        onClick = if (state.enabled && usable && !working) onBind else null,
                        trailingContent = {
                            if (working && state.sync.boundSensors.isEmpty()) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            else IconButton(onClick = onBind, enabled = state.enabled && usable && !working, shapes = IconButtonDefaults.shapes()) {
                                Icon(Icons.Default.Link, stringResource(R.string.lumiflex_bind_sensor))
                            }
                        })
                    val syncTitle = when {
                        !state.enabled -> stringResource(R.string.lumiflex_sync_paused)
                        state.sync.state == LumiflexCloudSettings.SyncState.SYNCING -> stringResource(R.string.lumiflex_sync_uploading)
                        state.sync.state == LumiflexCloudSettings.SyncState.ERROR -> stringResource(R.string.lumiflex_sync_error)
                        state.sync.state == LumiflexCloudSettings.SyncState.WAITING_FOR_SENSOR -> stringResource(R.string.lumiflex_connect_sensor)
                        state.sync.pending > 0 -> stringResource(R.string.lumiflex_pending_readings, state.sync.pending)
                        state.sync.lastSuccessMs > 0 -> stringResource(R.string.lumiflex_sync_up_to_date)
                        else -> stringResource(R.string.lumiflex_sync_waiting_readings)
                    }
                    val detail = when {
                        state.sync.state == LumiflexCloudSettings.SyncState.ERROR ->
                            if (state.sync.retrying) stringResource(R.string.lumiflex_sync_retry_hint) else state.sync.message
                        state.sync.lastSuccessMs > 0 -> DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(state.sync.lastSuccessMs))
                        else -> null
                    }
                    SettingsItem(title = syncTitle, subtitle = detail, icon = Icons.Default.Sync,
                        position = CardPosition.MIDDLE, onClick = if (state.enabled && usable && !working) onSync else null,
                        trailingContent = {
                            if (working) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            else IconButton(onClick = onSync, enabled = state.enabled && usable, shapes = IconButtonDefaults.shapes()) {
                                Icon(Icons.Default.Sync, stringResource(R.string.lumiflex_sync_now))
                            }
                        })
                    SettingsItem(title = stringResource(R.string.lumiflex_manage_followers),
                        icon = Icons.Default.Group, position = CardPosition.BOTTOM, onClick = onFollowers)
                }
            }
        }
    }
}

@Composable
private fun LumiflexPhoneField(account: String, onAccount: (String) -> Unit, enabled: Boolean) {
    AccountPhoneField(
        value = russianSubscriberFromAccount(account),
        onValueChange = { raw ->
            val digits = russianSubscriberInput(raw)
            onAccount(if (digits.isBlank()) "" else "7$digits")
        },
        prefix = "+7", label = stringResource(R.string.lumiflex_phone_mode), enabled = enabled,
        placeholder = stringResource(R.string.lumiflex_phone_example),
        normalizeInput = ::russianSubscriberInput,
        outputTransformation = RussianPhoneOutputTransformation,
    )
}

private fun lumiflexAccountLabel(account: String): String {
    if (account.contains('@')) return account
    val digits = russianSubscriberFromAccount(account)
    return if (digits.length == 10) "+7 (${digits.take(3)}) ${digits.substring(3, 6)}-${digits.substring(6, 8)}-${digits.substring(8)}" else account
}
