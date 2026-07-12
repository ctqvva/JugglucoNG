package tk.glucodata.ui.setup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import tk.glucodata.R
import tk.glucodata.drivers.ottai.OttaiCloudClient
import tk.glucodata.drivers.ottai.LoginResult
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OttaiSetupWizard(
    onDismiss: () -> Unit,
    onNavigateToReadiness: (() -> Unit)? = null,
    onComplete: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var macAddress by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var loginResult by remember { mutableStateOf<LoginResult?>(null) }

    val client = remember { OttaiCloudClient() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ottai_setup_title)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Login section
            Text(stringResource(R.string.ottai_login_title), style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(stringResource(R.string.ottai_username)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.ottai_password)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )

            Button(
                onClick = {
                    scope.launch {
                        isLoading = true
                        statusMessage = ""
                        val result = client.passwordLogin(username, password)
                        loginResult = result
                        if (result != null) {
                            statusMessage = "Logged in as ${result.userId}"
                        } else {
                            statusMessage = "Login failed"
                        }
                        isLoading = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = username.isNotBlank() && password.isNotBlank() && !isLoading,
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Login, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ottai_sign_in))
                }
            }

            HorizontalDivider()

            // MAC entry + Connect
            Text(stringResource(R.string.ottai_sensor_mac), style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = macAddress,
                onValueChange = { macAddress = it.uppercase().take(12).filter { it in '0'..'9' || it in 'A'..'F' } },
                label = { Text(stringResource(R.string.ottai_mac_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            )

            Button(
                onClick = {
                    scope.launch {
                        isLoading = true
                        statusMessage = ""
                        try {
                            val resp = client.validateDevice(macAddress)
                            if (resp != null) {
                                statusMessage = "Device found: ${resp.optJSONObject("data")?.optString("deviceVersion") ?: "unknown"}"
                            } else {
                                statusMessage = "Device lookup failed"
                            }
                        } catch (e: Exception) {
                            statusMessage = "Error: ${e.message}"
                        }
                        isLoading = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = macAddress.length == 12 && loginResult != null && !isLoading,
            ) {
                Icon(Icons.Default.Bluetooth, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.ottai_fetch_materials))
            }

            if (statusMessage.isNotEmpty()) {
                Text(statusMessage, style = MaterialTheme.typography.bodyMedium,
                    color = if (statusMessage.startsWith("Error") || statusMessage.startsWith("Login failed"))
                        MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary)
            }
        }
    }
}
