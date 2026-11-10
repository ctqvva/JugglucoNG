package tk.glucodata.ui.journal

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import tk.glucodata.R
import tk.glucodata.data.food.FoodAmount
import tk.glucodata.data.food.FoodItem
import tk.glucodata.data.food.FoodMath
import tk.glucodata.data.food.FoodPortion
import tk.glucodata.data.food.FoodPortionKind
import tk.glucodata.data.food.FoodSource
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.IconButtonTooltip
import tk.glucodata.ui.components.cardShape
import tk.glucodata.ui.util.ConnectedButtonGroup
import tk.glucodata.ui.util.ExpressiveMotion

/** The portions offered beside the base unit; more would not fit one row of buttons. */
private const val MAX_PORTION_UNITS = 3

/** The meal stays visible above results while picking; tapping a chip removes that ingredient. */
@Composable
internal fun FoodMealSummary(items: List<JournalDraftFoodItem>, onRemove: (Int) -> Unit) {
    Column(Modifier.testTag("food_meal_summary")) {
        SectionLabel(stringResource(R.string.mealname), topPadding = 4.dp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items.forEachIndexed { index, item ->
                key(index, item.food.key) {
                    InputChip(selected = true, onClick = { onRemove(index) },
                        label = { Text(item.food.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 160.dp)) },
                        trailingIcon = { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.remove),
                            modifier = Modifier.size(18.dp)) })
                }
            }
        }
    }
}

/**
 * The meal so far: one row per food. The row being worked on is open, with its amount, the
 * units the food can be counted in and a way to take it out; the others show what they add.
 */
@Composable
internal fun FoodMealList(
    items: List<JournalDraftFoodItem>,
    openItem: Int,
    macrosEnabled: Boolean,
    onOpen: (Int) -> Unit,
    onChange: (Int, JournalDraftFoodItem) -> Unit,
    onRemove: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEachIndexed { index, item ->
            key(index, item.food.key) {
                FoodMealRow(
                    item = item,
                    open = index == openItem || items.size == 1,
                    position = foodCardPosition(index, items.size),
                    macrosEnabled = macrosEnabled,
                    onOpen = { onOpen(index) },
                    onChange = { onChange(index, it) },
                    onRemove = { onRemove(index) }
                )
            }
        }
    }
}

@Composable
private fun FoodMealRow(
    item: JournalDraftFoodItem,
    open: Boolean,
    position: CardPosition,
    macrosEnabled: Boolean,
    onOpen: () -> Unit,
    onChange: (JournalDraftFoodItem) -> Unit,
    onRemove: () -> Unit
) {
    val food = item.food
    val shape = cardShape(position, FoodGroupRadius)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = shape
    ) {
        Column(modifier = Modifier.animateContentSize(ExpressiveMotion.defaultSpatial())) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .clickable(enabled = !open, role = Role.Button, onClick = onOpen)
                    .heightIn(min = 64.dp)
                    .padding(start = 16.dp, end = if (open) 4.dp else 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = food.name.ifBlank { stringResource(R.string.journal_type_food) },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = if (open) 2 else 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = foodMealSummary(item, macrosEnabled),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                if (open) {
                    IconButtonTooltip(stringResource(R.string.remove)) {
                        IconButton(onClick = onRemove, shapes = IconButtonDefaults.shapes()) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.remove),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (open) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (food.source == FoodSource.CUSTOM) {
                        FoodLabelFields(
                            food = food,
                            macrosEnabled = macrosEnabled,
                            onFoodChange = { onChange(item.copy(food = it)) }
                        )
                    }
                    FoodAmountEditor(item = item, onChange = onChange)
                }
            }
        }
    }
}

