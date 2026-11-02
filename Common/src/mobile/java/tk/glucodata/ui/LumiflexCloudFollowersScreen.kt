package tk.glucodata.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.R
import tk.glucodata.share.lumiflex.FollowerInfo
import tk.glucodata.share.lumiflex.LumiflexCloudClient
import tk.glucodata.share.lumiflex.LumiflexCloudFollowerManager
import tk.glucodata.share.lumiflex.LumiflexCloudSettings
import tk.glucodata.ui.components.AppTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LumiflexCloudFollowersScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { LumiflexCloudSettings(context) }
    val manager = remember { LumiflexCloudFollowerManager(LumiflexCloudClient(), settings) }

    var followers by remember { mutableStateOf<List<FollowerInfo>>(emptyList()) }
    var account by remember { mutableStateOf("") }
    var alias by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var renameId by remember { mutableStateOf("") }
    var renameValue by remember { mutableStateOf("") }

    fun refresh() {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { manager.list() }
            if (loaded != null) {
                followers = loaded
            } else {
                status = context.getString(R.string.lumiflex_status_load_failed)
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { refresh() }

    fun update(id: String, transform: (FollowerInfo) -> FollowerInfo) {
        followers = followers.map { if (it.userAuthorizationId == id) transform(it) else it }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.lumiflex_followers_title),
                onNavigateBack = { navController.popBackStack() },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.lumiflex_invite_title), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = account,
                onValueChange = { account = it },
                label = { Text(stringResource(R.string.lumiflex_follower_account)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = alias,
                onValueChange = { alias = it },
                label = { Text(stringResource(R.string.lumiflex_nickname_optional)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Button(
                onClick = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { manager.invite(account, alias) }
                        status = context.getString(
                            if (ok) R.string.lumiflex_status_invited else R.string.lumiflex_status_invite_failed,
                        )
                        if (ok) {
                            account = ""
                            alias = ""
                        }
                        refresh()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shapes = ButtonDefaults.shapes(),
            ) {
                Icon(Icons.Default.PersonAdd, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.lumiflex_send_invite))
            }

            if (status.isNotEmpty()) {
                Text(status, style = MaterialTheme.typography.bodySmall)
            }

            Text(stringResource(R.string.lumiflex_my_shares), style = MaterialTheme.typography.titleMedium)
            if (followers.isEmpty()) {
                Text(
                    stringResource(R.string.lumiflex_no_followers),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            followers.forEach { follower ->
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            follower.readerAlias.ifBlank { follower.readerUserName },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(follower.readerUserName, style = MaterialTheme.typography.bodySmall)

                        ToggleRow(stringResource(R.string.lumiflex_switch_display), follower.hideState == 1) { checked ->
                            update(follower.userAuthorizationId) {
                                it.copy(hideState = if (checked) 1 else 0)
                            }
                            scope.launch(Dispatchers.IO) {
                                manager.setDisplayHidden(follower.userAuthorizationId, checked)
                            }
                        }
                        ToggleRow(stringResource(R.string.lumiflex_regular_reminders), follower.normalPushState == 1) { checked ->
                            update(follower.userAuthorizationId) {
                                it.copy(normalPushState = if (checked) 1 else 0)
                            }
                            scope.launch(Dispatchers.IO) {
                                manager.setAlerts(
                                    follower.userAuthorizationId,
                                    normal = checked,
                                    urgent = follower.emergePushState == 1,
                                    extremeHigh = follower.extremeHighPushState == 1,
                                )
                            }
                        }
                        ToggleRow(stringResource(R.string.lumiflex_urgent_low), follower.emergePushState == 1) { checked ->
                            update(follower.userAuthorizationId) {
                                it.copy(emergePushState = if (checked) 1 else 0)
                            }
                            scope.launch(Dispatchers.IO) {
                                manager.setAlerts(
                                    follower.userAuthorizationId,
                                    normal = follower.normalPushState == 1,
                                    urgent = checked,
                                    extremeHigh = follower.extremeHighPushState == 1,
                                )
                            }
                        }
                        ToggleRow(stringResource(R.string.lumiflex_extreme_high), follower.extremeHighPushState == 1) { checked ->
                            update(follower.userAuthorizationId) {
                                it.copy(extremeHighPushState = if (checked) 1 else 0)
                            }
                            scope.launch(Dispatchers.IO) {
                                manager.setAlerts(
                                    follower.userAuthorizationId,
                                    normal = follower.normalPushState == 1,
                                    urgent = follower.emergePushState == 1,
                                    extremeHigh = checked,
                                )
                            }
                        }

                        if (renameId == follower.userAuthorizationId) {
                            OutlinedTextField(
                                value = renameValue,
                                onValueChange = { renameValue = it },
                                label = { Text(stringResource(R.string.lumiflex_new_nickname)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            Button(
                                onClick = {
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) {
                                            manager.rename(follower.userAuthorizationId, renameValue)
                                        }
                                        status = context.getString(
                                            if (ok) R.string.lumiflex_status_renamed else R.string.lumiflex_status_rename_failed,
                                        )
                                        renameId = ""
                                        refresh()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                Text(stringResource(R.string.lumiflex_save_nickname))
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(
                                onClick = {
                                    renameId = follower.userAuthorizationId
                                    renameValue = follower.readerAlias
                                },
                                shapes = IconButtonDefaults.shapes(),
                            ) {
                                Text(stringResource(R.string.lumiflex_rename))
                            }
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) {
                                            manager.remove(follower.userAuthorizationId)
                                        }
                                        status = context.getString(
                                            if (ok) R.string.lumiflex_status_removed
                                            else R.string.lumiflex_status_remove_failed,
                                        )
                                        refresh()
                                    }
                                },
                                shapes = IconButtonDefaults.shapes(),
                            ) {
                                Text(stringResource(R.string.lumiflex_remove))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
