// JugglucoNG — iCanHealth (Sinocare iCan i3/i6/i7) Setup Wizard
// iCan setup is now QR/manual onboarding-first:
// - scan the onboarding SN / active code
// - let the driver discover the BLE peripheral in background
// Account ID stays hidden from the normal flow; bundled keys are selected automatically.

package tk.glucodata.ui.setup

import android.widget.Toast
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tk.glucodata.ui.components.AppTopBar
import tk.glucodata.Log
import tk.glucodata.R
import tk.glucodata.drivers.icanhealth.ICanHealthConstants
import tk.glucodata.drivers.icanhealth.ICanHealthRegistry

private const val ICAN_HEALTH_ONBOARDING_EXAMPLE = "726022F50005"

private enum class ICanHealthSetupStep {
    ONBOARDING,
    CONNECTING,
    SUCCESS,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ICanHealthSetupWizard(
    onDismiss: () -> Unit,
    onNavigateToReadiness: () -> Unit = {},
    onComplete: () -> Unit
) {
    val tag = "ICanHealthSetupWizard"
    val ui = rememberWizardUiMetrics()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentStep by remember { mutableStateOf(ICanHealthSetupStep.ONBOARDING) }
    var lastOnboardingCode by remember { mutableStateOf("") }
    var selectedSensorLabel by remember { mutableStateOf("") }
    var showManualEntry by remember { mutableStateOf(false) }
    var pendingAttachCode by remember { mutableStateOf<String?>(null) }

    fun normalizeOnboardingInput(raw: String): String =
        ICanHealthConstants.normalizeOnboardingDeviceSn(raw)

    fun canAttach(code: String): Boolean {
        val normalized = normalizeOnboardingInput(code)
        return normalized.length in 8..13
    }

    fun startAttach(normalizedOnboardingCode: String) {
        val normalized = normalizeOnboardingInput(normalizedOnboardingCode)
        if (!canAttach(normalized)) {
            return
        }
        selectedSensorLabel = normalized
        currentStep = ICanHealthSetupStep.CONNECTING
        scope.launch {
            try {
                ICanHealthRegistry.addSensor(
                    context,
                    displayName = null,
                    address = "",
                    null,
                    normalized,
                    null
                )
                kotlinx.coroutines.delay(2000)
                currentStep = ICanHealthSetupStep.SUCCESS
            } catch (t: Throwable) {
                Log.e(tag, "Failed to add iCanHealth sensor: ${t.message}")
                Toast.makeText(
                    context,
                    context.getString(R.string.nobluetooth),
                    Toast.LENGTH_LONG
                ).show()
                currentStep = ICanHealthSetupStep.ONBOARDING
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val pending = pendingAttachCode
        pendingAttachCode = null
        if (pending != null && hasBleScanPermissions(context)) {
            startAttach(pending)
        }
    }

    fun requestPermissionsAndAttach(rawCode: String) {
        val normalized = normalizeOnboardingInput(rawCode)
        lastOnboardingCode = normalized
        if (!canAttach(normalized)) {
            return
        }
        if (!hasBleScanPermissions(context)) {
            val required = requiredBleScanPermissions()
            if (required.isNotEmpty()) {
                pendingAttachCode = normalized
                permissionLauncher.launch(required)
                return
            }
        }
        startAttach(normalized)
    }

    val launchFullscreenScan = rememberUnifiedQrScanLauncher(
        requestCode = tk.glucodata.MainActivity.REQUEST_BARCODE,
        title = context.getString(R.string.icanhealth_sensor),
        onScanResult = ::requestPermissionsAndAttach
    )

    BackHandler {
        when {
            showManualEntry -> showManualEntry = false
            currentStep == ICanHealthSetupStep.ONBOARDING -> onDismiss()
            else -> currentStep = ICanHealthSetupStep.ONBOARDING
        }
    }

    LaunchedEffect(currentStep) {
        if (currentStep == ICanHealthSetupStep.SUCCESS) {
            delay(SENSOR_SETUP_SUCCESS_AUTO_ADVANCE_MS)
            onComplete()
        }
    }

    if (showManualEntry) {
        ICanHealthManualEntryDialog(
            initialValue = lastOnboardingCode,
            onDismiss = { showManualEntry = false },
            onConfirm = { normalized ->
                showManualEntry = false
                requestPermissionsAndAttach(normalized)
            }
        )
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.icanhealth_sensor),
                onNavigateBack = onDismiss,
                navigationContentDescription = stringResource(R.string.cancel),
            )
        }
    ) { padding ->
        AnimatedContent(
            targetState = currentStep,
            modifier = Modifier.padding(padding),
            label = "ICanHealthWizard"
        ) { step ->
            when (step) {
                ICanHealthSetupStep.ONBOARDING -> ICanHealthOnboardingStep(
                    ui = ui,
                    onNavigateToReadiness = onNavigateToReadiness,
                    onInlineScanResult = ::requestPermissionsAndAttach,
                    onLaunchFullscreenScan = launchFullscreenScan,
                    onShowManualEntry = { showManualEntry = true }
                )

                ICanHealthSetupStep.CONNECTING -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    SensorSetupConnectingScreen(
                        ui = ui,
                        sensorLabel = selectedSensorLabel.ifBlank { null }
                    )
                }

                ICanHealthSetupStep.SUCCESS -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    SensorSetupSuccessScreen(
                        ui = ui,
                        sensorLabel = selectedSensorLabel.ifBlank { null }
                    )
                }
            }
        }
    }
}

@Composable
private fun ICanHealthOnboardingStep(
    ui: WizardUiMetrics,
    onNavigateToReadiness: () -> Unit,
    onInlineScanResult: (String) -> Unit,
    onLaunchFullscreenScan: () -> Unit,
    onShowManualEntry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = ui.horizontalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Spacer(Modifier.height(ui.spacerMedium))

        tk.glucodata.ui.CgmReadinessSetupBanner(onOpenReadiness = onNavigateToReadiness)
        Spacer(Modifier.height(ui.spacerMedium))

        InlineQrScannerCard(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (ui.compact) 320.dp else 380.dp),
            onScanResult = { raw ->
                onInlineScanResult(raw)
                true
            },
            onManualFallback = onLaunchFullscreenScan,
            manualFallbackLabel = stringResource(R.string.scan_qr_button)
        )

        Spacer(Modifier.height(ui.spacerMedium))

        OutlinedButton(
            onClick = onShowManualEntry,
            modifier = Modifier
                .fillMaxWidth()
                .height(ui.buttonHeight)
        ) {
            Text(stringResource(R.string.enter_code_manually))
        }
    }
}

@Composable
private fun ICanHealthManualEntryDialog(
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    val normalized = remember(value) {
        ICanHealthConstants.normalizeOnboardingDeviceSn(value)
    }
    val isValid = normalized.length in 8..13

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.enter_code_manually)) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { input ->
                        value = input.uppercase().filter { it.isLetterOrDigit() }
                    },
                    label = { Text(stringResource(R.string.serial_number_label)) },
                    placeholder = { Text(ICAN_HEALTH_ONBOARDING_EXAMPLE) },
                    supportingText = {
                        Text(stringResource(R.string.serial_number_supporting, ICAN_HEALTH_ONBOARDING_EXAMPLE))
                    },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    singleLine = true,
                    isError = value.isNotBlank() && !isValid,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(normalized) },
                enabled = isValid
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
