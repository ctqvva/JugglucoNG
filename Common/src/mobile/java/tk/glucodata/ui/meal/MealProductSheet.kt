@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package tk.glucodata.ui.meal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import tk.glucodata.data.meal.AmountUnit
import tk.glucodata.data.meal.MealContributionLog
import tk.glucodata.data.meal.MissingReference
import tk.glucodata.data.meal.NutritionBasis
import tk.glucodata.data.meal.NutritionFacts
import tk.glucodata.data.meal.NutritionPlausibility
import tk.glucodata.data.meal.NutritionReference
import tk.glucodata.data.meal.NutritionSource
import tk.glucodata.data.meal.ProductBarcode
import tk.glucodata.data.meal.QuantityResolution
import tk.glucodata.data.meal.QuantityResolver
import tk.glucodata.data.meal.ScannedProduct

/** The editable fields behind a product, whether it came from a scan or is typed in. */
private data class ProductForm(
    val name: String,
    val brand: String,
    val basis: NutritionBasis,
    val carbs: String,
    val protein: String,
    val fat: String,
    val fiber: String,
    val kcal: String,
    val netQuantity: String,
    val netUnit: AmountUnit,
    val servingQuantity: String,
    val servingUnit: AmountUnit,
    val density: String,
    val pieceGrams: String,
    val saturatedFat: String = "",
    val salt: String = "",
    val category: String = "",
    /** Typed barcode for a product entered by hand; only a valid EAN/UPC is used. */
    val barcode: String = ""
) {
    companion object {
        fun from(product: ScannedProduct?): ProductForm {
            val facts = product?.facts
            val ref = product?.reference
            return ProductForm(
                name = product?.displayName.orEmpty(),
                brand = product?.brand.orEmpty(),
                basis = ref?.basis ?: NutritionBasis.PER_100G,
                carbs = MealFormat.editor(facts?.carbsGrams),
                protein = MealFormat.editor(facts?.proteinGrams),
                fat = MealFormat.editor(facts?.fatGrams),
                fiber = MealFormat.editor(facts?.fiberGrams),
                kcal = MealFormat.editor(facts?.kcal),
                netQuantity = MealFormat.editor(ref?.netQuantity),
                netUnit = ref?.netUnit ?: if (ref?.basis == NutritionBasis.PER_100ML) AmountUnit.MILLILITER else AmountUnit.GRAM,
                servingQuantity = MealFormat.editor(ref?.servingQuantity),
                servingUnit = ref?.servingUnit ?: if (ref?.basis == NutritionBasis.PER_100ML) AmountUnit.MILLILITER else AmountUnit.GRAM,
                density = MealFormat.editor(ref?.densityGramsPerMl),
                pieceGrams = MealFormat.editor(ref?.pieceGrams),
                saturatedFat = MealFormat.editor(facts?.saturatedFatGrams),
                salt = MealFormat.editor(facts?.saltGrams),
                category = product?.category.orEmpty(),
                barcode = product?.barcode.orEmpty()
            )
        }
    }

    /** Null while the form cannot describe a product (no name or no carbs). */
    fun toProduct(original: ScannedProduct?, barcode: String?): ScannedProduct? {
        val carbsValue = MealFormat.parseEditor(carbs) ?: return null
        val displayName = name.trim().ifEmpty { return null }
        val originalRef = original?.reference
        return ScannedProduct(
            barcode = barcode ?: original?.barcode ?: ProductBarcode.normalize(this.barcode),
            displayName = displayName,
            brand = brand.trim().takeIf { it.isNotEmpty() },
            source = original?.source ?: NutritionSource.MANUAL,
            facts = NutritionFacts(
                carbsGrams = carbsValue,
                proteinGrams = MealFormat.parseEditor(protein),
                fatGrams = MealFormat.parseEditor(fat),
                fiberGrams = MealFormat.parseEditor(fiber),
                sugarsGrams = original?.facts?.sugarsGrams?.takeIf { basis == originalRef?.basis },
                polyolsGrams = original?.facts?.polyolsGrams?.takeIf { basis == originalRef?.basis },
                kcal = MealFormat.parseEditor(kcal),
                saturatedFatGrams = MealFormat.parseEditor(saturatedFat),
                saltGrams = MealFormat.parseEditor(salt)
            ),
            contributedAt = original?.contributedAt,
            category = category.trim().takeIf { it.isNotEmpty() },
            reference = NutritionReference(
                basis = basis,
                netQuantity = MealFormat.parseEditor(netQuantity)?.takeIf { it > 0f },
                netUnit = MealFormat.parseEditor(netQuantity)?.takeIf { it > 0f }?.let { netUnit },
                servingText = originalRef?.servingText,
                servingQuantity = MealFormat.parseEditor(servingQuantity)?.takeIf { it > 0f },
                servingUnit = MealFormat.parseEditor(servingQuantity)?.takeIf { it > 0f }?.let { servingUnit },
                servingPieces = originalRef?.servingPieces,
                servingPieceLabel = originalRef?.servingPieceLabel,
                servingsPerBatch = originalRef?.servingsPerBatch,
                densityGramsPerMl = MealFormat.parseEditor(density)?.takeIf { it > 0f },
                pieceGrams = MealFormat.parseEditor(pieceGrams)?.takeIf { it > 0f }
            )
        )
    }
}

