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
import tk.glucodata.share.ican.ICanHealthCloudClient
import tk.glucodata.share.ican.ICanHealthCloudSettings
import tk.glucodata.share.ican.ICanHealthCloudUploader
import tk.glucodata.ui.components.AppTopBar
import tk.glucodata.ui.components.IconButtonTooltip
import tk.glucodata.ui.components.MasterSwitchCard
import tk.glucodata.ui.components.SectionLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ICanHealthCloudSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val initial = remember { ICanHealthCloudSettings(context) }

    var enabled by remember { mutableStateOf(initial.enabled) }
    var account by remember { mutableStateOf(initial.account) }
    var password by remember { mutableStateOf(initial.password) }
    var showPassword by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(initial.lastSyncMs) }
    var testing by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf<String?>(null) }
    val hasCredentials = account.isNotBlank() && password.isNotBlank()
    var hasSession by remember { mutableStateOf(initial.token.isNotBlank()) }
    var storedAccount by remember { mutableStateOf(initial.account) }
    val passwordLocked = hasSession && account.trim() == storedAccount.trim()

    fun save() {
        val s = ICanHealthCloudSettings(context)
        s.enabled = enabled
        status = s.lastSyncMs
    }

    fun login() {
        if (!hasCredentials) {
            testMessage = context.getString(R.string.ican_health_credentials_required)
            return
        }
        val loginAccount = account.trim()
        val loginPassword = password
        testing = true
        testMessage = null
        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) {
                ICanHealthCloudClient().login(loginAccount, loginPassword)
            }
            withContext(Dispatchers.Main) {
                when (result) {
                    is ICanHealthCloudClient.LoginResult.Ok -> {
                        val s = ICanHealthCloudSettings(context)
                        val saved = withContext(Dispatchers.IO) {
                            runCatching { s.installLogin(loginAccount, loginPassword, result) }
                        }
                        if (saved.isFailure) {
                            testing = false
                            testMessage = context.getString(R.string.ican_health_secure_storage_failed)
                            return@withContext
                        }
                        hasSession = true
                        storedAccount = loginAccount
                        s.enabled = enabled
                        val loginAccountKey = s.accountKey
                        status = s.lastSyncMs
                        testMessage = context.getString(R.string.ican_health_logged_in)
                        val since = if (s.lastSyncMs > 0L) s.lastSyncMs else
                            System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L
                        coroutineScope.launch(Dispatchers.IO) {
                            runCatching {
                                val serial = tk.glucodata.SensorIdentity.resolveMainSensor() ?: return@runCatching
                                val roomSerial = tk.glucodata.SensorIdentity.resolveRoomStorageSensorId(serial) ?: serial
                                val repo = tk.glucodata.data.HistoryRepository(context)
                                val readings = repo.storedReadingsByMinuteForSensor(roomSerial, since).values
                                val rows = readings.map {
                                    tk.glucodata.XdripBackfill.Row(
                                        it.timestamp,
                                        it.value,
                                        it.rawValue,
                                        it.rate,
                                        it.firstStoredAt,
                                    )
                                }
                                val displayed = tk.glucodata.XdripBackfill.displayedRows(roomSerial, rows)
                                ICanHealthCloudUploader.onHistoryStored(
                                    roomSerial,
                                    displayed.map {
                                        ICanHealthCloudUploader.Row(roomSerial, it.timeMs, it.mgdl, 0)
                                    },
                                    expectedAccountKey = loginAccountKey,
                                )
                            }
                        }
                    }
                    is ICanHealthCloudClient.LoginResult.Failed -> {
                        testMessage = context.getString(R.string.ican_health_login_failed, result.message.ifBlank { result.kind.name })
                    }
                }
                testing = false
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { if (account != initial.account || enabled != initial.enabled) save() }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val current = ICanHealthCloudSettings(context)
            status = current.lastSyncMs
            hasSession = current.token.isNotBlank()
            delay(1_000)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.ican_health_settings_title),
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
                title = stringResource(R.string.ican_health_title),
                subtitle = stringResource(R.string.ican_health_active_desc),
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    save()
                    testMessage = null
                },
                icon = Icons.Default.CloudUpload
            )

            SectionLabel(text = stringResource(R.string.ican_health_section_account), topPadding = 0.dp)

            OutlinedTextField(
                value = account,
                onValueChange = { account = it },
                label = { Text(stringResource(R.string.ican_health_account)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.ican_health_password)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !passwordLocked,
                visualTransformation = if (showPassword) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    val image = if (showPassword) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                    IconButtonTooltip(
                        if (showPassword) stringResource(R.string.ican_health_hide_password) else stringResource(R.string.ican_health_show_password)
                    ) {
                        IconButton(onClick = { showPassword = !showPassword }, shapes = IconButtonDefaults.shapes()) {
                            Icon(
                                imageVector = image,
                                contentDescription = if (showPassword) stringResource(R.string.ican_health_hide_password) else stringResource(R.string.ican_health_show_password)
                            )
                        }
                    }
                }
            )

            if (!hasCredentials && !hasSession && enabled) {
                Text(
                    text = stringResource(R.string.ican_health_credentials_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = { login() },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.ContentPadding,
                enabled = !testing,
            ) {
                if (testing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.size(8.dp))
                }
                Text(if (testing) stringResource(R.string.ican_health_signing_in) else stringResource(R.string.ican_health_sign_in))
            }

            if (testMessage != null) {
                Text(
                    text = testMessage!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Text(
                text = stringResource(
                    R.string.ican_health_last_sync,
                    if (status > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault()).format(Date(status))
                    else stringResource(R.string.ican_health_never),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedButton(
                shapes = ButtonDefaults.shapes(),
                onClick = { navController.navigate("settings/ican-health/followers") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
            ) {
                Text(stringResource(R.string.ican_health_manage_followers))
            }
        }
    }
}