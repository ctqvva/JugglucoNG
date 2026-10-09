package tk.glucodata.ui

import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tk.glucodata.Libre3NfcSettings
import tk.glucodata.Natives
import tk.glucodata.R
import tk.glucodata.ui.components.AppTopBar
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.MasterSwitchCard
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.SettingsSwitchItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibreViewSettingsScreen(navController: NavController) {
    val coroutineScope = rememberCoroutineScope()
    val initialAccountId = remember { Natives.getlibreAccountIDnumber() }
    val initialManualAccountId = remember { Natives.manualLibreAccountIDnumber() }

    var email by remember { mutableStateOf(Natives.getlibreemail() ?: "") }
    var password by remember { mutableStateOf(Natives.getlibrepass() ?: "") }
    var isActive by remember { mutableStateOf(Natives.getuselibreview()) }
    var isRussia by remember { mutableStateOf(Natives.getLibreCountry() == 4) }
    var libreCurrent by remember { mutableStateOf(Natives.getLibreCurrent()) }
    var libreIsViewed by remember { mutableStateOf(Natives.getLibreIsViewed()) }
    var sendNumbers by remember { mutableStateOf(Natives.getSendNumbers()) }
    var showPassword by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf(tk.glucodata.Libreview.getStatus()) }
    var accountId by remember { mutableLongStateOf(initialAccountId) }
    var isManualAccountId by remember { mutableStateOf(initialManualAccountId != -1L) }
    var manualAccountIdInput by remember {
        mutableStateOf(
            when {
                initialManualAccountId > 0L -> initialManualAccountId.toString()
                initialAccountId > 0L -> initialAccountId.toString()
                else -> ""
            }
        )
    }
    var manualAccountIdError by remember { mutableStateOf<String?>(null) }
    var nfcCommandMode by remember { mutableIntStateOf(Libre3NfcSettings.getMode()) }
    var isSendingNow by remember { mutableStateOf(false) }
    var isResendingData by remember { mutableStateOf(false) }
    var isFetchingAccountId by remember { mutableStateOf(false) }
    val hasCredentials = email.isNotBlank() && password.isNotBlank()
    val sendingNowText = stringResource(R.string.sending_now)
    val requestingAccountIdText = stringResource(R.string.requesting_account_id)
    val resendTriggeredText = stringResource(R.string.resend_triggered)
    val accountIdTimeoutText = stringResource(R.string.libre_setup_account_id_timeout)
    val accountIdObtainedText = stringResource(R.string.libre_setup_account_id_obtained)
    val manualText = stringResource(R.string.manual)
    val saveText = stringResource(R.string.save)
    val noAccountIdSpecifiedText = stringResource(R.string.noaccountidspecified).replace("\"", "").trim()
    val wrongFormatText = stringResource(R.string.wrongformat).replace("\"", "").trim()

    fun saveLibreViewSettings(includeUploadPreference: Boolean = true) {
        Natives.setlibreemail(email)
        Natives.setlibrepass(password)
        if (includeUploadPreference) {
            Natives.setuselibreview(isActive)
        }
        val currentCountry = Natives.getLibreCountry()
        Natives.setLibreCountry(
            if (isRussia) 4
            else if (currentCountry == 4) 0
            else currentCountry
        )
        Natives.setLibreCurrent(libreCurrent)
        Natives.setLibreIsViewed(libreIsViewed)
        Natives.setSendNumbers(sendNumbers)
        Libre3NfcSettings.setMode(nfcCommandMode)
    }

    fun refreshAccountIdState() {
        val resolvedAccountId = Natives.getlibreAccountIDnumber()
        accountId = resolvedAccountId
        if (!isManualAccountId) {
            manualAccountIdInput = if (resolvedAccountId > 0L) resolvedAccountId.toString() else ""
        }
    }

    fun clearManualAccountIdOverride() {
        isManualAccountId = false
        manualAccountIdError = null
        Natives.setlibreAccountIDnumber(-1L)
        refreshAccountIdState()
    }

    fun saveManualAccountId(): Boolean {
        val rawValue = manualAccountIdInput.trim()
        if (rawValue.isEmpty()) {
            manualAccountIdError = noAccountIdSpecifiedText
            return false
        }
        val parsedValue = rawValue.toLongOrNull()
        if (parsedValue == null || parsedValue <= 0L) {
            manualAccountIdError = listOf(wrongFormatText, rawValue).filter { it.isNotEmpty() }.joinToString(" ")
            return false
        }
        isManualAccountId = true
        manualAccountIdError = null
        manualAccountIdInput = parsedValue.toString()
        Natives.setlibreAccountIDnumber(parsedValue)
        refreshAccountIdState()
        statusText = ""
        return true
    }

    fun isTerminalStatus(currentStatus: String): Boolean {
        return currentStatus.contains("failed", ignoreCase = true) ||
            currentStatus.contains("error", ignoreCase = true) ||
            currentStatus.contains("locked", ignoreCase = true) ||
            currentStatus.contains("no credentials", ignoreCase = true) ||
            currentStatus.contains("ResponseCode", ignoreCase = true) ||
            currentStatus.contains("success", ignoreCase = true) ||
            currentStatus.contains("успеш", ignoreCase = true)
    }

    DisposableEffect(Unit) {
        onDispose { saveLibreViewSettings() }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.libreview_settings_title),
                onNavigateBack = { navController.popBackStack() },
            )
        }
    ) { padding ->
        val isBusy = isSendingNow || isResendingData || isFetchingAccountId
        val canSendData = isActive && !isBusy
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            MasterSwitchCard(
                title = stringResource(R.string.libreview_active),
                subtitle = stringResource(R.string.libreview_active_desc),
                checked = isActive,
                onCheckedChange = { isActive = it },
                icon = Icons.Default.Cloud
            )
            SectionLabel(
                text = stringResource(R.string.libreview_account_id),
                topPadding = 0.dp
            )
            LibreViewStatusCard(
                accountId = accountId,
                statusText = statusText
            )

            SettingsSwitchItem(
                title = manualText,
                checked = isManualAccountId,
                onCheckedChange = { enabled ->
                    if (enabled) {
                        isManualAccountId = true
                        manualAccountIdError = null
                        if (manualAccountIdInput.isBlank() && accountId > 0L) {
                            manualAccountIdInput = accountId.toString()
                        }
                    } else {
                        clearManualAccountIdOverride()
                    }
                },
                icon = Icons.Default.Key,
                iconTint = MaterialTheme.colorScheme.secondary,
                position = CardPosition.SINGLE,
                enabled = !isBusy
            )

            if (isManualAccountId) {
                Card(
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = manualAccountIdInput,
                            onValueChange = {
                                manualAccountIdInput = it
                                manualAccountIdError = null
                            },
                            label = { Text(stringResource(R.string.libreview_account_id)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            isError = manualAccountIdError != null,
                            supportingText = {
                                manualAccountIdError?.let { error ->
                                    Text(error)
                                }
                            },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = KeyboardType.Number
                            )
                        )

                        OutlinedButton(
                            onClick = { saveManualAccountId() },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isBusy,
                            shapes = ButtonDefaults.shapes(),
                            contentPadding = ButtonDefaults.ContentPadding
                        ) {
                            Text(saveText)
                        }
                    }
                }
            }

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text(stringResource(R.string.libreview_email)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Email
                )
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.libreview_password)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showPassword) {
                    androidx.compose.ui.text.input.VisualTransformation.None
                } else {
                    androidx.compose.ui.text.input.PasswordVisualTransformation()
                },
                trailingIcon = {
                    val image = if (showPassword) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                    IconButton(onClick = { showPassword = !showPassword }, shapes = IconButtonDefaults.shapes()) {
                        Icon(
                            imageVector = image,
                            contentDescription = if (showPassword) {
                                stringResource(R.string.hide_password)
                            } else {
                                stringResource(R.string.show_password)
                            }
                        )
                    }
                }
            )

            Button(
                onClick = {
                    saveLibreViewSettings()
                    tk.glucodata.Libreview.clearStatus()
                    val initialStatus = tk.glucodata.Libreview.getStatus()
                    statusText = sendingNowText
                    isSendingNow = true
                    Natives.wakelibreview(0)
                    coroutineScope.launch {
                        var elapsed = 0
                        var latestStatus = initialStatus
                        while (elapsed < 30_000) {
                            delay(500)
                            elapsed += 500
                            latestStatus = tk.glucodata.Libreview.getStatus()
                            refreshAccountIdState()
                            if (latestStatus.isNotEmpty() && latestStatus != initialStatus) {
                                statusText = latestStatus
                                break
                            }
                        }
                        if (statusText == sendingNowText && latestStatus.isNotEmpty()) {
                            statusText = latestStatus
                        }
                        isSendingNow = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = canSendData,
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.ContentPadding
            ) {
                if (isSendingNow) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.size(8.dp))
                }
                Text(stringResource(R.string.libreview_send_now))
            }

            OutlinedButton(
                onClick = {
                    saveLibreViewSettings()
                    tk.glucodata.Libreview.clearStatus()
                    val initialStatus = tk.glucodata.Libreview.getStatus()
                    statusText = resendTriggeredText
                    isResendingData = true
                    Natives.clearlibreFromMSec(0L)
                    Natives.wakelibreview(0)
                    coroutineScope.launch {
                        var elapsed = 0
                        var latestStatus = initialStatus
                        while (elapsed < 30_000) {
                            delay(500)
                            elapsed += 500
                            latestStatus = tk.glucodata.Libreview.getStatus()
                            refreshAccountIdState()
                            if (latestStatus.isNotEmpty() && latestStatus != initialStatus) {
                                statusText = latestStatus
                                break
                            }
                        }
                        if (statusText == resendTriggeredText && latestStatus.isNotEmpty()) {
                            statusText = latestStatus
                        }
                        isResendingData = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = canSendData,
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.ContentPadding
            ) {
                if (isResendingData) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.size(8.dp))
                }
                Text(stringResource(R.string.libreview_resend))
            }

            FilledTonalButton(
                onClick = {
                    saveLibreViewSettings(includeUploadPreference = false)
                    clearManualAccountIdOverride()
                    tk.glucodata.Libreview.clearStatus()
                    val initialStatus = tk.glucodata.Libreview.getStatus()
                    statusText = requestingAccountIdText
                    isFetchingAccountId = true
                    Natives.askServerforAccountID()
                    coroutineScope.launch {
                        var elapsed = 0
                        var receivedAccountId = false
                        while (elapsed < 30_000) {
                            delay(500)
                            elapsed += 500
                            val currentStatus = tk.glucodata.Libreview.getStatus()
                            refreshAccountIdState()
                            if (currentStatus.contains("AccountID", ignoreCase = true)) {
                                statusText = if (currentStatus != initialStatus) currentStatus else accountIdObtainedText
                                receivedAccountId = true
                                break
                            }
                            if (currentStatus.isNotEmpty()) {
                                statusText = currentStatus
                            }
                            if (isTerminalStatus(currentStatus)) break
                        }
                        if (!receivedAccountId) {
                            val finalStatus = tk.glucodata.Libreview.getStatus()
                            statusText = if (finalStatus.isNotEmpty()) finalStatus else accountIdTimeoutText
                        }
                        isFetchingAccountId = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = hasCredentials && !isBusy,
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.ContentPadding
            ) {
                if (isFetchingAccountId) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.size(8.dp))
                } else {
                    Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.size(8.dp))
                }
                Text(stringResource(R.string.libreview_get_account_id))
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SettingsSwitchItem(
                    title = stringResource(R.string.libreview_russia),
                    checked = isRussia,
                    onCheckedChange = { isRussia = it },
                    icon = Icons.Default.Public,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    position = CardPosition.TOP
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.libreview_current),
                    checked = libreCurrent,
                    onCheckedChange = { libreCurrent = it },
                    icon = Icons.Default.ShowChart,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    position = CardPosition.MIDDLE
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.libreview_is_viewed),
                    checked = libreIsViewed,
                    onCheckedChange = { libreIsViewed = it },
                    icon = Icons.Default.Visibility,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    position = CardPosition.MIDDLE
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.libreview_send_numbers),
                    checked = sendNumbers,
                    onCheckedChange = { sendNumbers = it },
                    icon = Icons.Default.Insights,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    position = CardPosition.BOTTOM
                )
            }
        }
    }
}

@Composable
private fun LibreViewStatusCard(
    accountId: Long,
    statusText: String
) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (accountId > 0L) {
                    stringResource(R.string.libreview_account_ready)
                } else {
                    stringResource(R.string.libreview_account_missing_desc)
                },
                style = MaterialTheme.typography.titleSmallEmphasized,)

            if (accountId > 0L) {
                Text(
                    text = "${stringResource(R.string.libreview_account_id)}: $accountId",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (statusText.isNotEmpty()) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
