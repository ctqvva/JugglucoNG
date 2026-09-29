package tk.glucodata.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tk.glucodata.R

/**
 * Placeholder wizard for Dexcom sensor setup.
 * Currently just shows scan instructions and launches existing flow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DexcomSetupWizard(
    onDismiss: () -> Unit,
    onNavigateToReadiness: () -> Unit = {},
    onScanResult: (String) -> Unit
) {
    val ui = rememberWizardUiMetrics()
    var handledScan by remember { mutableStateOf(false) }
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
    BackHandler(onBack = onDismiss)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dexcom_setup_title)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(ui.horizontalPadding)
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

            Text(
                text = stringResource(R.string.dexcom_scan_instruction),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
