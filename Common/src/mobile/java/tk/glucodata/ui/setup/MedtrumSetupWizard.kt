// JugglucoNG — Medtrum EasySense setup wizard.
// Medtrum patches have no QR onboarding: the user types the serial printed on the patch,
// entered as a decimal number (the advert carries it little-endian in manufacturer 0x4781).

package tk.glucodata.ui.setup

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tk.glucodata.Log
import tk.glucodata.R
import tk.glucodata.ui.components.AppTopBar
import tk.glucodata.drivers.medtrum.MedtrumPersistence
import tk.glucodata.drivers.medtrum.MedtrumRegistry

private const val TAG = "MedtrumSetupWizard"

/** How long setup waits for an authenticated link before reporting "saved, still searching". */
private const val CONNECT_CONFIRM_TIMEOUT_MS = 90_000L
private const val CONNECT_POLL_MS = 500L

/**
 * SUCCESS is shown only once the patch accepted AUTH; SAVED when it did not come up in time (out
 * of range, still busy with another app) — the driver keeps trying in the background either way.
 */
private enum class MedtrumSetupStep { ENTRY, CONNECTING, SUCCESS, SAVED }

private enum class MedtrumAttachResult { CONNECTED, REJECTED_AUTH, REJECTED_DEVICE, TIMED_OUT }

