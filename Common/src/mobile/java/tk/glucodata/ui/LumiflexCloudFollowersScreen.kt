package tk.glucodata.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import tk.glucodata.R
import tk.glucodata.share.lumiflex.FollowerInfo
import tk.glucodata.ui.components.*
import tk.glucodata.ui.util.ConnectedButtonGroup

@Composable
fun LumiflexCloudFollowersScreen(navController: NavController) {
    val model: LumiflexCloudFollowersViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    LumiflexCloudFollowersContent(state, onBack = { navController.popBackStack() }, actions = LumiflexFollowerActions(
        refresh = model::refresh, emailMode = model::emailModeChanged,
        account = model::accountChanged, alias = model::aliasChanged, invite = model::invite,
        edit = model::edit, editChanged = model::editChanged, dismissEditor = model::dismissEditor,
        save = model::saveEditor, remove = model::removeEditor,
    ))
}

internal class LumiflexFollowerActions(
    val refresh: () -> Unit = {}, val emailMode: (Boolean) -> Unit = {},
    val account: (String) -> Unit = {}, val alias: (String) -> Unit = {}, val invite: () -> Unit = {},
    val edit: (FollowerInfo) -> Unit = {}, val editChanged: (FollowerInfo) -> Unit = {},
    val dismissEditor: () -> Unit = {}, val save: () -> Unit = {}, val remove: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LumiflexCloudFollowersContent(
    state: LumiflexFollowersUiState,
    onBack: () -> Unit = {},
    actions: LumiflexFollowerActions = LumiflexFollowerActions(),
) {
    var discard by rememberSaveable { mutableStateOf(false) }
    var pendingId by rememberSaveable { mutableStateOf<String?>(null) }
    var leaving by rememberSaveable { mutableStateOf(false) }
    var remove by rememberSaveable { mutableStateOf(false) }
    fun select(row: FollowerInfo?) {
        if (!state.blocked) {
            if (state.editorDirty) { pendingId = row?.userAuthorizationId; leaving = false; discard = true }
            else if (row == null) actions.dismissEditor() else actions.edit(row)
        }
    }
    val back = {
        if (!state.busy) {
            if (state.editorDirty) { leaving = true; discard = true } else onBack()
        }
    }
    BackHandler(enabled = state.busy || state.editorDirty, onBack = back)
    Scaffold(contentWindowInsets = WindowInsets(0.dp), topBar = {
        AppTopBar(stringResource(R.string.lumiflex_followers_title), back, actions = {
            IconButtonTooltip(stringResource(R.string.refresh)) {
                IconButton(onClick = actions.refresh, enabled = !state.blocked, shapes = IconButtonDefaults.shapes()) {
                    Icon(Icons.Default.Refresh, stringResource(R.string.refresh))
                }
            }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding().testTag("followers-list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp)) {
            item(key = "invite") {
                SectionLabel(stringResource(R.string.lumiflex_invite_title), topPadding = 0.dp)
                LumiflexInviteForm(state, actions)
                state.notice?.let {
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(it), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
                SectionLabel(stringResource(R.string.lumiflex_my_shares))
            }
            if (state.loadFailed) item {
                Text(stringResource(R.string.lumiflex_status_load_failed), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                FilledTonalButton(onClick = actions.refresh, enabled = !state.blocked,
                    shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.clone_history_try_again))
                }
                Spacer(Modifier.height(24.dp))
            }
            if (state.loading) item {
                val loading = stringResource(R.string.refreshing)
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    LoadingIndicator(Modifier.size(48.dp).semantics { contentDescription = loading })
                }
            }
            if (!state.loading && !state.loadFailed && state.followers.isEmpty()) item {
                Text(stringResource(R.string.lumiflex_no_followers), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            itemsIndexed(state.followers, key = { _, row -> row.userAuthorizationId }) { index, row ->
                val draft = state.editor?.takeIf { it.userAuthorizationId == row.userAuthorizationId }
                ExpandableSettingsCard(
                    title = row.readerAlias.ifBlank { lumiflexAccountLabel(row.readerUserName) },
                    summary = if (row.readerAlias.isBlank()) "" else lumiflexAccountLabel(row.readerUserName),
                    icon = Icons.Default.Person, iconTint = MaterialTheme.colorScheme.tertiary,
                    expanded = draft != null, onExpandedChange = { select(if (it) row else null) },
                    modifier = Modifier.testTag("follower-${row.userAuthorizationId}"),
                    position = when {
                        state.followers.size == 1 -> CardPosition.SINGLE
                        index == 0 -> CardPosition.TOP
                        index == state.followers.lastIndex -> CardPosition.BOTTOM
                        else -> CardPosition.MIDDLE
                    },
                ) {
                    LumiflexFollowerEditor(state, draft ?: row, actions, onCancel = { select(null) }, onRemove = { remove = true })
                }
                if (index != state.followers.lastIndex) Spacer(Modifier.height(2.dp))
            }
        }
    }
    state.editor?.let { row ->
        if (remove) AlertDialog(onDismissRequest = { remove = false }, modifier = Modifier.testTag("follower-remove-confirm"),
            title = { Text(stringResource(R.string.lumiflex_remove)) },
            text = { Text(stringResource(R.string.lumiflex_remove_follower_confirm,
                row.readerAlias.ifBlank { lumiflexAccountLabel(row.readerUserName) })) },
            confirmButton = {
                TextButton(onClick = { remove = false; actions.remove() }, shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.lumiflex_remove))
                }
            }, dismissButton = {
                TextButton(onClick = { remove = false }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) }
            })
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, modifier = Modifier.testTag("follower-discard-confirm"),
        title = { Text(stringResource(R.string.lumiflex_discard_changes_title)) },
        text = { Text(stringResource(R.string.lumiflex_discard_changes_message)) },
        confirmButton = {
            TextButton(onClick = {
                discard = false
                if (leaving) onBack()
                else state.followers.find { it.userAuthorizationId == pendingId }?.let(actions.edit) ?: actions.dismissEditor()
            }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.app_updates_action_discard)) }
        }, dismissButton = {
            TextButton(onClick = { discard = false }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) }
        })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LumiflexInviteForm(state: LumiflexFollowersUiState, actions: LumiflexFollowerActions) {
    Column(Modifier.fillMaxWidth().testTag("follower-invite"),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ConnectedButtonGroup(options = listOf(false, true), selectedOption = state.emailMode,
            onOptionSelected = { if (it != state.emailMode) actions.emailMode(it) }, enabled = !state.busy,
            label = { Text(stringResource(if (it) R.string.lumiflex_email_mode else R.string.lumiflex_phone_mode)) },
            modifier = Modifier.fillMaxWidth())
        if (state.emailMode) {
            OutlinedTextField(state.account, actions.account, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.lumiflex_email_mode)) }, singleLine = true, enabled = !state.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false))
        } else LumiflexPhoneField(state.account, actions.account, !state.busy)
        OutlinedTextField(state.alias, actions.alias, modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.lumiflex_nickname_optional)) }, singleLine = true, enabled = !state.busy)
        FollowerError(state.inviteError)
        Button(onClick = actions.invite, enabled = state.canInvite, shapes = ButtonDefaults.shapes(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            FollowerButtonProgress(state.operation == FollowerOperation.INVITE)
            Text(stringResource(R.string.lumiflex_send_invite))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LumiflexFollowerEditor(
    state: LumiflexFollowersUiState, row: FollowerInfo, actions: LumiflexFollowerActions,
    onCancel: () -> Unit, onRemove: () -> Unit,
) {
    // The shared accordion supplies 16dp horizontally and 12dp vertically; complete the 16dp body inset.
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("follower-editor")) {
        OutlinedTextField(row.readerAlias, { actions.editChanged(row.copy(readerAlias = it)) },
            modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.lumiflex_nickname_optional)) },
            singleLine = true, enabled = !state.blocked)
        SectionLabel(stringResource(R.string.lumiflex_follower_alerts))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FollowerSetting(stringResource(R.string.lumiflex_regular_reminders), row.normalPushState == 1,
                !state.blocked, CardPosition.TOP) {
                actions.editChanged(row.copy(normalPushState = if (it) 1 else 0))
            }
            FollowerSetting(stringResource(R.string.lumiflex_urgent_low), row.emergePushState == 1,
                !state.blocked, CardPosition.MIDDLE) {
                actions.editChanged(row.copy(emergePushState = if (it) 1 else 0))
            }
            FollowerSetting(stringResource(R.string.lumiflex_extreme_high), row.extremeHighPushState == 1,
                !state.blocked, CardPosition.BOTTOM) {
                actions.editChanged(row.copy(extremeHighPushState = if (it) 1 else 0))
            }
        }
        Spacer(Modifier.height(24.dp))
        FollowerSetting(stringResource(R.string.lumiflex_switch_display), row.hideState == 1, !state.blocked) {
            actions.editChanged(row.copy(hideState = if (it) 1 else 0))
        }
        state.editorError?.let { Spacer(Modifier.height(16.dp)); FollowerError(it) }
        Spacer(Modifier.height(24.dp))
        AdaptiveButtonRow(Modifier.fillMaxWidth()) {
            Button(onClick = actions.save, enabled = !state.blocked && state.editorDirty, shapes = ButtonDefaults.shapes(),
                modifier = Modifier.heightIn(min = 56.dp)) {
                FollowerButtonProgress(state.operation == FollowerOperation.SAVE)
                Text(stringResource(R.string.save))
            }
            OutlinedButton(onClick = onCancel, enabled = !state.blocked, shapes = ButtonDefaults.shapes(),
                modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.cancel)) }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onRemove, enabled = !state.blocked, shapes = ButtonDefaults.shapes(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            FollowerButtonProgress(state.operation == FollowerOperation.REMOVE)
            Text(stringResource(R.string.lumiflex_remove))
        }
    }
}

@Composable
private fun FollowerSetting(
    label: String, checked: Boolean, enabled: Boolean, position: CardPosition = CardPosition.SINGLE, onChange: (Boolean) -> Unit,
) {
    SettingsSwitchItem(title = label, checked = checked, enabled = enabled, position = position, onCheckedChange = onChange,
        modifier = Modifier.semantics {
            role = Role.Switch
            toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
        })
}

@Composable
private fun FollowerError(error: Int?) {
    error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun RowScope.FollowerButtonProgress(busy: Boolean) {
    if (busy) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
    }
}
