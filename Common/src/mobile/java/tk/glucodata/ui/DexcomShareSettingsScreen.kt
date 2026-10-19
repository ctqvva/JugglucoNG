package tk.glucodata.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold



import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.R
import tk.glucodata.share.DexcomShareRegion
import tk.glucodata.share.DexcomShareSettings
import tk.glucodata.share.DexcomShareUploader
import tk.glucodata.ui.components.AppTopBar
import tk.glucodata.ui.components.IconButtonTooltip
import tk.glucodata.ui.components.MasterSwitchCard
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.util.ConnectedButtonGroup

/**
 * Dexcom Share is an unofficial, credential-carrying output, so the account, the password, the
 * region and the receiver serial all live here and never leave the phone. The screen keeps the
 * status card alive while it is open, because the uploader only reports what the last flush did.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DexcomShareSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val initial = remember { DexcomShareSettings.load(context) }

    var enabled by remember { mutableStateOf(initial.enabled) }
    var account by remember { mutableStateOf(initial.account) }
    var password by remember { mutableStateOf(initial.password) }
    var region by remember { mutableStateOf(initial.region) }
    var serial by remember { mutableStateOf(initial.serial) }
    var showPassword by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(DexcomShareSettings.status(context)) }
    var testing by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf<String?>(null) }

    fun currentConfig() = DexcomShareSettings.Config(
        enabled = enabled,
        account = account,
        password = password,
        region = region,
        serial = serial,
    )

    fun unchangedSinceOpen(): Boolean =
        enabled == initial.enabled &&
            account == initial.account &&
            password == initial.password &&
            region == initial.region &&
            DexcomShareSettings.normalizeSerial(serial) == DexcomShareSettings.normalizeSerial(initial.serial)

    /**
     * The screen no longer decides whether a blocked login may be released: [DexcomShareSettings.save]
     * does that itself, and only when the account or the password changed.
     *
     * @param flush false before a Test, which logs in itself: one tap must cost one login.
     */
    fun save(flush: Boolean = true) {
        // Saves, and fences off any login still running for the previous settings.
        DexcomShareUploader.applySettings(context, currentConfig(), flush)
        status = DexcomShareSettings.status(context)
    }

    DisposableEffect(Unit) {
        // Editing then leaving the screen is a save; merely visiting it is not.
        onDispose { if (!unchangedSinceOpen()) save() }
    }

    // The uploader writes its status from the send thread and the prefs file is the only channel
    // between them, so the card is polled. Nothing here decrypts anything.
    LaunchedEffect(Unit) {
        while (true) {
            status = DexcomShareSettings.status(context)
            delay(1_000)
        }
    }

    val hasCredentials = account.isNotBlank() && password.isNotBlank()
    val credentialsRequiredText = stringResource(R.string.dexcom_share_credentials_required)
    val testOkText = stringResource(R.string.dexcom_share_test_ok)

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.dexcom_share_settings_title),
                onNavigateBack = { navController.popBackStack() },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            MasterSwitchCard(
                title = stringResource(R.string.dexcom_share_active),
                subtitle = stringResource(R.string.dexcom_share_active_desc),
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    // Saving on the switch stops the stream at once when it is turned off.
                    save()
                    testMessage = null
                },
                icon = Icons.Default.CloudUpload
            )

            DexcomShareStatusCard(status)

            SectionLabel(text = stringResource(R.string.dexcom_share_account), topPadding = 0.dp)

            OutlinedTextField(
                value = account,
                onValueChange = { account = it },
                label = { Text(stringResource(R.string.dexcom_share_account)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.dexcom_share_password)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showPassword) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    val image = if (showPassword) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                    IconButtonTooltip(
                        if (showPassword) stringResource(R.string.hide_password) else stringResource(R.string.show_password)
                    ) {
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
                }
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.dexcom_share_region),
                    style = MaterialTheme.typography.labelLarge,
                )
                val regions = listOf(DexcomShareRegion.OUS, DexcomShareRegion.US)
                val outsideUsLabel = stringResource(R.string.dexcom_share_region_ous)
                val usLabel = stringResource(R.string.dexcom_share_region_us)
                ConnectedButtonGroup(
                    options = regions,
                    selectedOption = region,
                    onOptionSelected = { region = it },
                    label = { option ->
                        Text(if (option == DexcomShareRegion.US) usLabel else outsideUsLabel)
                    },
                    labelText = { option ->
                        if (option == DexcomShareRegion.US) usLabel else outsideUsLabel
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            OutlinedTextField(
                value = serial,
                onValueChange = { serial = it },
                label = { Text(stringResource(R.string.dexcom_share_serial)) },
                placeholder = { Text(stringResource(R.string.dexcom_share_serial_hint)) },
                supportingText = { Text(stringResource(R.string.dexcom_share_serial_helper)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            OutlinedButton(
                onClick = {
                    save(flush = false)
                    testMessage = null
                    testing = true
                    DexcomShareUploader.testConnection(context, currentConfig()) { result ->
                        // The callback arrives on the send thread; Compose state needs the main one.
                        coroutineScope.launch {
                            withContext(Dispatchers.Main) {
                                testing = false
                                testMessage = if (result.ok) testOkText else result.message
                                status = DexcomShareSettings.status(context)
                            }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                enabled = hasCredentials && !testing,
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.ContentPadding,
            ) {
                if (testing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.size(8.dp))
                }
                Text(stringResource(R.string.dexcom_share_test))
            }

            if (testMessage != null) {
                Text(
                    text = testMessage!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (testMessage == testOkText) {
                        StatusPalette.success()
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }

            if (!hasCredentials && enabled) {
                Text(
                    text = credentialsRequiredText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = { save(); testMessage = null },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.ContentPadding,
            ) {
                Text(stringResource(R.string.save))
            }

            Text(
                text = stringResource(R.string.dexcom_share_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DexcomShareStatusCard(status: DexcomShareSettings.Status) {
    val context = LocalContext.current

    fun timestamp(epochMillis: Long): String {
        if (epochMillis <= 0L) return context.getString(R.string.dexcom_share_status_never)
        return DateFormat.getDateTimeInstance(
            DateFormat.SHORT,
            DateFormat.SHORT,
            Locale.getDefault()
        ).format(Date(epochMillis))
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.status),
                style = MaterialTheme.typography.titleMediumEmphasized,
            )
            if (status.authBlocked) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.dexcom_share_auth_blocked),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Text(
                text = stringResource(
                    R.string.dexcom_share_status_last_success,
                    timestamp(status.lastSuccessMs)
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(
                    R.string.dexcom_share_status_last_attempt,
                    timestamp(status.lastAttemptMs)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.dexcom_share_pending, status.pendingCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (status.lastError.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.dexcom_share_status_error, status.lastError),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