private suspend fun awaitMedtrumLink(sensorId: String): MedtrumAttachResult =
    withTimeoutOrNull(CONNECT_CONFIRM_TIMEOUT_MS) {
        var result: MedtrumAttachResult? = null
        while (result == null) {
            val driver = MedtrumRegistry.findDriver(sensorId)
            result = when {
                // A device without the Medtrum service has stopped reconnecting on its own, so
                // waiting for the timeout would promise a connection that cannot come.
                driver?.isDeviceRejected() == true -> MedtrumAttachResult.REJECTED_DEVICE
                driver?.isAuthRejected() == true -> MedtrumAttachResult.REJECTED_AUTH
                driver?.isConnected() == true -> MedtrumAttachResult.CONNECTED
                else -> null
            }
            if (result == null) delay(CONNECT_POLL_MS)
        }
        result
    } ?: MedtrumAttachResult.TIMED_OUT

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MedtrumSetupWizard(
    onDismiss: () -> Unit,
    onNavigateToReadiness: () -> Unit = {},
    onComplete: () -> Unit,
) {
    val ui = rememberWizardUiMetrics()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(MedtrumSetupStep.ENTRY) }
    var serialInput by remember { mutableStateOf("") }
    var attachedSerial by remember { mutableStateOf("") }
    var pendingSerial by remember { mutableStateOf<Long?>(null) }
    var rejection by remember { mutableStateOf<MedtrumAttachResult?>(null) }
    var attachJob by remember { mutableStateOf<Job?>(null) }
    var attachAttempt by remember { mutableStateOf(0) }

    val normalized = serialInput.trim()
    val serialNumber = MedtrumPersistence.parseSerialNumber(normalized)
    val isValid = serialNumber != null

    fun startAttach(serial: Long) {
        attachedSerial = MedtrumPersistence.formatSerialHex(serial)
        rejection = null
        step = MedtrumSetupStep.CONNECTING
        // A replaced attempt must not touch the wizard any more: its timeout would otherwise
        // interrupt the new serial under the new label.
        val attempt = ++attachAttempt
        attachJob?.cancel()
        attachJob = scope.launch {
            try {
                val sensorId = MedtrumRegistry.addSensor(context, serial)
                val result = if (sensorId == null) MedtrumAttachResult.TIMED_OUT else awaitMedtrumLink(sensorId)
                Log.i(TAG, "Medtrum setup $attachedSerial: $result")
                if (attempt != attachAttempt) return@launch
                when (result) {
                    MedtrumAttachResult.CONNECTED -> step = MedtrumSetupStep.SUCCESS
                    MedtrumAttachResult.TIMED_OUT -> step = MedtrumSetupStep.SAVED
                    MedtrumAttachResult.REJECTED_AUTH,
                    MedtrumAttachResult.REJECTED_DEVICE -> {
                        rejection = result
                        step = MedtrumSetupStep.ENTRY
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to add Medtrum sensor: ${t.message}")
                if (attempt != attachAttempt) return@launch
                step = MedtrumSetupStep.ENTRY
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val pending = pendingSerial
        pendingSerial = null
        if (pending != null && hasBleScanPermissions(context)) startAttach(pending)
    }

    fun requestPermissionsAndAttach(serial: Long) {
        if (!hasBleScanPermissions(context)) {
            val required = requiredBleScanPermissions()
            if (required.isNotEmpty()) {
                pendingSerial = serial
                permissionLauncher.launch(required)
                return
            }
        }
        startAttach(serial)
    }

    BackHandler {
        if (step == MedtrumSetupStep.ENTRY) {
            onDismiss()
        } else {
            // Stop waiting on the old patch: its timeout must not interrupt the next attempt.
            attachJob?.cancel()
            attachJob = null
            attachAttempt++
            step = MedtrumSetupStep.ENTRY
        }
    }

    LaunchedEffect(step) {
        if (step == MedtrumSetupStep.SUCCESS) {
            delay(SENSOR_SETUP_SUCCESS_AUTO_ADVANCE_MS)
            onComplete()
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.medtrum_sensor),
                onNavigateBack = onDismiss,
                navigationContentDescription = stringResource(R.string.cancel),
            )
        }
    ) { padding ->
        AnimatedContent(
            targetState = step,
            modifier = Modifier.padding(padding),
            label = "MedtrumWizard",
        ) { current ->
            when (current) {
                MedtrumSetupStep.ENTRY -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = ui.horizontalPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top,
                ) {
                    Spacer(Modifier.height(ui.spacerMedium))

                    tk.glucodata.ui.CgmReadinessSetupBanner(onOpenReadiness = onNavigateToReadiness)

                    Spacer(Modifier.height(ui.spacerLarge))

                    Text(
                        text = stringResource(R.string.medtrum_sensor_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(Modifier.height(ui.spacerMedium))

                    OutlinedTextField(
                        value = serialInput,
                        onValueChange = { input ->
                            rejection = null
                            serialInput = input.filter {
                                it.isDigit() || it in 'a'..'f' || it in 'A'..'F'
                            }.take(8).uppercase()
                        },
                        label = { Text(stringResource(R.string.serial_number_label)) },
                        placeholder = { Text(stringResource(R.string.medtrum_serial_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        isError = (normalized.isNotEmpty() && !isValid) || rejection != null,
                        supportingText = when {
                            rejection == MedtrumAttachResult.REJECTED_DEVICE -> {
                                { Text(stringResource(R.string.medtrum_setup_device_rejected)) }
                            }
                            rejection == MedtrumAttachResult.REJECTED_AUTH -> {
                                { Text(stringResource(R.string.medtrum_setup_auth_failed)) }
                            }
                            isValid && serialNumber != null -> {
                                { Text(stringResource(R.string.medtrum_serial_echo, normalized, serialNumber)) }
                            }
                            else -> null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(ui.spacerMedium))

                    OutlinedButton(
                        onClick = { serialNumber?.let { requestPermissionsAndAttach(it) } },
                        enabled = isValid,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ui.buttonHeight),
                    ) {
                        Text(stringResource(R.string.confirm))
                    }
                }

                MedtrumSetupStep.CONNECTING -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    SensorSetupConnectingScreen(ui = ui, sensorLabel = attachedSerial.ifBlank { null })
                }

                MedtrumSetupStep.SUCCESS -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    SensorSetupSuccessScreen(ui = ui, sensorLabel = attachedSerial.ifBlank { null })
                }

                MedtrumSetupStep.SAVED -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = ui.horizontalPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        SensorSetupConnectingScreen(
                            ui = ui,
                            sensorLabel = attachedSerial.ifBlank { null },
                            title = stringResource(R.string.medtrum_setup_saved_title),
                            supportingText = stringResource(R.string.medtrum_setup_searching),
                        )
                    }
                    OutlinedButton(
                        onClick = onComplete,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ui.buttonHeight),
                    ) {
                        Text(stringResource(R.string.ok))
                    }
                    Spacer(Modifier.height(ui.spacerLarge))
                }
            }
        }
    }
}
