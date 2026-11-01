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
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(initial.lastSyncMs) }
    var testing by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf<String?>(null) }

    fun save() {
        val s = ICanHealthCloudSettings(context)
        s.enabled = enabled
        s.account = account
        if (password.isNotBlank()) s.token = "" // force re-login
        status = s.lastSyncMs
    }

    DisposableEffect(Unit) {
        onDispose { if (account != initial.account || enabled != initial.enabled) save() }
    }

    LaunchedEffect(Unit) {
        while (true) {
            status = ICanHealthCloudSettings(context).lastSyncMs
            delay(1_000)
        }
    }

    val hasCredentials = account.isNotBlank() && password.isNotBlank()

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            AppTopBar(
                title = "iCan Health Cloud",
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
                title = "iCan Health cloud",
                subtitle = "Upload glucose to SinoCare iCan Health",
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    save()
                    testMessage = null
                },
                icon = Icons.Default.CloudUpload
            )

            SectionLabel(text = "Account", topPadding = 0.dp)

            OutlinedTextField(
                value = account,
                onValueChange = { account = it },
                label = { Text("Account (email)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
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
                        if (showPassword) "Hide password" else "Show password"
                    ) {
                        IconButton(onClick = { showPassword = !showPassword }, shapes = IconButtonDefaults.shapes()) {
                            Icon(
                                imageVector = image,
                                contentDescription = if (showPassword) "Hide password" else "Show password"
                            )
                        }
                    }
                }
            )

            if (!hasCredentials && enabled) {
                Text(
                    text = "Credentials required",
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
                Text("Save")
            }

            Text(
                text = "Last sync: " + if (status > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault()).format(Date(status)) else "never",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}