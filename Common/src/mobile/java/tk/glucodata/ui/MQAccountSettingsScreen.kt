@file:OptIn(ExperimentalMaterial3Api::class)

package tk.glucodata.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PeopleAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
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
import tk.glucodata.drivers.mq.MQAuthCredentials
import tk.glucodata.drivers.mq.MQCloudClient
import tk.glucodata.drivers.mq.MQRegistry
import tk.glucodata.drivers.mq.MQVerifyCodeAction
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.MasterSwitchCard
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.SettingsItem

@Composable
fun MQAccountSettingsScreen(navController: NavController) {
    MQAccountSettingsContent(
        onBack = { navController.popBackStack() },
        onNavigateToFollower = { navController.navigate("settings/mq-follower") },
    )
}

@Composable
fun MQAccountSettingsContent(
    onBack: () -> Unit,
    onNavigateToFollower: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val initialAccountState = remember { MQRegistry.loadAccountState(context) }

    var phone by rememberSaveable { mutableStateOf(initialAccountState.phone) }
    var password by rememberSaveable { mutableStateOf(initialAccountState.password) }
    var apiBaseUrl by rememberSaveable { mutableStateOf(initialAccountState.apiBaseUrl) }
    var verificationCode by rememberSaveable { mutableStateOf("") }
    var cloudSyncEnabled by rememberSaveable {
        mutableStateOf(MQRegistry.isCloudSyncEnabled(context))
    }
    var authToken by rememberSaveable { mutableStateOf(initialAccountState.authToken.orEmpty()) }
    var statusText by rememberSaveable { mutableStateOf("") }
    var busyAction by remember { mutableStateOf<String?>(null) }
    var smsExpanded by rememberSaveable { mutableStateOf(false) }
    val followerEnabled = MQRegistry.isFollowerEnabled(context)
    val followerAccount = remember { MQRegistry.loadFollowerAccount(context) }

    val hasCredentials = phone.isNotBlank() && password.isNotBlank()
    val hasVerificationCode = verificationCode.isNotBlank()
    val hasAuthToken = authToken.isNotBlank()
    val isBusy = busyAction != null

    val signInLabel = stringResource(R.string.mq_account_sign_in_action)
    val checkSessionLabel = stringResource(R.string.mq_account_check_session_action)
    val signOutLabel = stringResource(R.string.mq_account_sign_out_action)
    val requestLoginCodeLabel = stringResource(R.string.mq_request_login_code_action)
    val requestSignupCodeLabel = stringResource(R.string.mq_request_signup_code_action)
    val requestResetCodeLabel = stringResource(R.string.mq_request_reset_code_action)
    val signInWithCodeLabel = stringResource(R.string.mq_account_sign_in_with_code_action)
    val createAccountLabel = stringResource(R.string.mq_account_register_action)
    val resetPasswordLabel = stringResource(R.string.mq_account_reset_password_action)

    fun persistAccountState() {
        MQRegistry.saveAccountState(
            context = context,
            phone = phone,
            password = password,
            apiBaseUrl = apiBaseUrl,
        )
        MQRegistry.saveCloudSyncEnabled(context, cloudSyncEnabled)
        authToken = MQRegistry.loadAccountState(context).authToken.orEmpty()
    }

    fun setResultMessage(message: String?, fallbackRes: Int) {
        statusText = message?.takeIf { it.isNotBlank() } ?: context.getString(fallbackRes)
    }

    suspend fun runCloudAction(actionLabel: String, block: suspend () -> Unit) {
        busyAction = actionLabel
        try {
            persistAccountState()
            block()
        } finally {
            busyAction = null
        }
    }

    DisposableEffect(Unit) {
        onDispose { persistAccountState() }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mq_account_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
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
            AccountStatusCard(
                hasAuthToken = hasAuthToken,
                hasCredentials = hasCredentials,
                phone = phone,
                cloudSyncEnabled = cloudSyncEnabled,
                followerEnabled = followerEnabled,
                followerAccount = followerAccount,
                busyAction = busyAction,
                statusText = statusText,
            )

            // ---- Account credentials ----
            SectionLabel(
                text = stringResource(R.string.mq_account_section_credentials),
                topPadding = 0.dp,
            )

            MQAccountFields(
                phone = phone,
                onPhoneChange = { phone = it },
                password = password,
                onPasswordChange = { password = it },
                apiBaseUrl = apiBaseUrl,
                onApiBaseUrlChange = { apiBaseUrl = it },
                supportingText = stringResource(R.string.mq_account_desc),
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    scope.launch {
                        runCloudAction(signInLabel) {
                            val result = withContext(Dispatchers.IO) {
                                MQCloudClient.signInWithPassword(
                                    context = context,
                                    credentials = MQAuthCredentials(
                                        account = phone.trim(),
                                        password = password,
                                    ),
                                )
                            }
                            if (result.success) {
                                MQRegistry.saveAuthToken(context, result.token)
                                authToken = result.token.orEmpty()
                            }
                            setResultMessage(result.message, R.string.mq_account_sign_in_action)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = hasCredentials && !isBusy,
            ) {
                Text(signInLabel)
            }

            // ---- Session actions (only when signed in / have credentials) ----
            if (hasAuthToken || hasCredentials) {
                SectionLabel(
                    text = stringResource(R.string.mq_account_section_session),
                    topPadding = 8.dp,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                runCloudAction(checkSessionLabel) {
                                    val result = withContext(Dispatchers.IO) {
                                        MQCloudClient.checkToken(
                                            context = context,
                                            authToken = authToken,
                                        )
                                    }
                                    setResultMessage(
                                        result.message,
                                        if (result.isValid) {
                                            R.string.mq_account_session_valid
                                        } else {
                                            R.string.mq_account_session_invalid
                                        },
                                    )
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = hasAuthToken && !isBusy,
                    ) { Text(checkSessionLabel) }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                runCloudAction(signOutLabel) {
                                    val result = withContext(Dispatchers.IO) {
                                        if (hasAuthToken && phone.isNotBlank()) {
                                            MQCloudClient.logout(
                                                context = context,
                                                phone = phone.trim(),
                                                authToken = authToken,
                                            )
                                        } else {
                                            tk.glucodata.drivers.mq.MQCloudActionResult(success = true)
                                        }
                                    }
                                    MQRegistry.clearAuthToken(context)
                                    authToken = ""
                                    setResultMessage(result.message, R.string.mq_account_sign_out_action)
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = (hasAuthToken || hasCredentials) && !isBusy,
                    ) { Text(signOutLabel) }
                }
            }

            // ---- Cloud sync ----
            SectionLabel(
                text = stringResource(R.string.mq_account_section_sync),
                topPadding = 8.dp,
            )

            MasterSwitchCard(
                title = stringResource(R.string.mq_sync_title),
                subtitle = stringResource(R.string.mq_sync_desc),
                checked = cloudSyncEnabled,
                onCheckedChange = { enabled ->
                    cloudSyncEnabled = enabled
                    MQRegistry.saveCloudSyncEnabled(context, enabled)
                },
                icon = Icons.Default.Cloud,
            )

            // ---- Followers (link to follower screen) ----
            SectionLabel(
                text = stringResource(R.string.mq_account_section_followers),
                topPadding = 8.dp,
            )

            val followerSubtitle = when {
                followerEnabled && followerAccount.isNotBlank() ->
                    stringResource(R.string.mq_followers_summary_following, followerAccount)
                followerEnabled -> stringResource(R.string.mq_follower_enabled_summary)
                else -> stringResource(R.string.mq_follower_settings_subtitle)
            }
            SettingsItem(
                title = stringResource(R.string.mq_follower_settings_title),
                subtitle = followerSubtitle,
                icon = Icons.Default.PeopleAlt,
                iconTint = MaterialTheme.colorScheme.primary,
                position = CardPosition.SINGLE,
                onClick = onNavigateToFollower,
                modifier = Modifier.fillMaxWidth(),
            )

            // ---- Advanced: SMS sign-in & recovery (collapsible) ----
            SectionLabel(
                text = stringResource(R.string.mq_account_section_advanced),
                topPadding = 8.dp,
            )

            CollapsibleCard(
                title = stringResource(R.string.mq_sms_section_title),
                subtitle = stringResource(R.string.mq_sms_recovery_subtitle),
                expanded = smsExpanded,
                onToggle = { smsExpanded = !smsExpanded },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = verificationCode,
                        onValueChange = { verificationCode = it.filter { c -> c.isLetterOrDigit() } },
                        label = { Text(stringResource(R.string.mq_sms_code_label)) },
                        supportingText = { Text(stringResource(R.string.mq_sms_code_desc)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    runCloudAction(requestLoginCodeLabel) {
                                        val result = withContext(Dispatchers.IO) {
                                            MQCloudClient.requestVerifyCode(
                                                context = context,
                                                phone = phone.trim(),
                                                action = MQVerifyCodeAction.LOGIN,
                                            )
                                        }
                                        setResultMessage(result.message, R.string.mq_request_login_code_action)
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = phone.isNotBlank() && !isBusy,
                        ) { Text(requestLoginCodeLabel) }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    runCloudAction(requestSignupCodeLabel) {
                                        val result = withContext(Dispatchers.IO) {
                                            MQCloudClient.requestVerifyCode(
                                                context = context,
                                                phone = phone.trim(),
                                                action = MQVerifyCodeAction.REGISTER,
                                            )
                                        }
                                        setResultMessage(result.message, R.string.mq_request_signup_code_action)
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = phone.isNotBlank() && !isBusy,
                        ) { Text(requestSignupCodeLabel) }
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                runCloudAction(requestResetCodeLabel) {
                                    val result = withContext(Dispatchers.IO) {
                                        MQCloudClient.requestVerifyCode(
                                            context = context,
                                            phone = phone.trim(),
                                            action = MQVerifyCodeAction.RESET_PASSWORD,
                                        )
                                    }
                                    setResultMessage(result.message, R.string.mq_request_reset_code_action)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = phone.isNotBlank() && !isBusy,
                    ) { Text(requestResetCodeLabel) }

                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                runCloudAction(signInWithCodeLabel) {
                                    val result = withContext(Dispatchers.IO) {
                                        MQCloudClient.signInWithCode(
                                            context = context,
                                            phone = phone.trim(),
                                            captcha = verificationCode.trim(),
                                        )
                                    }
                                    if (result.success) {
                                        MQRegistry.saveAuthToken(context, result.token)
                                        authToken = result.token.orEmpty()
                                    }
                                    setResultMessage(result.message, R.string.mq_account_sign_in_with_code_action)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = phone.isNotBlank() && hasVerificationCode && !isBusy,
                    ) { Text(signInWithCodeLabel) }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    runCloudAction(createAccountLabel) {
                                        val result = withContext(Dispatchers.IO) {
                                            MQCloudClient.registerByCode(
                                                context = context,
                                                phone = phone.trim(),
                                                captcha = verificationCode.trim(),
                                                password = password,
                                            )
                                        }
                                        if (result.success) {
                                            MQRegistry.saveAuthToken(context, result.token)
                                            authToken = result.token.orEmpty()
                                        }
                                        setResultMessage(result.message, R.string.mq_account_register_action)
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = phone.isNotBlank() && hasVerificationCode && password.isNotBlank() && !isBusy,
                        ) { Text(createAccountLabel) }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    runCloudAction(resetPasswordLabel) {
                                        val result = withContext(Dispatchers.IO) {
                                            MQCloudClient.resetPasswordByCode(
                                                context = context,
                                                phone = phone.trim(),
                                                captcha = verificationCode.trim(),
                                                newPassword = password,
                                            )
                                        }
                                        setResultMessage(result.message, R.string.mq_account_reset_password_action)
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = phone.isNotBlank() && hasVerificationCode && password.isNotBlank() && !isBusy,
                        ) { Text(resetPasswordLabel) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountStatusCard(
    hasAuthToken: Boolean,
    hasCredentials: Boolean,
    phone: String,
    cloudSyncEnabled: Boolean,
    followerEnabled: Boolean,
    followerAccount: String,
    busyAction: String?,
    statusText: String,
) {
    val visuals = when {
        hasAuthToken -> StatusVisuals(
            icon = Icons.Filled.CheckCircle,
            summaryRes = R.string.mq_account_status_signed_in,
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        hasCredentials -> StatusVisuals(
            icon = Icons.Filled.Cloud,
            summaryRes = R.string.mq_account_status_saved,
            container = MaterialTheme.colorScheme.secondaryContainer,
            content = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        else -> StatusVisuals(
            icon = Icons.Filled.ErrorOutline,
            summaryRes = R.string.mq_account_status_missing,
            container = MaterialTheme.colorScheme.surfaceContainerHigh,
            content = MaterialTheme.colorScheme.onSurface,
        )
    }

    val secondary = buildList {
        phone.trim().takeIf { it.isNotEmpty() }?.let(::add)
        add(
            if (cloudSyncEnabled) stringResource(R.string.mq_sync_enabled_summary)
            else stringResource(R.string.mq_sync_disabled_summary)
        )
        add(
            if (followerEnabled) {
                if (followerAccount.isNotBlank()) {
                    stringResource(R.string.mq_followers_summary_following, followerAccount)
                } else stringResource(R.string.mq_follower_enabled_summary)
            } else stringResource(R.string.mq_follower_disabled_summary)
        )
    }.joinToString(" · ")

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = visuals.container),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = visuals.icon,
                contentDescription = null,
                tint = visuals.content,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(visuals.summaryRes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = visuals.content,
                )
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = visuals.content.copy(alpha = 0.78f),
                )
                if (busyAction != null) {
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = visuals.content,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = busyAction,
                            style = MaterialTheme.typography.bodySmall,
                            color = visuals.content.copy(alpha = 0.78f),
                        )
                    }
                } else if (statusText.isNotBlank()) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = visuals.content.copy(alpha = 0.78f),
                    )
                }
            }
        }
    }
}

private data class StatusVisuals(
    val icon: ImageVector,
    val summaryRes: Int,
    val container: androidx.compose.ui.graphics.Color,
    val content: androidx.compose.ui.graphics.Color,
)

@Composable
private fun CollapsibleCard(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "collapsibleArrow",
    )
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(modifier = Modifier.rotate(rotation)) {
                    Icon(
                        imageVector = Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) {
                            stringResource(R.string.mq_sms_hide_action)
                        } else {
                            stringResource(R.string.mq_sms_show_action)
                        },
                    )
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 16.dp),
                ) {
                    content()
                }
            }
        }
    }
}
