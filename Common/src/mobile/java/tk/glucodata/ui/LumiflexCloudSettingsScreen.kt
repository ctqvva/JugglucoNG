package tk.glucodata.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import java.text.DateFormat
import java.util.Date
import tk.glucodata.R
import tk.glucodata.share.lumiflex.LumiflexCloudClient
import tk.glucodata.share.lumiflex.LumiflexCloudSettings
import tk.glucodata.ui.components.AppTopBar
import tk.glucodata.ui.components.MasterSwitchCard
import tk.glucodata.ui.components.SettingsItem

@Composable
fun LumiflexCloudSettingsScreen(navController: NavController) {
    val model: LumiflexCloudViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    LumiflexCloudSettingsContent(
        state = state,
        onBack = { navController.popBackStack() },
        onAccount = model::accountChanged, onPassword = model::passwordChanged,
        onSignIn = model::signIn, onSignOut = model::signOut,
        onEdit = model::editCredentials, onEnabled = model::setEnabled,
        onSync = { model.checkConnection() },
        onRegistration = model::showRegistration,
        onPhone = model::phoneChanged, onCode = model::codeChanged,
        onRequestCode = model::requestCode, onVerifyCode = model::verifyCode,
        onSetPassword = model::setPassword,
        onFollowers = { navController.navigate("settings/lumiflex/followers") },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun LumiflexCloudSettingsContent(
    state: LumiflexCloudUiState,
    onBack: () -> Unit = {},
    onAccount: (String) -> Unit = {},
    onPassword: (String) -> Unit = {},
    onSignIn: () -> Unit = {},
    onSignOut: () -> Unit = {},
    onEdit: () -> Unit = {},
    onEnabled: (Boolean) -> Unit = {},
    onSync: () -> Unit = {},
    onRegistration: (Boolean) -> Unit = {},
    onPhone: (String) -> Unit = {},
    onCode: (String) -> Unit = {},
    onRequestCode: () -> Unit = {},
    onVerifyCode: () -> Unit = {},
    onSetPassword: () -> Unit = {},
    onFollowers: () -> Unit = {},
) {
    val signedIn = state.session.signedIn
    val accountStatus = when {
        state.checkingSession -> R.string.lumiflex_account_checking
        signedIn && state.passwordSetupRequired -> R.string.lumiflex_account_setup_pending
        signedIn && state.verificationFailed -> R.string.lumiflex_account_unverified
        signedIn -> R.string.lumiflex_status_signed_in
        state.sessionExpired -> R.string.lumiflex_account_expired
        else -> R.string.lumiflex_status_signed_out
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = { AppTopBar(title = stringResource(R.string.lumiflex_cloud_title), onNavigateBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding()
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(
                            if (signedIn && !state.verificationFailed && !state.checkingSession && !state.passwordSetupRequired)
                                Icons.Default.CheckCircle else Icons.Default.AccountCircle,
                            contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.lumiflex_account), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(accountStatus), style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                color = if (state.sessionExpired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (state.checkingSession) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                    if (signedIn) {
                        Text(state.session.account, style = MaterialTheme.typography.bodyLarge)
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!state.passwordSetupRequired) {
                                TextButton(onClick = onEdit, enabled = !state.busy, shapes = ButtonDefaults.shapes()) {
                                    Text(stringResource(if (state.editing) R.string.lumiflex_hide_credentials else R.string.lumiflex_edit_credentials))
                                }
                            }
                            TextButton(onClick = onSignOut, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.lumiflex_sign_out)) }
                        }
                    }
                }
            }

            state.error?.takeIf { it.isNotBlank() }?.let { message ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                    Text(message, Modifier.fillMaxWidth().padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                }
            }
            state.notice?.let { message ->
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }

            if (state.registration) {
                LumiflexRegistrationCard(state, onPhone, onCode, onRequestCode, onVerifyCode) { onRegistration(false) }
            } else if (!signedIn || state.editing || state.passwordSetupRequired) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            stringResource(if (state.passwordSetupRequired && signedIn) R.string.lumiflex_set_password else R.string.lumiflex_sign_in),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (!state.passwordSetupRequired || !signedIn) {
                            OutlinedTextField(
                                value = state.account, onValueChange = onAccount,
                                label = { Text(stringResource(R.string.lumiflex_account_hint)) },
                                modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.busy,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email,
                                    autoCorrectEnabled = false, imeAction = ImeAction.Next),
                            )
                        }
                        LumiflexPasswordField(state.password, onPassword, enabled = !state.busy) {
                            if (state.passwordSetupRequired && signedIn) onSetPassword() else onSignIn()
                        }
                        if (state.passwordSetupRequired && signedIn) {
                            Text(stringResource(R.string.lumiflex_password_rules), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(
                            onClick = if (state.passwordSetupRequired && signedIn) onSetPassword else onSignIn,
                            enabled = !state.busy && state.password.isNotBlank() &&
                                (state.account.isNotBlank() || (signedIn && state.passwordSetupRequired)),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shapes = ButtonDefaults.shapes(),
                        ) {
                            if (state.busy) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(stringResource(when {
                                state.busy -> R.string.lumiflex_working
                                state.passwordSetupRequired && signedIn -> R.string.lumiflex_set_password
                                else -> R.string.lumiflex_sign_in
                            }))
                        }
                        if (!signedIn) {
                            TextButton(onClick = { onRegistration(true) }, enabled = !state.busy,
                                shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.lumiflex_create_account))
                            }
                            Text(stringResource(R.string.lumiflex_account_help), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            if (signedIn && !state.registration && !state.passwordSetupRequired) {
                MasterSwitchCard(
                    title = stringResource(R.string.lumiflex_auto_upload),
                    subtitle = stringResource(R.string.lumiflex_auto_upload_description),
                    checked = state.enabled, onCheckedChange = onEnabled, icon = Icons.Default.CloudUpload,
                    enabled = !state.busy,
                )
                LumiflexSyncCard(state, onSync)
                SettingsItem(
                    title = stringResource(R.string.lumiflex_manage_followers),
                    subtitle = stringResource(R.string.lumiflex_followers_description),
                    icon = Icons.Default.Group, onClick = onFollowers,
                )
            }
            Text(stringResource(R.string.lumiflex_session_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LumiflexPasswordField(value: String, onValue: (String) -> Unit, enabled: Boolean, onDone: () -> Unit) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onValue, enabled = enabled,
        label = { Text(stringResource(R.string.lumiflex_password)) },
        modifier = Modifier.fillMaxWidth(), singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (enabled && value.isNotBlank()) onDone() }),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }, shapes = IconButtonDefaults.shapes()) {
                Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = stringResource(if (visible) R.string.lumiflex_hide_password else R.string.lumiflex_show_password))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LumiflexRegistrationCard(
    state: LumiflexCloudUiState, onPhone: (String) -> Unit, onCode: (String) -> Unit,
    onRequestCode: () -> Unit, onVerifyCode: () -> Unit, onBack: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.lumiflex_create_account), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.lumiflex_registration_description), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = state.phone, onValueChange = onPhone, enabled = !state.busy,
                label = { Text(stringResource(R.string.lumiflex_phone)) },
                placeholder = { Text(stringResource(R.string.lumiflex_phone_example)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
            )
            OutlinedButton(
                onClick = onRequestCode,
                enabled = !state.busy && state.resendSeconds == 0 && LumiflexCloudClient.registrationPhone(state.phone) != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shapes = ButtonDefaults.shapes(),
            ) {
                if (state.sendingCode) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (state.sendingCode) stringResource(R.string.lumiflex_working)
                    else if (state.resendSeconds > 0) stringResource(R.string.lumiflex_resend_after, state.resendSeconds)
                    else stringResource(R.string.lumiflex_send_code))
            }
            if (state.codeSent) {
                OutlinedTextField(
                    value = state.code, onValueChange = { onCode(it.filter { char -> char in '0'..'9' }) },
                    label = { Text(stringResource(R.string.lumiflex_verification_code)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (!state.busy && state.code.isNotBlank()) onVerifyCode() }),
                )
                Button(onClick = onVerifyCode, enabled = !state.busy && state.code.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shapes = ButtonDefaults.shapes()) {
                    if (state.busy && !state.sendingCode) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(if (state.busy) R.string.lumiflex_working else R.string.lumiflex_verify_phone))
                }
            }
            TextButton(onClick = onBack, enabled = !state.busy, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.lumiflex_back_to_sign_in))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LumiflexSyncCard(state: LumiflexCloudUiState, onSync: () -> Unit) {
    val working = state.sync.state == LumiflexCloudSettings.SyncState.SYNCING
    val title = if (!state.enabled) R.string.lumiflex_sync_paused else when (state.sync.state) {
        LumiflexCloudSettings.SyncState.IDLE -> R.string.lumiflex_sync_waiting_readings
        LumiflexCloudSettings.SyncState.QUEUED -> R.string.lumiflex_sync_queued
        LumiflexCloudSettings.SyncState.SYNCING -> R.string.lumiflex_sync_uploading
        LumiflexCloudSettings.SyncState.SYNCED -> R.string.lumiflex_sync_up_to_date
        LumiflexCloudSettings.SyncState.WAITING_FOR_SENSOR -> R.string.lumiflex_sync_sensor_missing
        LumiflexCloudSettings.SyncState.ERROR -> R.string.lumiflex_sync_error
        LumiflexCloudSettings.SyncState.SIGN_IN_REQUIRED -> R.string.lumiflex_account_expired
    }
    val lastSuccess = if (state.sync.lastSuccessMs > 0) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(state.sync.lastSuccessMs))
    } else stringResource(R.string.lumiflex_never)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Sync, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
                if (working) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            Text(stringResource(R.string.lumiflex_pending_readings, state.sync.pending), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.lumiflex_last_sync, lastSuccess), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.sync.state == LumiflexCloudSettings.SyncState.WAITING_FOR_SENSOR) {
                Text(stringResource(R.string.lumiflex_sensor_registration_required), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.sync.state == LumiflexCloudSettings.SyncState.ERROR) {
                Text(if (state.sync.retrying) stringResource(R.string.lumiflex_sync_retry_hint)
                    else state.sync.message.ifBlank { stringResource(R.string.lumiflex_request_failed) },
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Button(onClick = onSync,
                enabled = state.enabled && !state.busy && !state.checkingSession && !working,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shapes = ButtonDefaults.shapes()) {
                Text(stringResource(if (state.checkingSession) R.string.lumiflex_account_checking else R.string.lumiflex_sync_now))
            }
        }
    }
}
