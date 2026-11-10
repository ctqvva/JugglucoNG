@file:OptIn(ExperimentalMaterial3Api::class)

package tk.glucodata.ui.journal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tk.glucodata.R
import tk.glucodata.data.food.FoodLibrary
import tk.glucodata.data.food.FoodPortion
import tk.glucodata.data.food.FoodPortionKind
import tk.glucodata.data.journal.JournalFood
import tk.glucodata.data.journal.JournalFoodInput
import tk.glucodata.ui.components.ColorSwatchButton
import tk.glucodata.ui.components.CompactSheetDragHandle
import tk.glucodata.ui.components.IconButtonTooltip
import tk.glucodata.ui.components.StableModalBottomSheet
import tk.glucodata.ui.util.ConnectedButtonGroup

/** What a food's numbers are written for. */
internal enum class FoodBasis { PER_100, PER_SERVING }

internal data class FoodEditorDraft(
    val id: Long? = null,
    val displayName: String = "",
    val carbsText: String = "",
    val proteinText: String = "",
    val fatText: String = "",
    val absorptionText: String = "90",
    val accentColor: Int = FoodLibrary.DEFAULT_ACCENT_COLOR,
    val sortOrder: Int = Int.MAX_VALUE,
    val isBuiltIn: Boolean = false,
    val isArchived: Boolean = false,
    val basis: FoodBasis = FoodBasis.PER_100,
    /** The weight of one serving, when the person knows it. */
    val servingText: String = "",
    val isLiquid: Boolean = false
)

@Composable
internal fun FoodEditorSheet(
    food: JournalFood?,
    foodMacrosEnabled: Boolean,
    nextSortOrder: Int,
    onDismiss: () -> Unit,
    onSave: (JournalFoodInput) -> Unit,
    onDelete: (() -> Unit)?,
    initialBarcode: String? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember(food?.id) { mutableStateOf(foodEditorDraft(food, nextSortOrder)) }
    var showColorDialog by rememberSaveable(food?.id) { mutableStateOf(false) }
    val input = foodEditorInput(draft, food)?.let { it.copy(barcode = it.barcode ?: initialBarcode) }

    StableModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { CompactSheetDragHandle() }
    ) {
        FoodEditorForm(
            food = food,
            foodMacrosEnabled = foodMacrosEnabled,
            draft = draft,
            onDraftChange = { draft = it },
            onSave = { input?.let(onSave) },
            canSave = input != null,
            onDelete = onDelete,
            onPickColor = { showColorDialog = true },
            modifier = Modifier.imePadding()
        )
    }

    if (showColorDialog) {
        PresetColorDialog(
            initialColor = draft.accentColor,
            onDismiss = { showColorDialog = false },
            onConfirm = {
                draft = draft.copy(accentColor = it)
                showColorDialog = false
            }
        )
    }
}

