package tk.glucodata.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import tk.glucodata.share.lumiflex.LumiflexCloudClient
import tk.glucodata.share.lumiflex.LumiflexCloudSettings
import tk.glucodata.ui.components.AppTopBar
import tk.glucodata.ui.components.MasterSwitchCard
import tk.glucodata.ui.components.SectionLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LumiflexCloudSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val initial = remember { LumiflexCloudSettings(context) }

    var enabled by remember { mutableStateOf(initial.enabled) }
    var account by remember { mutableStateOf(initial.account) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(initial.lastSyncMs) }
    var testing by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf<String?>(null) }
    var signedIn by remember { mutableStateOf(initial.token.isNotBlank()) }
    var signInGeneration by remember { mutableIntStateOf(0) }
    val hasCredentials = account.isNotBlank() && password.isNotBlank()

    fun save() {
        val settings = LumiflexCloudSettings(context)
        settings.enabled = enabled
        settings.account = account
        status = settings.lastSyncMs
    }

    fun login() {
        if (!hasCredentials) {
            testMessage = "Enter account and password"
            return
        }
        testing = true
        testMessage = null
        val generation = ++signInGeneration
        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) {
                LumiflexCloudClient().login(account, password, LumiflexCloudSettings(context))
            }
            withContext(Dispatchers.Main) {
                if (generation != signInGeneration) return@withContext
                testing = false
                when (result) {
                    is LumiflexCloudClient.LoginResult.Ok -> {
                        enabled = true
                        save()
                        signedIn = true
                        testMessage = "Signed in"
                        password = ""
                    }
                    is LumiflexCloudClient.LoginResult.Failed -> {
                        signedIn = false
                        testMessage = "Sign-in failed: ${result.message.ifBlank { result.kind.name }}"
                    }
                }
            }
        }
    }

    fun logout() {
        signInGeneration++
        val settings = LumiflexCloudSettings(context)
        settings.token = ""
        settings.enabled = false
        enabled = false
        signedIn = false
        testMessage = "Signed out"
        save()
    }

    DisposableEffect(Unit) {
        onDispose { if (account != initial.account || enabled != initial.enabled) save() }
    }

    LaunchedEffect(Unit) {
        while (true) {
            status = LumiflexCloudSettings(context).lastSyncMs
            signedIn = LumiflexCloudSettings(context).token.isNotBlank()
            delay(1_000)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.lumiflex_cloud_title),
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
            MasterSwitchCard(
                title = stringResource(R.string.lumiflex_cloud_title),
                subtitle = stringResource(R.string.lumiflex_cloud_subtitle),
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    if (!it) signInGeneration++
                    save()
                    testMessage = null
                },
                icon = Icons.Default.CloudUpload,
            )

            SectionLabel(text = stringResource(R.string.lumiflex_account), topPadding = 0.dp)

            OutlinedTextField(
                value = account,
                onValueChange = { account = it },
                label = { Text(stringResource(R.string.lumiflex_account_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.lumiflex_password)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showPassword) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(
                        onClick = { showPassword = !showPassword },
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(
                            imageVector = if (showPassword) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                            contentDescription = stringResource(
                                if (showPassword) R.string.lumiflex_hide_password else R.string.lumiflex_show_password,
                            ),
                        )
                    }
                },
            )

            if (!hasCredentials && enabled && !signedIn) {
                Text(
                    text = stringResource(R.string.lumiflex_credentials_required),
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
                enabled = !testing,
            ) {
                if (testing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                Text(
                    if (testing) stringResource(R.string.lumiflex_signing_in)
                    else stringResource(R.string.lumiflex_sign_in),
                )
            }

            if (testMessage != null) {
                Text(
                    text = testMessage!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = stringResource(
                    R.string.lumiflex_last_sync,
                    if (status > 0) {
                        DateFormat.getDateTimeInstance(
                            DateFormat.SHORT,
                            DateFormat.SHORT,
                            Locale.getDefault(),
                        ).format(Date(status))
                    } else {
                        stringResource(R.string.lumiflex_never)
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedButton(
                onClick = { navController.navigate("settings/lumiflex/followers") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                shapes = ButtonDefaults.shapes(),
                enabled = signedIn,
            ) {
                Text(stringResource(R.string.lumiflex_manage_followers))
            }

            OutlinedButton(
                onClick = { logout() },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(R.string.lumiflex_sign_out))
            }
        }
    }
}
