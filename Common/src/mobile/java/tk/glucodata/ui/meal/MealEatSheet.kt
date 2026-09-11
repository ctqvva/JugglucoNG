@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package tk.glucodata.ui.meal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tk.glucodata.R
import tk.glucodata.data.journal.JournalEntryInput
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.journalFoodTailDurationMinutes
import tk.glucodata.data.meal.Meal
import tk.glucodata.data.meal.MealTotals
import tk.glucodata.data.meal.QuantityResolution
import tk.glucodata.data.meal.QuantityResolver

/**
 * "How much of this meal?" — portions, a share, or a weighed plate, through the same engine as the
 * products. The result is handed to the regular journal sheet as a pre-filled carbs entry: the
 * timestamp is the wall clock (editable there), and the dose suggestion is made there, now.
 */
@Composable
internal fun MealEatSheet(
    meal: Meal,
    totals: MealTotals,
    showMacros: Boolean,
    onDismiss: () -> Unit,
    onContinue: (JournalEntryInput) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val reference = remember(meal.servings, meal.cookedWeightGrams) { meal.eatReference() }
    val servingWord = stringResource(R.string.meal_serving_word)
    val pieceWord = stringResource(R.string.meal_piece_word)
    val packageWord = stringResource(R.string.meal_package_word)
    val carbsWord = stringResource(R.string.meal_carbs)
    val proteinWord = stringResource(R.string.meal_protein)
    val fatWord = stringResource(R.string.meal_fat)
    val chips = remember(reference) { quantityChipsFor(reference, servingWord, pieceWord, packageWord) }
    var amountText by remember(meal.id) {
        mutableStateOf(if (meal.servings != null) "1 $servingWord" else "100 %")
    }
    val resolution = remember(amountText, reference) { QuantityResolver.resolve(amountText, reference) }
    val resolved = resolution as? QuantityResolution.Resolved
    val eaten = remember(resolved, totals) { resolved?.let { totals.facts.scaled(it.factor) } }
    val noteFormat = stringResource(R.string.meal_eat_note_format)

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
            Text(meal.label, style = MaterialTheme.typography.titleLarge)
            Text(
                text = stringResource(R.string.meal_totals) + ": " + totals.facts.summary(carbsWord, proteinWord, fatWord),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text(stringResource(R.string.meal_eat_amount_label)) },
                placeholder = { Text(stringResource(R.string.meal_eat_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                chips.forEach { chip ->
                    FilterChip(selected = amountText == chip, onClick = { amountText = chip }, label = { Text(chip) })
                }
            }
            ResolutionLine(resolution = resolution, servingWord = servingWord, pieceWord = pieceWord, onPickCandidate = { amountText = it })
            eaten?.let { facts ->
                MacroLine(label = carbsWord, value = facts.carbsGrams, prominent = true)
                if (showMacros) {
                    MacroLine(label = proteinWord, value = facts.proteinGrams)
                    MacroLine(label = fatWord, value = facts.fatGrams)
                }
                facts.kcal?.let { MacroLine(label = stringResource(R.string.meal_kcal), value = it, unit = "") }
            }
            Text(
                text = stringResource(R.string.meal_eat_next_step),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                enabled = eaten != null,
                onClick = {
                    val facts = eaten ?: return@Button
                    val protein = facts.proteinGrams?.takeIf { showMacros }
                    val fat = facts.fatGrams?.takeIf { showMacros }
                    onContinue(
                        JournalEntryInput(
                            // Wall clock, never a chart position: the meal was scanned earlier.
                            timestamp = System.currentTimeMillis(),
                            type = JournalEntryType.CARBS,
                            title = meal.label,
                            note = String.format(noteFormat, amountText.trim(), meal.label),
                            amount = facts.carbsGrams,
                            durationMinutes = journalFoodTailDurationMinutes(protein, fat).toInt().coerceIn(15, 480),
                            proteinGrams = protein,
                            fatGrams = fat,
                            mealId = meal.id
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.meal_eat_continue))
            }
        }
    }
}