/** The editor's fields, apart from the sheet they are shown in. */
@Composable
internal fun FoodEditorForm(
    food: JournalFood?,
    foodMacrosEnabled: Boolean,
    draft: FoodEditorDraft,
    onDraftChange: (FoodEditorDraft) -> Unit,
    onSave: () -> Unit,
    canSave: Boolean,
    onDelete: (() -> Unit)?,
    onPickColor: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(if (food == null) R.string.journal_add_food else R.string.journal_edit_food),
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (food != null) {
                    val archived = draft.isArchived
                    FilledTonalButton(
                        onClick = { onDraftChange(draft.copy(isArchived = !draft.isArchived)) },
                        modifier = Modifier.heightIn(min = 40.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = if (archived) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.errorContainer
                            },
                            contentColor = if (archived) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer
                            }
                        ),
                        shapes = ButtonDefaults.shapes()
                    ) {
                        Icon(
                            imageVector = if (archived) Icons.Default.CheckCircle else Icons.Default.Block,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(if (archived) R.string.enable else R.string.disable),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }

        item(key = "name_color") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FoodNameField(
                    value = draft.displayName,
                    onValueChange = { onDraftChange(draft.copy(displayName = it)) },
                    label = stringResource(R.string.name).trim().trimEnd(':').trim(),
                    accentColor = Color(draft.accentColor),
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(min = 0.dp)
                )
                ColorSwatchButton(
                    color = Color(draft.accentColor),
                    onClick = onPickColor,
                )
            }
        }

        item(key = "liquid") {
            val labels = mapOf(false to stringResource(R.string.food_weight), true to stringResource(R.string.food_volume))
            ConnectedButtonGroup(
                options = listOf(false, true),
                selectedOption = draft.isLiquid,
                onOptionSelected = { onDraftChange(draft.copy(isLiquid = it)) },
                label = { },
                labelText = { contextLiquid -> labels[contextLiquid].orEmpty() },
                modifier = Modifier.fillMaxWidth()
            )
        }

        item(key = "basis") {
            val labels = mapOf(
                FoodBasis.PER_100 to foodWeightText(100f, liquid = draft.isLiquid),
                FoodBasis.PER_SERVING to stringResource(R.string.food_one_serving)
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.food_values_for),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ConnectedButtonGroup(
                    options = FoodBasis.entries.toList(),
                    selectedOption = draft.basis,
                    onOptionSelected = { onDraftChange(draft.withBasis(it)) },
                    label = { },
                    labelText = { labels[it].orEmpty() },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        item(key = "macros") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FoodEditorField(
                    value = draft.carbsText,
                    onValueChange = { onDraftChange(draft.copy(carbsText = it)) },
                    onStep = { delta -> onDraftChange(draft.copy(carbsText = stepFoodNumber(draft.carbsText, delta, 5f))) },
                    label = stringResource(R.string.food_carbs),
                    suffix = stringResource(R.string.unit_carbs_short),
                    accentColor = Color(draft.accentColor)
                )
                if (foodMacrosEnabled) {
                    FoodEditorField(
                        value = draft.proteinText,
                        onValueChange = { onDraftChange(draft.copy(proteinText = it)) },
                        onStep = { delta -> onDraftChange(draft.copy(proteinText = stepFoodNumber(draft.proteinText, delta, 5f))) },
                        label = stringResource(R.string.journal_food_protein),
                        suffix = stringResource(R.string.unit_carbs_short),
                        accentColor = Color(draft.accentColor)
                    )
                    FoodEditorField(
                        value = draft.fatText,
                        onValueChange = { onDraftChange(draft.copy(fatText = it)) },
                        onStep = { delta -> onDraftChange(draft.copy(fatText = stepFoodNumber(draft.fatText, delta, 5f))) },
                        label = stringResource(R.string.journal_food_fat),
                        suffix = stringResource(R.string.unit_carbs_short),
                        accentColor = Color(draft.accentColor)
                    )
                }
                FoodEditorField(
                    value = draft.servingText,
                    onValueChange = { onDraftChange(draft.copy(servingText = it)) },
                    onStep = { delta -> onDraftChange(draft.copy(servingText = stepFoodNumber(draft.servingText, delta, 10f))) },
                    label = stringResource(R.string.food_serving_weight),
                    suffix = stringResource(if (draft.isLiquid) R.string.food_unit_ml else R.string.unit_carbs_short),
                    accentColor = Color(draft.accentColor),
                    placeholder = "—"
                )
                FoodEditorField(
                    value = draft.absorptionText,
                    onValueChange = { updated -> onDraftChange(draft.copy(absorptionText = updated.filter(Char::isDigit))) },
                    onStep = { delta ->
                        val current = tk.glucodata.data.food.FoodNumbers.normalize(draft.absorptionText).toIntOrNull() ?: 90
                        onDraftChange(draft.copy(absorptionText = (current + delta * 15).coerceIn(15, 480).toString()))
                    },
                    label = stringResource(R.string.journal_food_absorption),
                    suffix = stringResource(R.string.minutes),
                    keyboardType = KeyboardType.Number,
                    accentColor = Color(draft.accentColor)
                )
            }
        }

        item(key = "actions") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onSave,
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth(),
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.ContentPadding
                ) {
                    Text(text = stringResource(R.string.save))
                }
                onDelete?.let {
                    OutlinedButton(
                        onClick = it,
                        modifier = Modifier.fillMaxWidth(),
                        shapes = ButtonDefaults.shapes(),
                        contentPadding = ButtonDefaults.ContentPadding
                    ) {
                        Text(
                            text = stringResource(R.string.delete),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FoodNameField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                cursorBrush = SolidColor(accentColor),
                textStyle = MaterialTheme.typography.titleLargeEmphasized.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun FoodEditorField(
    value: String,
    onValueChange: (String) -> Unit,
    onStep: (Int) -> Unit,
    label: String,
    suffix: String,
    accentColor: Color,
    keyboardType: KeyboardType = KeyboardType.Decimal,
    placeholder: String = "0",
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButtonTooltip(stringResource(R.string.outbound_api_decrease_value)) {
                FilledTonalIconButton(
                    onClick = { onStep(-1) },
                    modifier = Modifier.size(44.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    shapes = IconButtonDefaults.shapes()
                ) {
                    Icon(
                        imageVector = Icons.Default.Remove,
                        contentDescription = stringResource(R.string.outbound_api_decrease_value),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                        cursorBrush = SolidColor(accentColor),
                        textStyle = MaterialTheme.typography.titleLargeEmphasized.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.weight(1f),
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (value.isBlank()) {
                                    Text(
                                        text = placeholder,
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.48f)
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = suffix,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButtonTooltip(stringResource(R.string.outbound_api_increase_value)) {
                FilledTonalIconButton(
                    onClick = { onStep(1) },
                    modifier = Modifier.size(44.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = accentColor.copy(alpha = 0.18f),
                        contentColor = accentColor
                    ),
                    shapes = IconButtonDefaults.shapes()
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.outbound_api_increase_value),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

internal fun foodEditorDraft(food: JournalFood?, nextSortOrder: Int): FoodEditorDraft {
    if (food == null) return FoodEditorDraft(sortOrder = nextSortOrder)
    val per100 = food.referenceGrams != null
    val factor = food.referenceGrams?.let { 100f / it } ?: 1f
    val serving = food.portions.firstOrNull { it.kind == FoodPortionKind.SERVING }?.grams
        ?: food.referenceGrams.takeIf { !per100 }
    return FoodEditorDraft(
        id = food.id,
        displayName = food.displayName,
        carbsText = formatFoodQuantity(food.carbsGrams * factor),
        proteinText = food.proteinGrams?.let { formatFoodQuantity(it * factor) }.orEmpty(),
        fatText = food.fatGrams?.let { formatFoodQuantity(it * factor) }.orEmpty(),
        absorptionText = food.absorptionMinutes.toString(),
        accentColor = food.accentColor,
        sortOrder = food.sortOrder,
        isBuiltIn = food.isBuiltIn,
        isArchived = food.isArchived,
        basis = if (per100) FoodBasis.PER_100 else FoodBasis.PER_SERVING,
        servingText = serving?.let(::formatFoodQuantity).orEmpty(),
        isLiquid = food.isLiquid
    )
}

/**
 * The draft with its numbers rewritten for [basis]. They are converted when the serving's
 * weight is known; without it there is nothing to convert by, and they stay as typed.
 */
internal fun FoodEditorDraft.withBasis(basis: FoodBasis): FoodEditorDraft {
    if (basis == this.basis) return this
    val serving = parseFoodQuantity(servingText)?.takeIf { it > 0f } ?: return copy(basis = basis)
    val factor = if (basis == FoodBasis.PER_SERVING) serving / 100f else 100f / serving
    fun convert(text: String): String = parseFoodQuantity(text)?.let { formatFoodQuantity(it * factor) } ?: text
    return copy(
        basis = basis,
        carbsText = convert(carbsText),
        proteinText = convert(proteinText),
        fatText = convert(fatText)
    )
}

/** What saving the draft writes, or null while it lacks a name, carbs or a time. */
internal fun foodEditorInput(draft: FoodEditorDraft, food: JournalFood?): JournalFoodInput? {
    val name = draft.displayName.trim().takeIf { it.isNotBlank() } ?: return null
    val carbs = parseFoodQuantity(draft.carbsText) ?: return null
    val absorption = tk.glucodata.data.food.FoodNumbers.normalize(draft.absorptionText).toIntOrNull()?.coerceIn(15, 480) ?: return null
    val serving = parseFoodQuantity(draft.servingText)?.takeIf { it > 0f }
    val reference = if (draft.basis == FoodBasis.PER_100) 100f else serving
    val otherPortions = food?.portions.orEmpty().filter { it.kind != FoodPortionKind.SERVING }
    val servingLabel = food?.portions?.firstOrNull { it.kind == FoodPortionKind.SERVING }?.label
    return JournalFoodInput(
        id = draft.id,
        displayName = name,
        carbsGrams = carbs,
        proteinGrams = parseFoodQuantity(draft.proteinText),
        fatGrams = parseFoodQuantity(draft.fatText),
        absorptionMinutes = absorption,
        accentColor = draft.accentColor,
        isBuiltIn = draft.isBuiltIn,
        isArchived = draft.isArchived,
        sortOrder = draft.sortOrder,
        referenceGrams = reference,
        portions = when {
            // Without a weight the food is counted in servings and nothing else.
            reference == null -> emptyList()
            serving != null -> listOf(FoodPortion(FoodPortionKind.SERVING, serving, servingLabel)) + otherPortions
            else -> otherPortions
        },
        // Per 100 g these still hold; for a serving of another size they would be wrong.
        fiberGrams = food?.fiberGrams?.let { value -> if (reference != null && food.referenceGrams != null) value * reference / food.referenceGrams else value.takeIf { food.referenceGrams == reference } },
        kcal = food?.kcal?.let { value -> if (reference != null && food.referenceGrams != null) value * reference / food.referenceGrams else value.takeIf { food.referenceGrams == reference } },
        brand = food?.brand,
        barcode = food?.barcode,
        sourceKey = food?.sourceKey,
        isLiquid = draft.isLiquid,
        hasOwnAbsorption = food == null || food.hasOwnAbsorption || draft.absorptionText != food.absorptionMinutes.toString(),
        // Saving a food that was only remembered keeps it.
        kept = true
    )
}

private fun stepFoodNumber(value: String, direction: Int, step: Float): String =
    formatFoodQuantity(((parseFoodQuantity(value) ?: 0f) + direction * step).coerceAtLeast(0f))
