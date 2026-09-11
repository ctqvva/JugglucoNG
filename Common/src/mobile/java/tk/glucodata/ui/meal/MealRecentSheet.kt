@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package tk.glucodata.ui.meal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import tk.glucodata.R
import tk.glucodata.data.meal.MealRepository
import tk.glucodata.data.meal.ScannedProduct

/**
 * Everything scanned, read from a label, or typed in before — most recently used first, with a
 * search over name, brand and barcode — so a product that is in the cupboard every week is one
 * tap away instead of another scan. Picking one opens the usual product sheet.
 */
@Composable
internal fun MealRecentSheet(
    repository: MealRepository,
    onDismiss: () -> Unit,
    onPick: (ScannedProduct) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val recent by repository.observeRecentProducts().collectAsStateWithLifecycle(initialValue = emptyList())
    var query by remember { mutableStateOf("") }
    val shown = remember(recent, query) {
        val needle = query.trim().lowercase(Locale.ROOT)
        if (needle.isEmpty()) recent else recent.filter { p ->
            p.displayName.lowercase(Locale.ROOT).contains(needle) ||
                p.brand?.lowercase(Locale.ROOT)?.contains(needle) == true ||
                p.barcode?.contains(needle) == true
        }
    }
    val carbsWord = stringResource(R.string.meal_carbs)
    val proteinWord = stringResource(R.string.meal_protein)
    val fatWord = stringResource(R.string.meal_fat)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(stringResource(R.string.meal_recent_title), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.meal_recent_search)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (shown.isEmpty()) {
                Text(
                    text = stringResource(R.string.meal_recent_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LazyColumn(
                modifier = Modifier.heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(shown, key = { it.barcode ?: "manual:${it.displayName}:${it.brand}" }) { product ->
                    Surface(
                        onClick = { onPick(product) },
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = listOfNotNull(product.brand, product.displayName).joinToString(" · "),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${basisLabel(product.reference.basis)} · " +
                                    product.facts.summary(carbsWord, proteinWord, fatWord) +
                                    (product.barcode?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
