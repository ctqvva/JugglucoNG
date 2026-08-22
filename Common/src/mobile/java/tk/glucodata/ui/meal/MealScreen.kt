@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package tk.glucodata.ui.meal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.LunchDining
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import tk.glucodata.R
import tk.glucodata.data.journal.JournalEntry
import tk.glucodata.data.journal.JournalEntryInput
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalRepository
import androidx.compose.ui.platform.LocalContext
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tk.glucodata.data.meal.ContributionPhoto
import tk.glucodata.data.meal.ContributionResult
import tk.glucodata.data.meal.LabelPhotoKind
import tk.glucodata.data.meal.LabelPhotoStore
import tk.glucodata.data.meal.Meal
import tk.glucodata.data.meal.MealContributionSettings
import tk.glucodata.data.meal.NutritionSource
import tk.glucodata.data.meal.OpenFoodFactsContributor
import tk.glucodata.data.meal.MealItem
import tk.glucodata.data.meal.MealMath
import tk.glucodata.data.meal.MealRepository
import tk.glucodata.data.meal.NutritionReference
import tk.glucodata.data.meal.QuantityResolution
import tk.glucodata.data.meal.ScannedProduct
import tk.glucodata.ui.JournalEditorRequest
import tk.glucodata.ui.JournalEditorSheetHost
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.journal.journalTypeColor
import tk.glucodata.ui.viewmodel.DashboardViewModel

/**
 * One meal: what is on the table (composition), what the batch amounts to, and what has been
 * logged for it. "Eaten" hands a pre-filled carbs entry to the regular journal sheet, so the dose
 * calculator there runs on the glucose and IOB of that moment, never on a number stored here.
 */