/**
 * The confirmation sheet: product head, one free-text amount with shortcuts, the live resolution
 * ("1 Tasse = 240 ml ÷ 100 ml = × 2,4"), the resulting macros each with their derivation, and the
 * one question the engine still needs answered. Values are always editable; nothing is logged
 * from here — the item goes into the meal's composition.
 */
@Composable
internal fun MealProductSheet(
    request: ProductSheetRequest,
    onDismiss: () -> Unit,
    onSubmit: (ScannedProduct, String, QuantityResolution.Resolved?) -> Unit,
    onRemove: (() -> Unit)?,
    onPhotograph: (() -> Unit)? = null,
    /** Present when the Open Food Facts contribution is switched on; receives the product as edited. */
    onContribute: ((ScannedProduct) -> Unit)? = null,
    /** Opens the barcode scanner for the manual form; the result arrives through [scannedBarcode]. */
    onScanBarcode: (() -> Unit)? = null,
    scannedBarcode: String? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = androidx.compose.ui.platform.LocalContext.current
    val original = request.product
    var form by remember(request) { mutableStateOf(ProductForm.from(original)) }
    var editing by remember(request) { mutableStateOf(original == null || request.startEditing) }
    androidx.compose.runtime.LaunchedEffect(scannedBarcode) {
        scannedBarcode?.let { form = form.copy(barcode = it) }
    }
    var amountText by remember(request) { mutableStateOf(request.existingItem?.quantityText ?: "") }
    val product = remember(form) { form.toProduct(original, request.barcode) }
    val reference = product?.reference
    val servingWord = stringResource(R.string.meal_serving_word)
    val pieceWord = stringResource(R.string.meal_piece_word)
    val packageWord = stringResource(R.string.meal_package_word)
    val carbsWord = stringResource(R.string.meal_carbs)
    val proteinWord = stringResource(R.string.meal_protein)
    val fatWord = stringResource(R.string.meal_fat)
    val chips = remember(reference) { reference?.let { quantityChipsFor(it, servingWord, pieceWord, packageWord) }.orEmpty() }
    val resolution = remember(amountText, reference) {
        if (reference == null || amountText.isBlank()) null else QuantityResolver.resolve(amountText, reference)
    }
    val resolved = resolution as? QuantityResolution.Resolved
    val plausibility = remember(product) { product?.let { NutritionPlausibility.check(it.facts, it.reference.basis) }.orEmpty() }

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
            // Head
            if (!editing && product != null) {
                Text(product.displayName, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = listOfNotNull(product.brand, sourceLabel(product.source), basisLabel(product.reference.basis), request.barcode).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${carbsWord} ${MealFormat.grams(product.facts.carbsGrams)} g · ${proteinWord} ${MealFormat.grams(product.facts.proteinGrams)} g · ${fatWord} ${MealFormat.grams(product.facts.fatGrams)} g" +
                        (product.facts.kcal?.let { " · ${MealFormat.grams(it, digits = 0)} kcal" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = { editing = true }) { Text(stringResource(R.string.meal_edit_values)) }
            } else {
                if (original?.source == NutritionSource.OCR_LABEL) {
                    Text(
                        text = stringResource(R.string.meal_ocr_confirm_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                ProductFormFields(form = form, onChange = { form = it }, showBarcodeField = request.barcode == null && original?.barcode == null, onScanBarcode = onScanBarcode)
                if (original == null && onPhotograph != null) {
                    OutlinedButton(onClick = onPhotograph, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.meal_ocr_button))
                    }
                }
                if (original != null) {
                    TextButton(onClick = { editing = false }) { Text(stringResource(R.string.meal_done_editing)) }
                }
            }

            plausibility.forEach { flag ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(plausibilityLabel(flag), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }

            // Amount
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text(stringResource(R.string.meal_quantity_label)) },
                placeholder = { Text(stringResource(R.string.meal_quantity_hint)) },
                singleLine = true,
                enabled = product != null,
                modifier = Modifier.fillMaxWidth()
            )
            if (chips.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    chips.forEach { chip ->
                        FilterChip(selected = amountText == chip, onClick = { amountText = chip }, label = { Text(chip) })
                    }
                }
            }
            resolution?.let {
                ResolutionLine(resolution = it, servingWord = servingWord, pieceWord = pieceWord, onPickCandidate = { text -> amountText = text })
            }

            // The one question
            (resolution as? QuantityResolution.Missing)?.let { missing ->
                MissingReferenceField(
                    need = missing.need,
                    form = form,
                    onChange = { form = it }
                )
            }

            // Result with derivation
            if (product != null && resolved != null) {
                val basisWord = if (product.reference.basis == NutritionBasis.PER_SERVING) servingWord else stringResource(R.string.meal_basis_per_batch)
                MacroResultRow(carbsWord, product.facts.carbsGrams, product.reference.basis, resolved.factor, basisWord)
                product.facts.proteinGrams?.let { MacroResultRow(proteinWord, it, product.reference.basis, resolved.factor, basisWord) }
                product.facts.fatGrams?.let { MacroResultRow(fatWord, it, product.reference.basis, resolved.factor, basisWord) }
                product.facts.fiberGrams?.let { MacroResultRow(stringResource(R.string.meal_fiber), it, product.reference.basis, resolved.factor, basisWord) }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    enabled = product != null && amountText.isNotBlank() && (resolved != null || resolution is QuantityResolution.Missing),
                    onClick = { product?.let { onSubmit(it, amountText, resolved) } },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(if (request.existingItem == null) R.string.meal_add_to_meal else R.string.meal_update_item))
                }
                onRemove?.let { remove ->
                    TextButton(onClick = remove) { Text(stringResource(R.string.meal_remove_item)) }
                }
            }
            if (onContribute != null && product != null && product.canContribute) {
                val sentAt = original?.contributedAt
                val lastAttempt = remember(product.barcode, sentAt) { product.barcode?.let { MealContributionLog.lastFor(context, it) } }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    lastAttempt?.let { attempt ->
                        Text(
                            text = stringResource(
                                R.string.meal_contribute_last,
                                java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(attempt.at)),
                                (if (attempt.ok) "✓ " else "✗ ") + attempt.message
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (attempt.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                        )
                    }
                    sentAt?.let {
                        Text(
                            text = stringResource(
                                R.string.meal_contribute_sent_on,
                                java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(it))
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = { onContribute(product) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (sentAt == null) R.string.meal_contribute_send else R.string.meal_contribute_send_again))
                    }
                }
            }
            if (resolution is QuantityResolution.Missing) {
                Text(
                    text = stringResource(R.string.meal_add_unresolved_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun ResolutionLine(
    resolution: QuantityResolution,
    servingWord: String,
    pieceWord: String,
    onPickCandidate: (String) -> Unit
) {
    when (resolution) {
        is QuantityResolution.Resolved -> Text(
            text = resolution.steps.joinToString(" → "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
        is QuantityResolution.Missing -> Text(
            text = resolution.steps.joinToString(" → ").let { if (it.isBlank()) "" else "$it → " } + missingReferenceLabel(resolution.need),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        is QuantityResolution.Ambiguous -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.meal_quantity_ambiguous), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                resolution.candidates.forEach { candidate ->
                    val text = candidate.describe(servingWord, pieceWord)
                    FilterChip(selected = false, onClick = { onPickCandidate(text) }, label = { Text(text) })
                }
            }
        }
        QuantityResolution.Unparsed -> Text(
            text = stringResource(R.string.meal_quantity_unparsed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun MacroResultRow(label: String, perBasis: Float, basis: NutritionBasis, factor: Float, basisWord: String) {
    Column {
        MacroLine(label = label, value = perBasis * factor)
        Text(
            text = MealFormat.macroDerivation(perBasis, basis, factor, basisWord),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MissingReferenceField(need: MissingReference, form: ProductForm, onChange: (ProductForm) -> Unit) {
    val label = missingReferenceAnswerLabel(need)
    val remembered = stringResource(R.string.meal_answer_remembered)
    when (need) {
        MissingReference.DENSITY -> NumberField(label, form.density, "g/ml") { onChange(form.copy(density = it)) }
        MissingReference.NET_QUANTITY, MissingReference.BATCH_WEIGHT -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            NumberField(label, form.netQuantity, form.netUnit.symbol, Modifier.weight(1f)) { onChange(form.copy(netQuantity = it)) }
            UnitToggle(form.netUnit) { onChange(form.copy(netUnit = it)) }
        }
        MissingReference.SERVING_SIZE, MissingReference.SERVINGS_PER_BATCH -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            NumberField(label, form.servingQuantity, form.servingUnit.symbol, Modifier.weight(1f)) { onChange(form.copy(servingQuantity = it)) }
            UnitToggle(form.servingUnit) { onChange(form.copy(servingUnit = it)) }
        }
        MissingReference.PIECE_WEIGHT -> NumberField(label, form.pieceGrams, "g") { onChange(form.copy(pieceGrams = it)) }
    }
    Text(remembered, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun NumberField(label: String, value: String, suffix: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        suffix = { Text(suffix) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
private fun UnitToggle(unit: AmountUnit, onChange: (AmountUnit) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        AmountUnit.entries.forEach { candidate ->
            FilterChip(selected = unit == candidate, onClick = { onChange(candidate) }, label = { Text(candidate.symbol) })
        }
    }
}

@Composable
private fun ProductFormFields(form: ProductForm, onChange: (ProductForm) -> Unit, showBarcodeField: Boolean = false, onScanBarcode: (() -> Unit)? = null) {
    OutlinedTextField(
        value = form.name,
        onValueChange = { onChange(form.copy(name = it)) },
        label = { Text(stringResource(R.string.meal_product_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = form.brand,
        onValueChange = { onChange(form.copy(brand = it)) },
        label = { Text(stringResource(R.string.meal_product_brand)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Text(stringResource(R.string.meal_product_basis), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(NutritionBasis.PER_100G, NutritionBasis.PER_100ML, NutritionBasis.PER_SERVING).forEach { basis ->
            FilterChip(selected = form.basis == basis, onClick = { onChange(form.copy(basis = basis)) }, label = { Text(basisLabel(basis)) })
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(stringResource(R.string.meal_carbs), form.carbs, "g", Modifier.weight(1f)) { onChange(form.copy(carbs = it)) }
        NumberField(stringResource(R.string.meal_protein), form.protein, "g", Modifier.weight(1f)) { onChange(form.copy(protein = it)) }
        NumberField(stringResource(R.string.meal_fat), form.fat, "g", Modifier.weight(1f)) { onChange(form.copy(fat = it)) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(stringResource(R.string.meal_fiber), form.fiber, "g", Modifier.weight(1f)) { onChange(form.copy(fiber = it)) }
        NumberField(stringResource(R.string.meal_kcal), form.kcal, "", Modifier.weight(1f)) { onChange(form.copy(kcal = it)) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(stringResource(R.string.meal_saturated_fat), form.saturatedFat, "g", Modifier.weight(1f)) { onChange(form.copy(saturatedFat = it)) }
        NumberField(stringResource(R.string.meal_salt), form.salt, "g", Modifier.weight(1f)) { onChange(form.copy(salt = it)) }
    }
    OutlinedTextField(
        value = form.category,
        onValueChange = { onChange(form.copy(category = it)) },
        label = { Text(stringResource(R.string.meal_off_category)) },
        placeholder = { Text(stringResource(R.string.meal_off_category_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    if (showBarcodeField) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = form.barcode,
                onValueChange = { onChange(form.copy(barcode = it.filter(Char::isDigit))) },
                label = { Text(stringResource(R.string.meal_product_barcode)) },
                placeholder = { Text(stringResource(R.string.meal_barcode_hint)) },
                singleLine = true,
                isError = form.barcode.length >= 8 && ProductBarcode.normalize(form.barcode) == null,
                supportingText = { Text(stringResource(R.string.meal_product_barcode_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            onScanBarcode?.let { scan ->
                androidx.compose.material3.FilledTonalIconButton(onClick = scan) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = stringResource(R.string.meal_scan_product))
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        NumberField(stringResource(R.string.meal_product_net_quantity), form.netQuantity, form.netUnit.symbol, Modifier.weight(1f)) { onChange(form.copy(netQuantity = it)) }
        UnitToggle(form.netUnit) { onChange(form.copy(netUnit = it)) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        NumberField(stringResource(R.string.meal_product_serving), form.servingQuantity, form.servingUnit.symbol, Modifier.weight(1f)) { onChange(form.copy(servingQuantity = it)) }
        UnitToggle(form.servingUnit) { onChange(form.copy(servingUnit = it)) }
    }
    Text(
        text = stringResource(R.string.meal_product_values_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
