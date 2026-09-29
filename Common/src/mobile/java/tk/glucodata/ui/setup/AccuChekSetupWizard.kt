package tk.glucodata.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tk.glucodata.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccuChekSetupWizard(
    onDismiss: () -> Unit,
    onNavigateToReadiness: () -> Unit = {},
    onScanResult: (String) -> Unit
) {
    val ui = rememberWizardUiMetrics()
    var handledScan by remember { mutableStateOf(false) }
    val launchFullscreenScan = rememberUnifiedQrScanLauncher(
        requestCode = tk.glucodata.MainActivity.REQUEST_BARCODE,
        title = stringResource(R.string.accuchek_setup_title),
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
                title = { Text(stringResource(R.string.accuchek_setup_title)) },
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
                manualFallbackLabel = stringResource(R.string.scan_accuchek)
            )

            Spacer(modifier = Modifier.height(ui.spacerSmall))

            Text(
                text = stringResource(R.string.accuchek_scan_instruction),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
