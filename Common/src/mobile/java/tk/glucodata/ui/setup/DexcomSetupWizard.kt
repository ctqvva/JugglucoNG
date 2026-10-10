package tk.glucodata.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tk.glucodata.DexcomManualPairing
import tk.glucodata.R
import tk.glucodata.ui.components.AppTopBar

/** Dexcom setup through either the applicator data matrix or its printed pairing code. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DexcomSetupWizard(
    onDismiss: () -> Unit,
    onNavigateToReadiness: () -> Unit = {},
    onScanResult: (String) -> Unit
) {
    val ui = rememberWizardUiMetrics()
    var handledScan by remember { mutableStateOf(false) }
    var showManualEntry by remember { mutableStateOf(false) }
    val launchFullscreenScan = rememberUnifiedQrScanLauncher(
        requestCode = tk.glucodata.MainActivity.REQUEST_BARCODE,
        title = stringResource(R.string.dexcom_setup_title),
        onScanResult = { raw ->
            if (!handledScan) {
                handledScan = true
                onScanResult(raw)
            }
        }
    )
    BackHandler {
        if (showManualEntry) {
            showManualEntry = false
        } else {
            onDismiss()
        }
    }

    if (showManualEntry) {
        DexcomManualPairingDialog(
            onDismiss = { showManualEntry = false },
            onConfirm = { pairingCode ->
                val payload = DexcomManualPairing.createScanPayload(pairingCode)
                if (payload != null && !handledScan) {
                    handledScan = true
                    showManualEntry = false
                    onScanResult(payload)
                }
            }
        )
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.dexcom_setup_title),
                onNavigateBack = onDismiss,
                navigationContentDescription = stringResource(R.string.cancel),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(ui.horizontalPadding)
                .verticalScroll(rememberScrollState())
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            tk.glucodata.ui.CgmReadinessSetupBanner(onOpenReadiness = onNavigateToReadiness)
            Spacer(modifier = Modifier.height(ui.spacerMedium))

            InlineQrScannerCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (ui.compact) 320.dp else 380.dp),
                onScanResult = { raw ->
                    if (!handledScan) {
                        handledScan = true
                        onScanResult(raw)
                    }
                    true
                },
                onManualFallback = launchFullscreenScan,
                manualFallbackLabel = stringResource(R.string.scan_dexcom)
            )

            Spacer(modifier = Modifier.height(ui.spacerSmall))

            OutlinedButton(
                onClick = { showManualEntry = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.ContentPadding
            ) {
                Text(stringResource(R.string.enter_code_manually))
            }

            Spacer(modifier = Modifier.height(ui.spacerSmall))

            Text(
                text = stringResource(R.string.dexcom_scan_instruction),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun DexcomManualPairingDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var pairingCode by remember { mutableStateOf("") }
    val isValid = DexcomManualPairing.isValidPairingCode(pairingCode)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.enter_code_manually)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.dexcom_pairing_code_instruction),
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = pairingCode,
                    onValueChange = { raw ->
                        pairingCode = raw.filter { it in '0'..'9' }.take(4)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.dexcom_pairing_code_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(pairingCode) },
                enabled = isValid,
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.ContentPadding
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
