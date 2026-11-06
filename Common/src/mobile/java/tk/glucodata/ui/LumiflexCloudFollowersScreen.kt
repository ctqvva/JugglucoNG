package tk.glucodata.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
        save = model::saveEditor, remove = model::remove,
    ))
}

internal class LumiflexFollowerActions(
    val refresh: () -> Unit = {}, val emailMode: (Boolean) -> Unit = {},
    val account: (String) -> Unit = {}, val alias: (String) -> Unit = {}, val invite: () -> Unit = {},
    val edit: (FollowerInfo) -> Unit = {}, val editChanged: (FollowerInfo) -> Unit = {},
    val dismissEditor: () -> Unit = {}, val save: () -> Unit = {}, val remove: (String) -> Unit = {},
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
    var removeId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val removalError = state.removeError?.let { stringResource(it) }
    LaunchedEffect(removalError) { removalError?.let { snackbar.showSnackbar(it) } }
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
    Scaffold(contentWindowInsets = WindowInsets(0.dp), snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        AppTopBar(stringResource(R.string.lumiflex_followers_title), back, actions = {
            IconButtonTooltip(stringResource(R.string.refresh)) {
                IconButton(onClick = actions.refresh, enabled = !state.blocked, shapes = IconButtonDefaults.shapes()) {
                    Icon(Icons.Default.Refresh, stringResource(R.string.refresh))
                }
            }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding().testTag("followers-list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 100.dp)) {
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
                ExpandableEditorCard(
                    title = row.readerAlias.ifBlank { lumiflexAccountLabel(row.readerUserName) },
                    subtitle = row.readerUserName.takeIf { row.readerAlias.isNotBlank() }?.let(::lumiflexAccountLabel),
                    leadingContent = { IconTile(Icons.Default.Person, MaterialTheme.colorScheme.tertiary) },
                    expanded = draft != null, onToggleExpanded = { select(if (draft == null) row else null) },
                    onDelete = { removeId = row.userAuthorizationId }, deleteLabel = stringResource(R.string.lumiflex_remove),
                    enabled = !state.blocked, deleting = state.removalId == row.userAuthorizationId,
                    modifier = Modifier.testTag("follower-${row.userAuthorizationId}"),
                ) {
                    LumiflexFollowerEditor(state, draft ?: row, actions)
                }
                if (index != state.followers.lastIndex) Spacer(Modifier.height(8.dp))
            }
        }
    }
    state.followers.find { it.userAuthorizationId == removeId }?.let { row ->
        AlertDialog(onDismissRequest = { removeId = null }, modifier = Modifier.testTag("follower-remove-confirm"),
            title = { Text(stringResource(R.string.lumiflex_remove)) },
            text = { Text(stringResource(R.string.lumiflex_remove_follower_confirm,
                row.readerAlias.ifBlank { lumiflexAccountLabel(row.readerUserName) })) },
            confirmButton = {
                TextButton(onClick = { removeId = null; actions.remove(row.userAuthorizationId) }, shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.lumiflex_remove))
                }
            }, dismissButton = {
                TextButton(onClick = { removeId = null }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) }
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
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            modifier = Modifier.fillMaxWidth()) {
            if (state.operation == FollowerOperation.INVITE) FollowerButtonProgress(true)
            else { Icon(Icons.Default.PersonAdd, null); Spacer(Modifier.width(8.dp)) }
            Text(stringResource(R.string.lumiflex_send_invite))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ColumnScope.LumiflexFollowerEditor(
    state: LumiflexFollowersUiState, row: FollowerInfo, actions: LumiflexFollowerActions,
) {
    OutlinedTextField(row.readerAlias, { actions.editChanged(row.copy(readerAlias = it)) },
        modifier = Modifier.fillMaxWidth().testTag("follower-name"),
        label = { Text(stringResource(R.string.lumiflex_nickname_optional)) },
        singleLine = true, enabled = !state.blocked)
    SettingsSubsectionTitle(stringResource(R.string.lumiflex_follower_alerts))
    Surface(Modifier.fillMaxWidth(), shape = cardShape(CardPosition.SINGLE, radius = 16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(vertical = 4.dp)) {
            ToggleRow(stringResource(R.string.lumiflex_regular_reminders), null, row.normalPushState == 1,
                { actions.editChanged(row.copy(normalPushState = if (it) 1 else 0)) }, enabled = !state.blocked)
            ControlDivider()
            ToggleRow(stringResource(R.string.lumiflex_urgent_low), null, row.emergePushState == 1,
                { actions.editChanged(row.copy(emergePushState = if (it) 1 else 0)) }, enabled = !state.blocked)
            ControlDivider()
            ToggleRow(stringResource(R.string.lumiflex_extreme_high), null, row.extremeHighPushState == 1,
                { actions.editChanged(row.copy(extremeHighPushState = if (it) 1 else 0)) }, enabled = !state.blocked)
        }
    }
    Surface(Modifier.fillMaxWidth(), shape = cardShape(CardPosition.SINGLE, radius = 16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer) {
        ToggleRow(stringResource(R.string.lumiflex_switch_display), null, row.hideState == 1,
            { actions.editChanged(row.copy(hideState = if (it) 1 else 0)) }, enabled = !state.blocked)
    }
    FollowerError(state.editorError)
    Button(onClick = actions.save, enabled = !state.blocked && state.editorDirty, shapes = ButtonDefaults.shapes(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), contentPadding = ButtonDefaults.ContentPadding) {
        if (state.operation == FollowerOperation.SAVE) FollowerButtonProgress(true)
        else { Icon(Icons.Default.Save, null); Spacer(Modifier.width(8.dp)) }
        Text(stringResource(R.string.save))
    }
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