@Composable
fun MealScreen(
    mealId: Long,
    navController: NavController,
    dashboardViewModel: DashboardViewModel,
    repository: MealRepository = remember { MealRepository() }
) {
    val scope = rememberCoroutineScope()
    val meal by repository.observeMeal(mealId).collectAsStateWithLifecycle(initialValue = null)
    val items by repository.observeItems(mealId).collectAsStateWithLifecycle(initialValue = emptyList())
    val journalRepository = remember { JournalRepository() }
    val linkedEntries by journalRepository.observeEntriesForMeal(mealId).collectAsStateWithLifecycle(initialValue = emptyList())
    val foodMacrosEnabled by dashboardViewModel.journalFoodMacrosEnabled.collectAsStateWithLifecycle()
    val onlineLookup by dashboardViewModel.journalMealOnlineLookup.collectAsStateWithLifecycle()
    val unit by dashboardViewModel.unit.collectAsStateWithLifecycle()
    val insulinPresets by dashboardViewModel.journalInsulinPresets.collectAsStateWithLifecycle()
    val presetsById = remember(insulinPresets) { insulinPresets.associateBy { it.id } }
    val totals = remember(items) { MealMath.totals(items) }

    val context = LocalContext.current
    var showScanner by remember { mutableStateOf(false) }
    var showRecent by remember { mutableStateOf(false) }
    var productSheet by remember { mutableStateOf<ProductSheetRequest?>(null) }
    var ocrRequest by remember { mutableStateOf<OcrRequest?>(null) }
    var attachBarcodeFor by remember { mutableStateOf<Pair<ScannedProduct, List<ContributionPhoto>>?>(null) }
    var sendRequest by remember { mutableStateOf<ScannedProduct?>(null) }
    var sendAddPhotos by remember { mutableStateOf(false) }
    var showAttachScanner by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showEatSheet by remember { mutableStateOf(false) }
    var journalRequest by remember { mutableStateOf<JournalEditorRequest?>(null) }

    val current = meal

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = current?.label ?: stringResource(R.string.meal_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showRename = true }) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.meal_rename))
                    }
                    current?.let { m ->
                        IconButton(onClick = { scope.launch { repository.setArchived(m.id, !m.isArchived) } }) {
                            Icon(
                                imageVector = if (m.isArchived) Icons.Default.Unarchive else Icons.Default.Archive,
                                contentDescription = stringResource(if (m.isArchived) R.string.meal_unarchive else R.string.meal_archive)
                            )
                        }
                    }
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.meal_delete))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "actions") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    FilledTonalButton(onClick = { showScanner = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.meal_scan_product))
                    }
                    FilledTonalButton(
                        onClick = { productSheet = ProductSheetRequest(product = null, barcode = null, existingItem = null) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.meal_add_manual))
                    }
                }
            }
            item(key = "recent") {
                FilledTonalButton(onClick = { showRecent = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.History, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.meal_recent_title))
                }
            }

            item(key = "composition_label") { SectionLabel(stringResource(R.string.meal_composition), topPadding = 8.dp) }
            if (items.isEmpty()) {
                item(key = "composition_empty") {
                    Text(
                        text = stringResource(R.string.meal_composition_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            itemsIndexed(items, key = { _, item -> "item_${item.id}" }) { _, item ->
                MealItemRow(
                    item = item,
                    onClick = { productSheet = ProductSheetRequest(product = item.toScannedProduct(), barcode = item.barcode, existingItem = item) }
                )
            }

            item(key = "totals") {
                current?.let { m ->
                    MealTotalsCard(
                        meal = m,
                        totals = totals,
                        showMacros = foodMacrosEnabled,
                        onServingsChanged = { value ->
                            scope.launch { repository.updateMeal(m.id, servings = value, clearServings = value == null) }
                        },
                        onCookedWeightChanged = { value ->
                            scope.launch { repository.updateMeal(m.id, cookedWeightGrams = value, clearCookedWeight = value == null) }
                        }
                    )
                }
            }

            item(key = "eaten") {
                Button(
                    onClick = { showEatSheet = true },
                    enabled = current != null && totals.resolvedItems > 0,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.LunchDining, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.meal_eaten))
                }
            }

            item(key = "entries_label") { SectionLabel(stringResource(R.string.meal_entries_section)) }
            if (linkedEntries.isEmpty()) {
                item(key = "entries_empty") {
                    Text(
                        text = stringResource(R.string.meal_entries_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            itemsIndexed(linkedEntries, key = { _, entry -> "entry_${entry.id}" }) { _, entry ->
                LinkedEntryRow(
                    entry = entry,
                    presetName = entry.insulinPresetId?.let { presetsById[it]?.displayName },
                    onClick = { journalRequest = JournalEditorRequest(entry.type, entry.timestamp, entry) }
                )
            }
        }
    }

    if (showScanner) {
        MealScanSheet(
            repository = repository,
            allowNetwork = onlineLookup,
            onDismiss = { showScanner = false },
            onProduct = { product, barcode ->
                showScanner = false
                productSheet = ProductSheetRequest(product = product, barcode = barcode, existingItem = null)
            },
            onPhotograph = { barcode ->
                showScanner = false
                ocrRequest = OcrRequest(barcode)
            }
        )
    }

    if (showRecent) {
        MealRecentSheet(
            repository = repository,
            onDismiss = { showRecent = false },
            onPick = { product ->
                showRecent = false
                productSheet = ProductSheetRequest(product = product, barcode = product.barcode, existingItem = null)
            }
        )
    }

    ocrRequest?.let { request ->
        MealLabelOcrSheet(
            barcode = request.barcode,
            onDismiss = { ocrRequest = null },
            onProduct = { product, photos ->
                ocrRequest = null
                productSheet = ProductSheetRequest(
                    product = product,
                    barcode = request.barcode,
                    existingItem = null,
                    startEditing = true,
                    photos = photos
                )
            }
        )
    }

    productSheet?.let { request ->
        val contributionUsable = remember(request) { MealContributionSettings.load(context).isUsable }
        MealProductSheet(
            request = request,
            onDismiss = {
                deleteLabelPhotos(request.photos)
                productSheet = null
            },
            onContribute = if (contributionUsable) { edited ->
                val code = edited.barcode
                if (code != null) {
                    scope.launch {
                        // What goes out is what is stored: keep the cache in step with the edit.
                        repository.rememberProduct(code, edited)
                        productSheet = null
                        sendRequest = edited
                    }
                }
            } else null,
            onPhotograph = {
                productSheet = null
                ocrRequest = OcrRequest(request.barcode)
            },
            onSubmit = { product: ScannedProduct, quantityText: String, resolution: QuantityResolution.Resolved? ->
                val fromLabel = request.product?.source == NutritionSource.OCR_LABEL
                scope.launch {
                    val existing = request.existingItem
                    if (existing == null) {
                        repository.addItem(mealId, product, quantityText, resolution)
                    } else {
                        repository.updateItem(existing.id, product, quantityText, resolution)
                    }
                    val code = product.barcode
                    if (code != null) {
                        // A manual or label-read product for a known barcode, or an answered
                        // question, is remembered so the next scan is a hit.
                        if (request.product == null || fromLabel || request.product.reference != product.reference) {
                            repository.rememberProduct(code, product)
                        } else {
                            repository.touchProduct(code)
                        }
                        if (fromLabel) contributeLabelProduct(context, repository, code, product, request.photos)
                    } else if (!fromLabel) {
                        deleteLabelPhotos(request.photos)
                    }
                }
                if (fromLabel && product.barcode == null && request.existingItem == null) {
                    attachBarcodeFor = product to request.photos
                }
                productSheet = null
            },
            onRemove = request.existingItem?.let { existing ->
                {
                    scope.launch { repository.deleteItem(existing.id) }
                    productSheet = null
                }
            }
        )
    }

    sendRequest?.let { product ->
        val code = product.barcode
        if (code == null) {
            sendRequest = null
        } else if (sendAddPhotos) {
            MealLabelOcrSheet(
                barcode = code,
                photosOnly = true,
                onDismiss = { sendAddPhotos = false },
                onProduct = { _, _ -> },
                onPhotos = { photos ->
                    LabelPhotoStore.store(context, code, photos)
                    sendAddPhotos = false
                }
            )
        } else {
            MealSendDialog(
                product = product,
                photos = LabelPhotoStore.photosFor(context, code),
                uploadPhotos = remember(product) { MealContributionSettings.load(context).uploadPhotos },
                onAddPhotos = { sendAddPhotos = true },
                onSend = {
                    sendRequest = null
                    scope.launch {
                        contributeLabelProduct(context, repository, code, product, LabelPhotoStore.photosFor(context, code))
                    }
                },
                onDismiss = { sendRequest = null }
            )
        }
    }

    attachBarcodeFor?.let { (product, photos) ->
        if (!showAttachScanner) {
            AlertDialog(
                onDismissRequest = {
                    deleteLabelPhotos(photos)
                    attachBarcodeFor = null
                },
                title = { Text(stringResource(R.string.meal_ocr_attach_barcode_title)) },
                text = { Text(stringResource(R.string.meal_ocr_attach_barcode_text)) },
                confirmButton = {
                    TextButton(onClick = { showAttachScanner = true }) { Text(stringResource(R.string.meal_ocr_attach_barcode_scan)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        deleteLabelPhotos(photos)
                        attachBarcodeFor = null
                    }) { Text(stringResource(R.string.meal_ocr_attach_barcode_skip)) }
                }
            )
        } else {
            MealScanSheet(
                repository = repository,
                allowNetwork = false,
                onDismiss = {
                    showAttachScanner = false
                    deleteLabelPhotos(photos)
                    attachBarcodeFor = null
                },
                onProduct = { _, _ -> },
                onBarcode = { code ->
                    showAttachScanner = false
                    attachBarcodeFor = null
                    val attached = product.copy(barcode = code)
                    scope.launch {
                        repository.rememberProduct(code, attached)
                        contributeLabelProduct(context, repository, code, attached, photos)
                    }
                }
            )
        }
    }

    if (showEatSheet && current != null) {
        MealEatSheet(
            meal = current,
            totals = totals,
            showMacros = foodMacrosEnabled,
            onDismiss = { showEatSheet = false },
            onContinue = { prefill: JournalEntryInput ->
                showEatSheet = false
                journalRequest = JournalEditorRequest(
                    type = JournalEntryType.CARBS,
                    timestamp = prefill.timestamp,
                    prefill = prefill
                )
            }
        )
    }

    journalRequest?.let { request ->
        JournalEditorSheetHost(
            dashboardViewModel = dashboardViewModel,
            request = request,
            onDismiss = { journalRequest = null }
        )
    }

    if (showRename && current != null) {
        var text by remember(current.id) { mutableStateOf(current.label) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text(stringResource(R.string.meal_rename)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.meal_label_title)) }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { repository.updateMeal(current.id, label = text) }
                    showRename = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showDeleteConfirm && current != null) {
        val entryCount = linkedEntries.size
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.meal_delete)) },
            text = {
                Text(
                    if (entryCount == 0) stringResource(R.string.meal_delete_confirm)
                    else stringResource(R.string.meal_delete_confirm_entries, entryCount)
                )
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (entryCount > 0) {
                        // Everything: the meal, its items, and every journal entry logged for it
                        // (each through the normal delete path, so Nightscout tombstones are kept).
                        TextButton(
                            onClick = {
                                showDeleteConfirm = false
                                scope.launch {
                                    journalRepository.deleteEntriesForMeal(current.id)
                                    repository.deleteMeal(current.id)
                                    navController.popBackStack()
                                }
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) { Text(stringResource(R.string.meal_delete_all)) }
                    }
                    TextButton(onClick = {
                        showDeleteConfirm = false
                        scope.launch {
                            repository.deleteMeal(current.id)
                            navController.popBackStack()
                        }
                    }) { Text(stringResource(if (entryCount > 0) R.string.meal_delete_only else R.string.delete)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

internal data class ProductSheetRequest(
    val product: ScannedProduct?,
    val barcode: String?,
    val existingItem: MealItem?,
    /** Open with the value fields visible — an OCR result must be checked, not just accepted. */
    val startEditing: Boolean = false,
    /** Label photos behind an OCR product; uploaded (opt-in) or deleted after confirmation. */
    val photos: List<ContributionPhoto> = emptyList()
)

@Composable
private fun MealItemRow(item: MealItem, onClick: () -> Unit) {
    val contribution = item.contribution
    val carbsWord = stringResource(R.string.meal_carbs)
    val proteinWord = stringResource(R.string.meal_protein)
    val fatWord = stringResource(R.string.meal_fat)
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = listOfNotNull(item.brand, item.displayName).joinToString(" · "),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (item.plausibility.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
            Text(
                text = item.quantityText.ifBlank { "–" } + (item.amountGrams?.let { " · ${MealFormat.grams(it)} g" } ?: item.amountMilliliters?.let { " · ${MealFormat.grams(it)} ml" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = contribution?.summary(carbsWord, proteinWord, fatWord) ?: stringResource(R.string.meal_item_unresolved),
                style = MaterialTheme.typography.bodyMedium,
                color = if (contribution == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${sourceLabel(item.source)} · ${basisLabel(item.reference.basis)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MealTotalsCard(
    meal: Meal,
    totals: tk.glucodata.data.meal.MealTotals,
    showMacros: Boolean,
    onServingsChanged: (Float?) -> Unit,
    onCookedWeightChanged: (Float?) -> Unit
) {
    var servingsText by remember(meal.id, meal.servings) { mutableStateOf(MealFormat.editor(meal.servings)) }
    var weightText by remember(meal.id, meal.cookedWeightGrams) { mutableStateOf(MealFormat.editor(meal.cookedWeightGrams)) }
    val perServing = remember(totals, meal.servings) { MealMath.perServing(totals, meal.servings) }
    val carbsWord = stringResource(R.string.meal_carbs)
    val proteinWord = stringResource(R.string.meal_protein)
    val fatWord = stringResource(R.string.meal_fat)

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.meal_totals), style = MaterialTheme.typography.titleSmall)
            MacroLine(label = carbsWord, value = totals.facts.carbsGrams, prominent = true)
            if (showMacros) {
                MacroLine(label = proteinWord, value = totals.facts.proteinGrams)
                MacroLine(label = fatWord, value = totals.facts.fatGrams)
            }
            totals.facts.kcal?.let { MacroLine(label = stringResource(R.string.meal_kcal), value = it, unit = "") }
            if (totals.unresolvedItems > 0) {
                Text(
                    text = stringResource(R.string.meal_unresolved_items, totals.unresolvedItems),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = servingsText,
                    onValueChange = { text ->
                        servingsText = text
                        onServingsChanged(MealFormat.parseEditor(text))
                    },
                    label = { Text(stringResource(R.string.meal_servings_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = weightText,
                    onValueChange = { text ->
                        weightText = text
                        onCookedWeightChanged(MealFormat.parseEditor(text))
                    },
                    label = { Text(stringResource(R.string.meal_cooked_weight_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                text = stringResource(R.string.meal_servings_hint) + " " + stringResource(R.string.meal_cooked_weight_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            perServing?.let { portion ->
                Text(
                    text = stringResource(R.string.meal_per_serving) + ": " + portion.summary(carbsWord, proteinWord, fatWord),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
internal fun MacroLine(label: String, value: Float?, unit: String = "g", prominent: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = if (prominent) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium)
        Text(
            text = "${MealFormat.grams(value)} $unit".trim(),
            style = if (prominent) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
            fontWeight = if (prominent) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun LinkedEntryRow(entry: JournalEntry, presetName: String?, onClick: () -> Unit) {
    val timeText = remember(entry.timestamp) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.timestamp))
    }
    val amountText = when (entry.type) {
        JournalEntryType.CARBS -> "${MealFormat.grams(entry.amount)} g ${stringResource(R.string.meal_carbs)}"
        JournalEntryType.INSULIN -> "${MealFormat.grams(entry.amount)} U" + (presetName?.let { " · $it" } ?: "")
        else -> entry.title
    }
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = journalTypeColor(entry.type),
                shape = RoundedCornerShape(50),
                modifier = Modifier.width(6.dp).height(28.dp)
            ) {}
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(amountText, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = listOfNotNull(timeText, entry.note?.takeIf { it.isNotBlank() }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** The reference the engine uses when the whole meal is the thing being eaten. */
internal fun Meal.eatReference(): NutritionReference = NutritionReference.forMeal(servings, cookedWeightGrams)

internal data class OcrRequest(val barcode: String?)

/**
 * Before a send: what will go out, which label photos exist for the product, and the chance to
 * add or retake some. Photos are only mentioned when the photo switch is on.
 */
@Composable
private fun MealSendDialog(
    product: ScannedProduct,
    photos: List<ContributionPhoto>,
    uploadPhotos: Boolean,
    onAddPhotos: () -> Unit,
    onSend: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.meal_contribute_send)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(listOfNotNull(product.brand, product.displayName).joinToString(" · ") + " · " + product.barcode.orEmpty())
                if (uploadPhotos) {
                    val kinds = listOf(LabelPhotoKind.FRONT, LabelPhotoKind.NUTRITION, LabelPhotoKind.INGREDIENTS)
                    val labels = kinds.map { it to photoKindLabel(it) }
                    val status = labels.joinToString("   ") { (kind, label) ->
                        (if (photos.any { it.kind == kind }) "✓ " else "– ") + label
                    }
                    Text(stringResource(R.string.meal_send_photos_status, photos.size) + "\n" + status, style = MaterialTheme.typography.bodySmall)
                    if (photos.isEmpty()) {
                        Text(stringResource(R.string.meal_send_no_photos), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onAddPhotos) { Text(stringResource(R.string.meal_send_add_photos)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onSend) { Text(stringResource(R.string.meal_send_now)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

/**
 * After the user confirmed a product read from its label: send it to Open Food Facts when the
 * opt-in is set and credentials exist, then drop the photos either way. Only the confirmed
 * values and, if separately allowed, the photos leave the phone.
 */
private suspend fun contributeLabelProduct(
    context: android.content.Context,
    repository: MealRepository,
    barcode: String,
    product: ScannedProduct,
    photos: List<ContributionPhoto>
) {
    val settings = MealContributionSettings.load(context)
    if (!settings.enabled) {
        // Contributions are off: nothing is kept.
        deleteLabelPhotos(photos)
        return
    }
    // Keep the photos under the barcode so a later or repeated send can still carry them.
    val stored = withContext(Dispatchers.IO) { LabelPhotoStore.store(context, barcode, photos) }
    if (!settings.isUsable || product.facts.carbsGrams < 0f) return
    val result = withContext(Dispatchers.IO) {
        OpenFoodFactsContributor.contribute(
            barcode = barcode,
            product = product,
            userId = settings.userId,
            password = settings.password,
            languageCode = Locale.getDefault().language,
            appUuid = settings.appUuid,
            comment = "Values read from the label and confirmed in JugglucoNG",
            photos = if (settings.uploadPhotos) stored else emptyList()
        )
    }
    if (result is ContributionResult.Sent) {
        repository.markContributed(barcode)
        // Photos went out (or were not wanted): no need to keep them.
        if (!settings.uploadPhotos || result.photosFailed == 0) withContext(Dispatchers.IO) { LabelPhotoStore.clear(context, barcode) }
    }
    val message = when (result) {
        is ContributionResult.Sent -> context.getString(R.string.meal_contribute_sent)
        is ContributionResult.Rejected -> context.getString(R.string.meal_contribute_failed, result.message ?: "")
        is ContributionResult.Failed -> context.getString(R.string.meal_contribute_failed, result.message ?: "")
    }
    tk.glucodata.Applic.Toaster(message)
}