/** The amount of one food: a stepper in the chosen unit, and the units to choose from. */
@Composable
private fun FoodAmountEditor(
    item: JournalDraftFoodItem,
    onChange: (JournalDraftFoodItem) -> Unit
) {
    val food = item.food
    val portion = food.portions.getOrNull(item.amount.portionIndex)
    JournalStepperField(
        value = item.amountText,
        onValueChange = { text ->
            val typed = text.filter { it.isDigit() || it == ',' || it == '.' || it == '\u066b' }.take(48)
            onChange(
                item.copy(
                    amountText = typed,
                    amount = item.amount.copy(quantity = parseFoodQuantity(typed) ?: 0f)
                )
            )
        },
        onStep = { direction ->
            val stepped = FoodMath.stepped(food, item.amount, direction)
            onChange(item.copy(amount = stepped, amountText = formatFoodQuantity(stepped.quantity)))
        },
        label = when {
            portion != null -> foodPortionLabel(portion, food.isLiquid)
            food.countedByWeight -> stringResource(if (food.isLiquid) R.string.food_volume else R.string.food_weight)
            else -> stringResource(R.string.food_servings)
        },
        suffix = when {
            portion != null || !food.countedByWeight -> "×"
            food.isLiquid -> stringResource(R.string.food_unit_ml)
            else -> stringResource(R.string.unit_carbs_short)
        }
    )
    if (food.countedByWeight && food.portions.isNotEmpty()) {
        val units = listOf(FoodAmount.BASE_UNIT) + food.portions.indices.take(MAX_PORTION_UNITS)
        val labels = units.associateWith { index ->
            food.portions.getOrNull(index)?.let { foodPortionName(it) }
                ?: stringResource(if (food.isLiquid) R.string.food_unit_ml else R.string.unit_carbs_short)
        }
        val container = MaterialTheme.colorScheme.surfaceContainerHighest
        ConnectedButtonGroup(
            options = units,
            selectedOption = item.amount.portionIndex,
            onOptionSelected = { index ->
                // The plate stays as it is; only the unit it is counted in changes.
                val converted = FoodMath.inUnit(food, item.amount, index)
                onChange(item.copy(amount = converted, amountText = formatFoodQuantity(converted.quantity)))
            },
            label = { },
            labelText = { labels[it].orEmpty() },
            modifier = Modifier.fillMaxWidth(),
            selectedContainerColor = journalTypeSelectedContainerColor(JournalEntryType.CARBS, container),
            selectedContentColor = MaterialTheme.colorScheme.onSurface,
            unselectedContainerColor = container,
            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** A food typed in from its label: a name and what 100 g holds. */
@Composable
private fun FoodLabelFields(
    food: FoodItem,
    macrosEnabled: Boolean,
    onFoodChange: (FoodItem) -> Unit
) {
    // What is typed, kept as typed: "7," must not snap back to "7" before the next digit.
    var carbsText by remember(food.key) { mutableStateOf(food.carbs.takeIf { it > 0f }?.let(::formatFoodQuantity).orEmpty()) }
    var proteinText by remember(food.key) { mutableStateOf(food.protein?.let(::formatFoodQuantity).orEmpty()) }
    var fatText by remember(food.key) { mutableStateOf(food.fat?.let(::formatFoodQuantity).orEmpty()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FoodTextField(
            value = food.name,
            onValueChange = { onFoodChange(food.copy(name = it.take(80))) },
            label = stringResource(R.string.name).trim().trimEnd(':').trim(),
            keyboardType = KeyboardType.Text,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = stringResource(R.string.food_per_100g_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FoodTextField(
                value = carbsText,
                onValueChange = {
                    carbsText = it
                    onFoodChange(food.copy(carbs = parseFoodQuantity(it)?.coerceIn(0f, 100f) ?: 0f))
                },
                label = stringResource(R.string.food_carbs),
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.weight(1f)
            )
            if (macrosEnabled) {
                FoodTextField(
                    value = proteinText,
                    onValueChange = {
                        proteinText = it
                        onFoodChange(food.copy(protein = parseFoodQuantity(it)?.coerceIn(0f, 100f)))
                    },
                    label = stringResource(R.string.journal_food_protein),
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.weight(1f)
                )
                FoodTextField(
                    value = fatText,
                    onValueChange = {
                        fatText = it
                        onFoodChange(food.copy(fat = parseFoodQuantity(it)?.coerceIn(0f, 100f)))
                    },
                    label = stringResource(R.string.journal_food_fat),
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** The sheet's filled field: the note field's look, for a short value. */
@Composable
internal fun FoodTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType,
    modifier: Modifier = Modifier
) {
    TextField(
        value = value,
        onValueChange = { text ->
            onValueChange(
                if (keyboardType == KeyboardType.Text) text
                else text.filter { it.isDigit() || it == ',' || it == '.' }.take(6)
            )
        },
        modifier = modifier.heightIn(min = 56.dp),
        label = { Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = RoundedCornerShape(16.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent
        )
    )
}

// --- Wording -------------------------------------------------------------------------------

/** "Ferrero · 100 g · 57.5 g carbs", with protein and fat when the journal counts them. */
@Composable
internal fun foodSummary(food: FoodItem, macrosEnabled: Boolean): String {
    val basis = food.referenceGrams?.let { foodWeightText(it, food.isLiquid) }
        ?: stringResource(R.string.food_one_serving)
    return listOfNotNull(
        food.brand,
        basis,
        stringResource(R.string.food_carbs_value, formatFoodNumber(food.carbs)),
        food.protein?.takeIf { macrosEnabled }?.let { stringResource(R.string.journal_food_protein_short, formatFoodNumber(it)) },
        food.fat?.takeIf { macrosEnabled }?.let { stringResource(R.string.journal_food_fat_short, formatFoodNumber(it)) }
    ).joinFoodFacts()
}

/** "2 × slice · 58 g · 27.1 g carbs" */
@Composable
private fun foodMealSummary(item: JournalDraftFoodItem, macrosEnabled: Boolean): String {
    val food = item.food
    val nutrients = item.nutrients
    val portion = food.portions.getOrNull(item.amount.portionIndex)
    val count = when {
        portion != null -> stringResource(
            R.string.food_count_of_unit,
            formatFoodQuantity(item.amount.quantity),
            foodPortionName(portion)
        )
        !food.countedByWeight -> stringResource(
            R.string.food_count_of_unit,
            formatFoodQuantity(item.amount.quantity),
            stringResource(R.string.food_portion_serving)
        )
        else -> null
    }
    return listOfNotNull(
        count,
        nutrients.grams?.let { foodWeightText(it, food.isLiquid) },
        stringResource(R.string.food_carbs_value, formatFoodNumber(nutrients.carbs)),
        nutrients.protein?.takeIf { macrosEnabled }?.let { stringResource(R.string.journal_food_protein_short, formatFoodNumber(it)) },
        nutrients.fat?.takeIf { macrosEnabled }?.let { stringResource(R.string.journal_food_fat_short, formatFoodNumber(it)) }
    ).joinFoodFacts()
}

/** Facts on one line, each kept whole: a line breaks between two of them, never inside "2.7 g fat". */
private fun List<String>.joinFoodFacts(): String = joinToString(" · ") { it.replace(' ', '\u00A0') }

@Composable
internal fun foodWeightText(grams: Float, liquid: Boolean): String =
    stringResource(if (liquid) R.string.food_amount_ml else R.string.unit_carbs_value, formatFoodNumber(grams))

@Composable
internal fun foodPortionName(portion: FoodPortion): String =
    portion.label ?: stringResource(portion.kind.labelRes())

/** "slice · 29 g" */
@Composable
internal fun foodPortionLabel(portion: FoodPortion, liquid: Boolean): String =
    foodPortionName(portion) + " · " + foodWeightText(portion.grams, liquid)

internal fun FoodPortionKind.labelRes(): Int = when (this) {
    FoodPortionKind.PIECE -> R.string.food_portion_piece
    FoodPortionKind.SLICE -> R.string.food_portion_slice
    FoodPortionKind.CUP -> R.string.food_portion_cup
    FoodPortionKind.GLASS -> R.string.food_portion_glass
    FoodPortionKind.TBSP -> R.string.food_portion_tbsp
    FoodPortionKind.TSP -> R.string.food_portion_tsp
    FoodPortionKind.SERVING -> R.string.food_portion_serving
    FoodPortionKind.PACKAGE -> R.string.food_portion_package
}

// --- Numbers -------------------------------------------------------------------------------

/** A field must round-trip even a tiny fraction of a package without changing the amount. */
internal fun formatFoodQuantity(value: Float): String {
    if (!value.isFinite()) return ""
    val symbols = DecimalFormatSymbols(Locale.getDefault())
    return java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
        .map { char ->
            when {
                char == '.' -> symbols.decimalSeparator
                char in '0'..'9' -> symbols.zeroDigit + (char - '0')
                else -> char
            }
        }.joinToString("")
}

/** A number to read: one decimal at most, none from 100 up. */
internal fun formatFoodNumber(value: Float): String {
    if (!value.isFinite()) return ""
    val pattern = if (abs(value) >= 100f) "0" else "0.#"
    return DecimalFormat(pattern, DecimalFormatSymbols(Locale.getDefault())).format(value)
}

internal fun parseFoodQuantity(text: String): Float? = tk.glucodata.data.food.FoodNumbers.parse(text)
