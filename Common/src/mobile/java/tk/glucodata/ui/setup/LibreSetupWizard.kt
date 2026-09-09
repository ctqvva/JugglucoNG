package tk.glucodata.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tk.glucodata.Libre3NfcSettings
import tk.glucodata.Natives
import tk.glucodata.R
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.MasterSwitchCard
import tk.glucodata.ui.components.SettingsItem
import tk.glucodata.ui.components.SettingsSwitchItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibreSetupWizard(
    onDismiss: () -> Unit,
    onNavigateToReadiness: () -> Unit = {},
    onScanNfc: () -> Unit
) {
    val ui = rememberWizardUiMetrics()
    val coroutineScope = rememberCoroutineScope()
    val initialAccountId = remember { Natives.getlibreAccountIDnumber() }
    val initialManualAccountId = remember { Natives.manualLibreAccountIDnumber() }
    var currentStep by remember { mutableIntStateOf(0) }

    var email by remember { mutableStateOf(Natives.getlibreemail() ?: "") }
    var password by remember { mutableStateOf(Natives.getlibrepass() ?: "") }
    var isActive by remember { mutableStateOf(Natives.getuselibreview()) }
    var isRussia by remember { mutableStateOf(Natives.getLibreCountry() == 4) }
    var showPassword by remember { mutableStateOf(false) }
    var accountIdValue by remember { mutableLongStateOf(initialAccountId) }
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
    var statusText by remember { mutableStateOf(tk.glucodata.Libreview.getStatus()) }
    var isSendingNow by remember { mutableStateOf(false) }
    var isFetchingAccountId by remember { mutableStateOf(false) }
    var isResendingData by remember { mutableStateOf(false) }
    var nfcCommandMode by remember { mutableIntStateOf(Libre3NfcSettings.getMode()) }
    var showAdvancedNfc by remember { mutableStateOf(false) }

    val requestingAccountIdText = stringResource(R.string.requesting_account_id)
    val sendingNowText = stringResource(R.string.sending_now)
    val accountIdTimeoutText = stringResource(R.string.libre_setup_account_id_timeout)
    val accountIdObtainedText = stringResource(R.string.libre_setup_account_id_obtained)
    val resendTriggeredText = stringResource(R.string.resend_triggered)
    val nfcAutoLabel = stringResource(R.string.libre3_nfc_command_auto)
    val nfcActivateLabel = stringResource(R.string.libre3_nfc_command_activate)
    val nfcSwitchReceiverLabel = stringResource(R.string.libre3_nfc_command_switch_receiver)
    val nfcAutoDescription = stringResource(R.string.libre3_nfc_command_auto_desc)
    val nfcActivateDescription = stringResource(R.string.libre3_nfc_command_activate_desc)
    val nfcSwitchReceiverDescription = stringResource(R.string.libre3_nfc_command_switch_receiver_desc)
    val manualText = stringResource(R.string.manual)
    val saveText = stringResource(R.string.save)
    val noAccountIdSpecifiedText = stringResource(R.string.noaccountidspecified).replace("\"", "").trim()
    val wrongFormatText = stringResource(R.string.wrongformat).replace("\"", "").trim()
    val nfcModes = remember {
        listOf(
            Libre3NfcSettings.MODE_AUTOMATIC,
            Libre3NfcSettings.MODE_ACTIVATE_A0,
            Libre3NfcSettings.MODE_SWITCH_RECEIVER_A8
        )
    }

    fun saveSettings(includeUploadPreference: Boolean = true) {
        Natives.setlibreemail(email)
        Natives.setlibrepass(password)
        if (includeUploadPreference) {
            Natives.setuselibreview(isActive)
        }
        Natives.setLibreCountry(if (isRussia) 4 else 0)
        Libre3NfcSettings.setMode(nfcCommandMode)
    }

    fun refreshAccountIdState() {
        val resolvedAccountId = Natives.getlibreAccountIDnumber()
        accountIdValue = resolvedAccountId
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

    fun leaveLibreViewStep() {
        saveSettings()
        refreshAccountIdState()
        statusText = tk.glucodata.Libreview.getStatus()
        currentStep = 0
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
        onDispose { saveSettings() }
    }

    BackHandler {
        if (currentStep > 0) currentStep-- else onDismiss()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.libre_setup_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (currentStep > 0) currentStep-- else onDismiss()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
                actions = {
                    if (currentStep == 1) {
                        TextButton(
                            onClick = ::leaveLibreViewStep,
                            enabled = !isSendingNow && !isFetchingAccountId && !isResendingData
                        ) {
                            Text(stringResource(R.string.libre_setup_done))
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LinearProgressIndicator(
                progress = { (currentStep + 1f) / 2f },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ui.horizontalPadding, vertical = ui.spacerSmall),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            when (currentStep) {
                0 -> {
                    val accountReady = accountIdValue > 0L
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = ui.horizontalPadding, vertical = ui.spacerSmall),
                        verticalArrangement = Arrangement.spacedBy(ui.spacerMedium)
                    ) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .size(if (ui.compact) 88.dp else 104.dp),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Nfc,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(if (ui.compact) 44.dp else 52.dp)
                                )
                            }
                        }

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.libre_setup_step_scan),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )

                            Text(
                                text = stringResource(R.string.libre_nfc_instruction),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }

                        tk.glucodata.ui.CgmReadinessSetupBanner(
                            includeLibreNfc = true,
                            onOpenReadiness = onNavigateToReadiness
                        )

                        Button(
                            onClick = {
                                saveSettings()
                                onScanNfc()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(ui.buttonHeight)
                        ) {
                            Icon(Icons.Default.Nfc, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.scan_libre_sensor))
                        }

                        SettingsItem(
                            title = stringResource(R.string.libre_setup_step_libreview),
                            subtitle = if (accountReady) {
                                stringResource(R.string.libreview_account_ready)
                            } else {
                                stringResource(R.string.libreview_account_missing_desc)
                            },
                            icon = Icons.Default.Cloud,
                            iconTint = MaterialTheme.colorScheme.primary,
                            position = CardPosition.SINGLE,
                            onClick = { currentStep = 1 }
                        )

                        LibreSetupAdvancedSection(
                            expanded = showAdvancedNfc,
                            onExpandedChange = { showAdvancedNfc = it },
                            selectedMode = nfcCommandMode,
                            onModeSelected = { mode ->
                                nfcCommandMode = mode
                                Libre3NfcSettings.setMode(mode)
                            },
                            nfcModes = nfcModes,
                            nfcAutoLabel = nfcAutoLabel,
                            nfcActivateLabel = nfcActivateLabel,
                            nfcSwitchReceiverLabel = nfcSwitchReceiverLabel,
                            nfcAutoDescription = nfcAutoDescription,
                            nfcActivateDescription = nfcActivateDescription,
                            nfcSwitchReceiverDescription = nfcSwitchReceiverDescription
                        )
                    }
                }

                1 -> {
                    val hasCredentials = email.isNotBlank() && password.isNotBlank()
                    val isBusy = isSendingNow || isFetchingAccountId || isResendingData
                    val canSendData = isActive && !isBusy
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = ui.horizontalPadding, vertical = ui.spacerSmall),
                        verticalArrangement = Arrangement.spacedBy(ui.spacerMedium)
                    ) {
                        Text(
                            text = stringResource(R.string.libre_setup_step_libreview),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold
                        )

                        Text(
                            text = stringResource(R.string.libre_setup_step_libreview_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (accountIdValue > 0L || statusText.isNotEmpty()) {
                            Text(
                                text = if (accountIdValue > 0L) {
                                    "${stringResource(R.string.libreview_account_id)}: $accountIdValue"
                                } else {
                                    statusText
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        MasterSwitchCard(
                            title = stringResource(R.string.libreview_active),
                            subtitle = stringResource(R.string.libreview_active_desc),
                            checked = isActive,
                            onCheckedChange = { isActive = it },
                            icon = Icons.Default.Cloud
                        )

                        SettingsSwitchItem(
                            title = stringResource(R.string.libreview_russia),
                            checked = isRussia,
                            onCheckedChange = { isRussia = it },
                            icon = Icons.Default.Public,
                            iconTint = MaterialTheme.colorScheme.secondary,
                            position = CardPosition.SINGLE
                        )

                        SettingsSwitchItem(
                            title = manualText,
                            checked = isManualAccountId,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    isManualAccountId = true
                                    manualAccountIdError = null
                                    if (manualAccountIdInput.isBlank() && accountIdValue > 0L) {
                                        manualAccountIdInput = accountIdValue.toString()
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
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
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
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                    )

                                    OutlinedButton(
                                        onClick = { saveManualAccountId() },
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = !isBusy
                                    ) {
                                        Text(saveText)
                                    }
                                }
                            }
                        }

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedTextField(
                                value = email,
                                onValueChange = { email = it },
                                label = { Text(stringResource(R.string.libreview_email)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                            )

                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text(stringResource(R.string.libreview_password)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    val image = if (showPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff
                                    IconButton(onClick = { showPassword = !showPassword }) {
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
                        }

                        Button(
                            onClick = {
                                saveSettings()
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
                            enabled = canSendData
                        ) {
                            if (isSendingNow) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                            } else {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(stringResource(R.string.save))
                        }

                        OutlinedButton(
                            onClick = {
                                saveSettings()
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
                            enabled = canSendData
                        ) {
                            if (isResendingData) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            }
                            Spacer(modifier = Modifier.width(if (isResendingData) 8.dp else 0.dp))
                            Text(stringResource(R.string.libreview_resend))
                        }

                        FilledTonalButton(
                            onClick = {
                                saveSettings(includeUploadPreference = false)
                                clearManualAccountIdOverride()
                                tk.glucodata.Libreview.clearStatus()
                                val initialStatus = tk.glucodata.Libreview.getStatus()
                                Natives.askServerforAccountID()
                                statusText = requestingAccountIdText
                                isFetchingAccountId = true
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
                                        val isTerminal = isTerminalStatus(currentStatus)
                                        if (isTerminal) break
                                    }
                                    if (!receivedAccountId) {
                                        val finalStatus = tk.glucodata.Libreview.getStatus()
                                        statusText = if (finalStatus.isNotEmpty()) finalStatus else accountIdTimeoutText
                                    }
                                    isFetchingAccountId = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = hasCredentials && !isBusy
                        ) {
                            if (isFetchingAccountId) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.libreview_get_account_id))
                        }

                    }
                }
            }
        }
    }
}

@Composable
private fun LibreSetupAdvancedSection(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    selectedMode: Int,
    onModeSelected: (Int) -> Unit,
    nfcModes: List<Int>,
    nfcAutoLabel: String,
    nfcActivateLabel: String,
    nfcSwitchReceiverLabel: String,
    nfcAutoDescription: String,
    nfcActivateDescription: String,
    nfcSwitchReceiverDescription: String
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "libreSetupAdvancedChevron"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onExpandedChange(!expanded) }
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.libre3_nfc_command_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = libreNfcModeDescription(
                        mode = selectedMode,
                        nfcAutoDescription = nfcAutoDescription,
                        nfcActivateDescription = nfcActivateDescription,
                        nfcSwitchReceiverDescription = nfcSwitchReceiverDescription
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.graphicsLayer { rotationZ = chevronRotation }
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                nfcModes.forEachIndexed { index, mode ->
                    val isSelected = selectedMode == mode
                    val title = libreNfcModeLabel(
                        mode = mode,
                        nfcAutoLabel = nfcAutoLabel,
                        nfcActivateLabel = nfcActivateLabel,
                        nfcSwitchReceiverLabel = nfcSwitchReceiverLabel
                    )
                    val description = libreNfcModeDescription(
                        mode = mode,
                        nfcAutoDescription = nfcAutoDescription,
                        nfcActivateDescription = nfcActivateDescription,
                        nfcSwitchReceiverDescription = nfcSwitchReceiverDescription
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isSelected) {
                                    MaterialTheme.colorScheme.inverseOnSurface
                                } else {
                                    MaterialTheme.colorScheme.onSecondary
                                }
                            )
                            .clickable { onModeSelected(mode) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.width(48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { onModeSelected(mode) }
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                            )
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (index < nfcModes.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    }
                }
            }
        }
    }
}

private fun libreNfcModeDescription(
    mode: Int,
    nfcAutoDescription: String,
    nfcActivateDescription: String,
    nfcSwitchReceiverDescription: String
): String {
    return when (mode) {
        Libre3NfcSettings.MODE_ACTIVATE_A0 -> nfcActivateDescription
        Libre3NfcSettings.MODE_SWITCH_RECEIVER_A8 -> nfcSwitchReceiverDescription
        else -> nfcAutoDescription
    }
}

private fun libreNfcModeLabel(
    mode: Int,
    nfcAutoLabel: String,
    nfcActivateLabel: String,
    nfcSwitchReceiverLabel: String
): String {
    return when (mode) {
        Libre3NfcSettings.MODE_ACTIVATE_A0 -> nfcActivateLabel
        Libre3NfcSettings.MODE_SWITCH_RECEIVER_A8 -> nfcSwitchReceiverLabel
        else -> nfcAutoLabel
    }
}
