@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package tk.glucodata.ui.meal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import tk.glucodata.R
import tk.glucodata.data.meal.MealRepository
import tk.glucodata.data.meal.ProductBarcode
import tk.glucodata.data.meal.ProductLookupOutcome
import tk.glucodata.data.meal.ScannedProduct
import tk.glucodata.ui.setup.InlineQrScannerCard
import tk.glucodata.ui.setup.PRODUCT_BARCODE_FORMATS

private sealed class ScanState {
    object Idle : ScanState()
    data class Looking(val barcode: String) : ScanState()
    data class Miss(val barcode: String, val messageRes: Int, val detail: String? = null) : ScanState()
}

/**
 * The existing camera card, told to read retail barcodes. A code that is not a valid EAN/UPC is
 * rejected so the camera keeps running; a valid one is looked up cache-first. A miss offers the
 * manual form with the barcode already filled in, so the next scan of that product is a hit.
 */
@Composable
internal fun MealScanSheet(
    repository: MealRepository,
    allowNetwork: Boolean,
    onDismiss: () -> Unit,
    onProduct: (ScannedProduct?, String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var state by remember { mutableStateOf<ScanState>(ScanState.Idle) }
    var manualCode by remember { mutableStateOf("") }
    var pendingBarcode by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pendingBarcode) {
        val code = pendingBarcode ?: return@LaunchedEffect
        state = ScanState.Looking(code)
        state = when (val outcome = repository.lookupProduct(code, allowNetwork)) {
            is ProductLookupOutcome.Hit -> {
                onProduct(outcome.product, code)
                ScanState.Idle
            }
            ProductLookupOutcome.NotFound -> ScanState.Miss(code, R.string.meal_lookup_not_found)
            ProductLookupOutcome.Offline -> ScanState.Miss(code, R.string.meal_lookup_offline)
            is ProductLookupOutcome.Failed -> ScanState.Miss(code, R.string.meal_lookup_failed, outcome.message)
        }
        pendingBarcode = null
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.meal_scan_product), style = MaterialTheme.typography.titleLarge)
            val current = state
            if (current !is ScanState.Looking) {
                InlineQrScannerCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                    scannerEnabled = pendingBarcode == null,
                    barcodeFormats = PRODUCT_BARCODE_FORMATS,
                    onScanResult = { raw ->
                        val code = ProductBarcode.normalize(raw)
                        if (code != null && pendingBarcode == null) {
                            pendingBarcode = code
                            true
                        } else {
                            false
                        }
                    }
                )
            }
            when (current) {
                ScanState.Idle -> Unit
                is ScanState.Looking -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(modifier = Modifier.width(24.dp).height(24.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.meal_lookup_running, current.barcode))
                    }
                }
                is ScanState.Miss -> {
                    Text(
                        text = stringResource(current.messageRes, current.detail ?: current.barcode),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onProduct(null, current.barcode) }) {
                            Text(stringResource(R.string.meal_add_manual))
                        }
                        TextButton(onClick = { state = ScanState.Idle }) {
                            Text(stringResource(R.string.meal_scan_again))
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = manualCode,
                    onValueChange = { manualCode = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.meal_enter_barcode)) },
                    placeholder = { Text(stringResource(R.string.meal_barcode_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = manualCode.length >= 8 && ProductBarcode.normalize(manualCode) == null,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    enabled = ProductBarcode.normalize(manualCode) != null && pendingBarcode == null,
                    onClick = { pendingBarcode = ProductBarcode.normalize(manualCode) }
                ) {
                    Text(stringResource(R.string.meal_lookup))
                }
            }
        }
    }
}
