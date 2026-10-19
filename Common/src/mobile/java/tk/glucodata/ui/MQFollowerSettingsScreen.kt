@file:OptIn(ExperimentalMaterial3Api::class)

package tk.glucodata.ui

import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.R
import tk.glucodata.drivers.mq.MQBootstrapFailure
import tk.glucodata.drivers.mq.MQCloudClient
import tk.glucodata.drivers.mq.MQIncomingFriendRequest
import tk.glucodata.drivers.mq.MQRegistry
import tk.glucodata.ui.components.AppTopBar
import tk.glucodata.ui.components.MasterSwitchCard
import tk.glucodata.ui.components.SectionLabel

@Composable
fun MQFollowerSettingsScreen(navController: NavController) {
    MQFollowerSettingsContent(onBack = { navController.popBackStack() })
}

@Composable
fun MQFollowerSettingsContent(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val initialAccountState = remember { MQRegistry.loadAccountState(context) }

    val signedInAccount = initialAccountState.phone
    var followerEnabled by rememberSaveable {
        mutableStateOf(MQRegistry.isFollowerEnabled(context))
    }
    var followerAccount by rememberSaveable {
        mutableStateOf(MQRegistry.loadFollowerAccount(context))
    }
    var statusText by rememberSaveable { mutableStateOf("") }
    var busyAction by remember { mutableStateOf<String?>(null) }
    var incomingRequests by remember { mutableStateOf<List<MQIncomingFriendRequest>>(emptyList()) }
    var requestsLoaded by remember { mutableStateOf(false) }

    val isBusy = busyAction != null
    val followerLabel = stringResource(R.string.mq_follower_title)
    val refreshLabel = stringResource(R.string.refresh)
    val approveLabel = stringResource(R.string.mq_friend_request_approve_action)
    val rejectLabel = stringResource(R.string.mq_friend_request_reject_action)
    val signedIn = initialAccountState.hasToken || initialAccountState.hasCredentials

    suspend fun ensureCloudToken(): String? {
        MQRegistry.loadAccountState(context).authToken?.takeIf { it.isNotBlank() }?.let { return it }
        val credentials = MQRegistry.loadAuthCredentials(context) ?: return null
        val result = withContext(Dispatchers.IO) {
            MQCloudClient.signInWithPassword(context = context, credentials = credentials)
        }
        if (result.success) {
            MQRegistry.saveAuthToken(context, result.token)
            return result.token
        }
        return null
    }

    suspend fun refreshIncomingRequests() {
        val currentUser = signedInAccount.trim()
        if (currentUser.isEmpty()) {
            incomingRequests = emptyList()
            requestsLoaded = false
            return
        }
        val token = ensureCloudToken()
        if (token.isNullOrBlank()) {
            incomingRequests = emptyList()
            requestsLoaded = false
            return
        }
        val (requests, result) = withContext(Dispatchers.IO) {
            MQCloudClient.fetchIncomingFriendRequests(
                context = context,
                authToken = token,
                userAccount = currentUser,
            )
        }
        incomingRequests = requests
        requestsLoaded = true
        if (result.failure != MQBootstrapFailure.NONE && result.message?.isNotBlank() == true) {
            statusText = result.message
        }
    }

    fun setMessage(message: String?, fallbackRes: Int) {
        statusText = message?.takeIf { it.isNotBlank() } ?: context.getString(fallbackRes)
    }

    suspend fun setFollowerEnabled(enabled: Boolean) {
        if (!enabled) {
            followerEnabled = false
            MQRegistry.saveFollowerEnabled(context, false)
            MQRegistry.disableFollowerSensors(context)
            withContext(Dispatchers.IO) { tk.glucodata.SensorBluetooth.updateDevices() }
            setMessage(
                context.getString(R.string.mq_follower_disabled_message),
                R.string.mq_follower_disabled_message,
            )
            return
        }

        val targetAccount = followerAccount.trim()
        if (targetAccount.isEmpty()) {
            followerEnabled = false
            setMessage(
                context.getString(R.string.mq_follower_requires_target),
                R.string.mq_follower_requires_target,
            )
            return
        }

        val currentUser = signedInAccount.trim()
        if (currentUser.isEmpty()) {
            followerEnabled = false
            setMessage(
                context.getString(R.string.mq_follower_requires_login),
                R.string.mq_follower_requires_login,
            )
            return
        }

        val token = ensureCloudToken()
        if (token.isNullOrBlank()) {
            followerEnabled = false
            setMessage(
                context.getString(R.string.mq_follower_requires_login),
                R.string.mq_follower_requires_login,
            )
            return
        }

        val lookup = withContext(Dispatchers.IO) {
            MQCloudClient.findFriendUser(
                context = context,
                authToken = token,
                userAccount = currentUser,
                friendAccount = targetAccount,
            )
        }
        if (!lookup.exists && lookup.failure != MQBootstrapFailure.NONE) {
            followerEnabled = false
            setMessage(lookup.message, R.string.mq_follower_account_not_found)
            return
        }
        if (!lookup.exists) {
            followerEnabled = false
            setMessage(
                context.getString(R.string.mq_follower_account_not_found),
                R.string.mq_follower_account_not_found,
            )
            return
        }

        val statusMessage = if (lookup.isFriend || lookup.friendshipState == 2) {
            MQRegistry.enableFollowerSensor(context, targetAccount, connectNow = true)
            if (lookup.isFriend) {
                context.getString(R.string.mq_follower_enabled_message)
            } else {
                context.getString(R.string.mq_follower_request_sent)
            }
        } else {
            val request = withContext(Dispatchers.IO) {
                MQCloudClient.addFriend(
                    context = context,
                    authToken = token,
                    userAccount = currentUser,
                    friendAccount = targetAccount,
                    permission = 2,
                    remark = "",
                    type = 2,
                )
            }
            if (!request.success) {
                followerEnabled = false
                setMessage(request.message, R.string.mq_follower_request_failed)
                return
            }
            MQRegistry.enableFollowerSensor(context, targetAccount, connectNow = true)
            context.getString(R.string.mq_follower_request_sent)
        }

        followerEnabled = true
        MQRegistry.saveFollowerEnabled(context, true)
        MQRegistry.saveFollowerAccount(context, targetAccount)
        withContext(Dispatchers.IO) { tk.glucodata.SensorBluetooth.updateDevices() }
        setMessage(statusMessage, R.string.mq_follower_enabled_message)
    }

    suspend fun runAction(label: String, block: suspend () -> Unit) {
        busyAction = label
        try { block() } finally { busyAction = null }
    }

    LaunchedEffect(signedInAccount) {
        if (signedIn) refreshIncomingRequests()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.mq_follower_settings_title),
                onNavigateBack = onBack,
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
            if (!signedIn) {
                NotSignedInCard()
            }

            MasterSwitchCard(
                title = stringResource(R.string.mq_follower_title),
                subtitle = stringResource(R.string.mq_follower_desc),
                checked = followerEnabled,
                onCheckedChange = { enabled ->
                    scope.launch {
                        runAction(followerLabel) { setFollowerEnabled(enabled) }
                    }
                },
                icon = Icons.Default.Cloud,
            )

            OutlinedTextField(
                value = followerAccount,
                onValueChange = {
                    followerAccount = it.trim()
                    MQRegistry.saveFollowerAccount(context, followerAccount)
                },
                label = { Text(stringResource(R.string.mq_follower_account_label)) },
                supportingText = { Text(stringResource(R.string.mq_follower_account_desc)) },
                singleLine = true,
                enabled = !followerEnabled && !isBusy && signedIn,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )

            if (busyAction != null || statusText.isNotBlank()) {
                StatusInline(busyAction = busyAction, statusText = statusText)
            }

            SectionLabel(
                text = stringResource(R.string.mq_friend_requests_title),
                topPadding = 8.dp,
            )

            Text(
                text = stringResource(R.string.mq_friend_requests_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedButton(
                onClick = {
                    scope.launch { runAction(refreshLabel) { refreshIncomingRequests() } }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = signedIn && !isBusy,
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.ContentPadding,
            ) {
                Text(refreshLabel)
            }

            when {
                requestsLoaded && incomingRequests.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.mq_friend_requests_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                incomingRequests.isNotEmpty() -> {
                    incomingRequests.forEach { request ->
                        IncomingRequestCard(
                            request = request,
                            enabled = !isBusy,
                            onApprove = {
                                scope.launch {
                                    runAction(approveLabel) {
                                        val token = ensureCloudToken()
                                        if (token.isNullOrBlank()) {
                                            setMessage(
                                                context.getString(R.string.mq_follower_requires_login),
                                                R.string.mq_follower_requires_login,
                                            )
                                            return@runAction
                                        }
                                        val result = withContext(Dispatchers.IO) {
                                            MQCloudClient.reviewFriendRequest(
                                                context = context,
                                                authToken = token,
                                                userAccount = signedInAccount.trim(),
                                                applyFriendId = request.requestId,
                                                approved = true,
                                                permission = 2,
                                                aliasName = request.name?.takeIf { it.isNotBlank() }
                                                    ?: request.phone,
                                            )
                                        }
                                        setMessage(result.message, R.string.mq_friend_request_approved_message)
                                        refreshIncomingRequests()
                                    }
                                }
                            },
                            onReject = {
                                scope.launch {
                                    runAction(rejectLabel) {
                                        val token = ensureCloudToken()
                                        if (token.isNullOrBlank()) {
                                            setMessage(
                                                context.getString(R.string.mq_follower_requires_login),
                                                R.string.mq_follower_requires_login,
                                            )
                                            return@runAction
                                        }
                                        val result = withContext(Dispatchers.IO) {
                                            MQCloudClient.reviewFriendRequest(
                                                context = context,
                                                authToken = token,
                                                userAccount = signedInAccount.trim(),
                                                applyFriendId = request.requestId,
                                                approved = false,
                                                permission = 2,
                                                aliasName = request.name?.takeIf { it.isNotBlank() }
                                                    ?: request.phone,
                                            )
                                        }
                                        setMessage(result.message, R.string.mq_friend_request_rejected_message)
                                        refreshIncomingRequests()
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NotSignedInCard() {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.mq_account_status_missing),
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = stringResource(R.string.mq_follower_requires_login),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun StatusInline(busyAction: String?, statusText: String) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        if (busyAction != null) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = busyAction,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun IncomingRequestCard(
    request: MQIncomingFriendRequest,
    enabled: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = request.name?.takeIf { it.isNotBlank() } ?: request.phone,
                style = MaterialTheme.typography.titleMediumEmphasized,
            )
            if (request.name?.isNotBlank() == true && request.phone.isNotBlank()) {
                Text(
                    text = request.phone,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            request.remark?.takeIf { it.isNotBlank() }?.let { remark ->
                Text(
                    text = stringResource(R.string.mq_friend_request_remark_format, remark),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.ContentPadding,
                ) { Text(stringResource(R.string.mq_friend_request_reject_action)) }
                FilledTonalButton(
                    onClick = onApprove,
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.ContentPadding,
                ) { Text(stringResource(R.string.mq_friend_request_approve_action)) }
            }
        }
    }
}
