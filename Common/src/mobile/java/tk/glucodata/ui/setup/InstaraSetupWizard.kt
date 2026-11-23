package tk.glucodata.ui.setup

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import tk.glucodata.Log
import tk.glucodata.R
import tk.glucodata.drivers.instara.InstaraPersistence
import tk.glucodata.drivers.instara.InstaraRegistry

private const val TAG = "InstaraSetup"
private const val CONNECT_TIMEOUT_MS = 90_000L
private const val CONNECT_POLL_MS = 500L

private enum class Step { ENTRY, CONNECTING, SUCCESS, SAVED }

private suspend fun awaitLink(sensorId: String): Boolean =
    withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
        while (InstaraRegistry.findDriver(sensorId)?.isConnected() != true) {
            delay(CONNECT_POLL_MS)
        }
        true
    } ?: false

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstaraSetupWizard(
    onDismiss: () -> Unit,
    onNavigateToReadiness: () -> Unit = {},
    onComplete: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(Step.ENTRY) }
    var snInput by remember { mutableStateOf("") }
    var activationInput by remember {
        mutableStateOf(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()))
    }
    var pendingSn by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var attempt by remember { mutableStateOf(0) }

    val sn = snInput.trim()
    val valid = InstaraPersistence.isValidSn(sn)

    fun startAttach(code: String) {
        val activatedAt = runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).parse(activationInput)?.time
        }.getOrNull() ?: System.currentTimeMillis()
        InstaraPersistence.setSensorStartMs(context, activatedAt)
        step = Step.CONNECTING
        val current = ++attempt
        job?.cancel()
        job = scope.launch {
            try {
                val sensorId = InstaraRegistry.addSensor(context, code)
                val connected = sensorId != null && awaitLink(sensorId)
                if (current != attempt) return@launch
                step = if (connected) Step.SUCCESS else Step.SAVED
                Log.i(TAG, "Instara setup $code connected=$connected")
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.e(TAG, "Instara setup failed: ${t.message}")
                if (current == attempt) step = Step.ENTRY
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val code = pendingSn
        pendingSn = null
        if (code != null) startAttach(code)
    }

    fun requestPermissionsAndAttach(code: String) {
        if (!hasBleScanPermissions(context)) {
            val required = requiredBleScanPermissions()
            if (required.isNotEmpty()) {
                pendingSn = code
                permissionLauncher.launch(required)
                return
            }
        }
        startAttach(code)
    }

    BackHandler {
        if (step == Step.ENTRY) {
            onDismiss()
        } else {
            job?.cancel()
            job = null
            attempt++
            step = Step.ENTRY
        }
    }

    LaunchedEffect(step) {
        if (step == Step.SUCCESS) {
            delay(SENSOR_SETUP_SUCCESS_AUTO_ADVANCE_MS)
            onComplete()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.instara_sensor)) }) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.instara_sensor_desc),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = snInput,
                onValueChange = { snInput = it.uppercase().take(8) },
                label = { Text(stringResource(R.string.instara_sn_hint)) },
                singleLine = true,
                isError = snInput.isNotEmpty() && !valid,
                supportingText = {
                    if (snInput.isNotEmpty() && !valid) {
                        Text(stringResource(R.string.instara_sn_invalid))
                    }
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = activationInput,
                onValueChange = { activationInput = it },
                label = { Text(stringResource(R.string.instara_activation_time)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            when (step) {
                Step.ENTRY -> Button(
                    onClick = { if (valid) requestPermissionsAndAttach(sn) },
                    enabled = valid,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(android.R.string.ok)) }

                Step.CONNECTING -> Text(stringResource(R.string.instara_connecting))
                Step.SAVED -> {
                    Text(stringResource(R.string.instara_saved))
                    Button(onClick = onComplete, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(android.R.string.ok))
                    }
                }
                Step.SUCCESS -> Text(stringResource(R.string.instara_connecting))
            }
        }
    }
}
