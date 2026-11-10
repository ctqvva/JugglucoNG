@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package tk.glucodata.ui.journal

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import java.text.NumberFormat
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.R
import tk.glucodata.data.food.FoodAmount
import tk.glucodata.data.food.FoodCatalog
import tk.glucodata.data.food.FoodItem
import tk.glucodata.data.food.FoodMath
import tk.glucodata.data.food.FoodNutrients
import tk.glucodata.data.food.FoodSource
import tk.glucodata.data.food.OpenFoodFactsClient
import tk.glucodata.data.food.OpenFoodFactsResult
import tk.glucodata.data.food.ProductBarcode
import tk.glucodata.data.journal.JournalFood
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.IconButtonTooltip
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.cardShape
import tk.glucodata.ui.setup.InlineQrScannerCard
import tk.glucodata.ui.setup.PRODUCT_BARCODE_FORMATS
import tk.glucodata.ui.util.ExpressiveMotion

/** One food of the meal being entered: what it is, how much, and the amount as it was typed. */
data class JournalDraftFoodItem(
    val food: FoodItem,
    val amount: FoodAmount,
    val amountText: String = formatFoodQuantity(amount.quantity),
    /** False for a saved-meal snapshot whose quantity has no mapping to the library food. */
    val rememberUse: Boolean = true
) {
    val nutrients: FoodNutrients get() = FoodMath.nutrients(food, amount)
}

/** What asking Open Food Facts for the typed words came to. */
internal sealed interface FoodOnlineSearch {
    data object Idle : FoodOnlineSearch
    data object Loading : FoodOnlineSearch
    data class Done(val foods: List<FoodItem>) : FoodOnlineSearch
    data object Failed : FoodOnlineSearch
}

/** Where a scanned barcode stands. */
internal sealed interface FoodBarcodeLookup {
    data object Idle : FoodBarcodeLookup
    data class Looking(val barcode: String) : FoodBarcodeLookup
    data class NotFound(val barcode: String) : FoodBarcodeLookup
    data class Failed(val barcode: String) : FoodBarcodeLookup
}

/**
 * The food search of one screen: what is typed, what it found, and the two things that go out
 * to Open Food Facts when asked. Lookups run in the screen's own scope, so a result row
 * scrolling out of a lazy list does not cancel them.
 */
@Stable
internal class FoodPickerState(
    private val scope: CoroutineScope,
    private val context: Context,
    private val onlineSearch: suspend (String, String) -> OpenFoodFactsResult<List<FoodItem>> = { query, language ->
        withContext(Dispatchers.IO) { OpenFoodFactsClient.search(query, language) }
    },
    private val productLookup: suspend (String, String) -> OpenFoodFactsResult<FoodItem> = { code, language ->
        withContext(Dispatchers.IO) { OpenFoodFactsClient.product(code, language) }
    }
) {
    var library: List<JournalFood> = emptyList()
    var searching by mutableStateOf(false)
    var query by mutableStateOf("")
        private set
    var shelves by mutableStateOf(FoodCatalog.Shelves())
        private set
    var online: FoodOnlineSearch by mutableStateOf(FoodOnlineSearch.Idle)
        private set
    var scanning by mutableStateOf(false)
    var barcode: FoodBarcodeLookup by mutableStateOf(FoodBarcodeLookup.Idle)
        private set

    /** The food of the meal whose amount is open for editing. */
    var openItem by mutableIntStateOf(0)

    private var searchJob: Job? = null
    private var onlineJob: Job? = null
    private var barcodeJob: Job? = null

    private val language: String get() = FoodCatalog.appLocale(context).language.lowercase(Locale.ROOT)

    fun onQueryChange(text: String) {
        if (text == query) return
        query = text
        // Typing is searching, whichever way the field came to hold the focus.
        if (text.isNotEmpty() && !scanning) searching = true
        onlineJob?.cancel()
        online = FoodOnlineSearch.Idle
        searchJob?.cancel()
        if (text.isBlank()) {
            shelves = FoodCatalog.Shelves()
            return
        }
        searchJob = scope.launch {
            // A word being typed is not worth a search per letter.
            delay(SEARCH_DEBOUNCE_MILLIS)
            shelves = FoodCatalog.search(context, text, library)
        }
    }

    /** Searches again for what is typed, after the library changed under it. */
    fun refresh() {
        val text = query
        if (text.isBlank()) return
        searchJob?.cancel()
        searchJob = scope.launch { shelves = FoodCatalog.search(context, text, library) }
    }

    fun searchOnline() {
        val words = query.trim()
        if (words.length < FoodCatalog.MIN_QUERY_LENGTH || online == FoodOnlineSearch.Loading) return
        online = FoodOnlineSearch.Loading
        onlineJob = scope.launch {
            val result = onlineSearch(words, language)
            online = when (result) {
                is OpenFoodFactsResult.Found -> FoodOnlineSearch.Done(result.value)
                else -> FoodOnlineSearch.Failed
            }
        }
    }

    /** Finds the product for [code]: in the library when it was scanned before, else online. */
    fun lookUp(code: String, onFound: (FoodItem) -> Unit) {
        barcodeJob?.cancel()
        library.firstOrNull { (ProductBarcode.normalize(it.barcode) ?: it.barcode) == code && !it.isArchived }?.let {
            barcode = FoodBarcodeLookup.Idle
            onFound(it.toFoodItem())
            return
        }
        barcode = FoodBarcodeLookup.Looking(code)
        barcodeJob = scope.launch {
            val result = productLookup(code, language)
            when (result) {
                is OpenFoodFactsResult.Found -> {
                    barcode = FoodBarcodeLookup.Idle
                    onFound(result.value)
                }
                is OpenFoodFactsResult.NotFound -> barcode = FoodBarcodeLookup.NotFound(code)
                is OpenFoodFactsResult.Failed -> barcode = FoodBarcodeLookup.Failed(code)
            }
        }
    }

    fun activateSearch() {
        barcodeJob?.cancel()
        barcode = FoodBarcodeLookup.Idle
        scanning = false
        searching = true
    }

    fun startScanning() {
        close()
        scanning = true
    }

    /** Back to the resting field: nothing typed, nothing listed, the camera off. */
    fun close() {
        searchJob?.cancel()
        onlineJob?.cancel()
        barcodeJob?.cancel()
        query = ""
        shelves = FoodCatalog.Shelves()
        online = FoodOnlineSearch.Idle
        barcode = FoodBarcodeLookup.Idle
        scanning = false
        searching = false
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MILLIS = 120L
    }
}

@Composable
internal fun rememberFoodPickerState(library: List<JournalFood>): FoodPickerState {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext
    return remember(scope, context) { FoodPickerState(scope, context) }.also { it.library = library }
}

/** A food typed in from a label: per 100 g, to be filled in on its card. */
internal fun customFood(name: String, barcode: String? = null): FoodItem = FoodItem(
    key = "custom:" + UUID.randomUUID(),
    source = FoodSource.CUSTOM,
    name = name.trim(),
    referenceGrams = 100f,
    carbs = 0f,
    barcode = barcode
)

/**
 * The food part of the journal's entry sheet, as rows of the sheet's own list: the search
 * field, what it found, and the meal put together so far.
 *
 * A row selects one food and closes search; its plus adds an ingredient and keeps search
 * open. Amounts start from the usual quantity and can be changed on each food's own card.
 *
 * @param row adds one keyed row to the sheet.
 */
internal fun LazyListScope.journalFoodRows(
    row: (key: String, content: @Composable () -> Unit) -> Unit,
    state: FoodPickerState,
    suggestions: FoodCatalog.Suggestions,
    items: List<JournalDraftFoodItem>,
    macrosEnabled: Boolean,
    onlineEnabled: Boolean,
    onItemsChange: (List<JournalDraftFoodItem>) -> Unit,
    onAddFood: (FoodItem) -> Unit,
    onSelectFood: (FoodItem) -> Unit
) {
    val mealCounts = items.groupingBy { it.food.mealKey(state.library) }.eachCount()
    val countInMeal: (FoodItem) -> Int = { mealCounts[it.mealKey(state.library)] ?: 0 }
    val remove: (Int) -> Unit = { index ->
        onItemsChange(items.filterIndexed { itemIndex, _ -> itemIndex != index })
        state.openItem = (index - 1).coerceAtLeast(0)
    }
    val addAndClose: (FoodItem) -> Unit = { food ->
        onAddFood(food)
        state.close()
    }
    val select: (FoodItem) -> Unit = { food ->
        onSelectFood(food)
        state.close()
    }

    row("food_search") {
        val focusManager = LocalFocusManager.current
        FoodSearchField(
            query = state.query,
            onQueryChange = state::onQueryChange,
            active = state.searching,
            scanning = state.scanning,
            onActivate = state::activateSearch,
            onClose = {
                focusManager.clearFocus()
                state.close()
            },
            onScan = if (onlineEnabled) {
                {
                    focusManager.clearFocus()
                    state.startScanning()
                }
            } else {
                null
            },
            onSearch = if (onlineEnabled) state::searchOnline else null
        )
    }

    if (items.isNotEmpty()) {
        row("food_meal") {
            FoodMealList(
                items = items,
                openItem = state.openItem,
                macrosEnabled = macrosEnabled,
                onOpen = { state.openItem = it },
                onChange = { index, item ->
                    onItemsChange(items.toMutableList().also { it[index] = item })
                },
                onRemove = remove
            )
        }
    }

    if (state.scanning) {
        row("food_scanner") {
            FoodBarcodeScanner(
                lookup = state.barcode,
                onBarcode = { code -> state.lookUp(code, addAndClose) },
                onEnterValues = { code -> addAndClose(customFood(name = code, barcode = code)) }
            )
        }
    } else if (state.searching) {
        if (state.query.isBlank()) {
            if (suggestions.recent.isNotEmpty()) {
                row("food_shelf_recent") {
                    FoodShelf(stringResource(R.string.food_recent), suggestions.recent, macrosEnabled, onAddFood, select, countInMeal)
                }
            }
            if (suggestions.mine.isNotEmpty()) {
                row("food_shelf_mine") {
                    FoodShelf(stringResource(R.string.food_my_foods), suggestions.mine, macrosEnabled, onAddFood, select, countInMeal)
                }
            }
        } else {
            val shelves = state.shelves
            if (shelves.library.isNotEmpty()) {
                row("food_shelf_mine") {
                    FoodShelf(stringResource(R.string.food_my_foods), shelves.library, macrosEnabled, onAddFood, select, countInMeal)
                }
            }
            if (shelves.common.isNotEmpty()) {
                row("food_shelf_common") {
                    FoodShelf(stringResource(R.string.food_common_foods), shelves.common, macrosEnabled, onAddFood, select, countInMeal)
                }
            }
            val found = (state.online as? FoodOnlineSearch.Done)?.foods.orEmpty()
            if (found.isNotEmpty()) {
                row("food_shelf_online") {
                    FoodShelf(stringResource(R.string.open_food_facts), found, macrosEnabled, onAddFood, select, countInMeal)
                }
            }
            if (shelves.reference.isNotEmpty()) {
                row("food_shelf_reference") {
                    FoodShelf(stringResource(R.string.food_reference_tables), shelves.reference, macrosEnabled, onAddFood, select, countInMeal)
                }
            }
            row("food_search_actions") {
                FoodSearchActions(
                    query = state.query.trim(),
                    online = state.online.takeIf { onlineEnabled },
                    canSearchOnline = state.query.trim().length >= FoodCatalog.MIN_QUERY_LENGTH,
                    onSearchOnline = state::searchOnline,
                    onEnterValues = { addAndClose(customFood(state.query)) }
                )
            }
        }
    } else if (items.isEmpty() && suggestions.recent.isNotEmpty()) {
        row("food_recent_chips") {
            FoodRecentChips(foods = suggestions.recent, onAdd = addAndClose)
        }
    }

}

/**
 * The search field. At rest it is a field with a hint and a barcode button; focusing it turns
 * the search on, and Close turns it off again.
 */
@Composable
internal fun FoodSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    active: Boolean,
    onActivate: () -> Unit,
    onClose: (() -> Unit)?,
    onScan: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** The keyboard's search key, when it has something to do. */
    onSearch: (() -> Unit)? = null,
    scanning: Boolean = false
) {
    val haptics = LocalHapticFeedback.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = CircleShape
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(12.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                enabled = enabled,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { if (it.isFocused) onActivate() },
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(R.string.journal_food_search),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        innerTextField()
                    }
                }
            )
            if (query.isNotEmpty() && !scanning) {
                IconButtonTooltip(stringResource(R.string.clear)) {
                    IconButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onQueryChange("")
                        },
                        shapes = IconButtonDefaults.shapes()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.clear),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (onScan != null || scanning) {
                val label = stringResource(if (scanning) R.string.close else R.string.food_scan_barcode)
                val rotation by animateFloatAsState(if (scanning) 90f else 0f,
                    animationSpec = ExpressiveMotion.fastSpatial(), label = "foodScanRotation")
                IconButtonTooltip(label) {
                    IconButton(onClick = { if (scanning) onClose?.invoke() else onScan?.invoke() },
                        enabled = enabled, shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier.semantics { contentDescription = label }) {
                        Crossfade(scanning, animationSpec = ExpressiveMotion.fastEffects(), label = "foodScanIcon",
                            modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = rotation }) { open ->
                            Icon(if (open) Icons.Default.Close else Icons.Default.QrCodeScanner,
                                contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (active && !scanning && onClose != null) {
                TextButton(
                    onClick = onClose,
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.TextButtonContentPadding
                ) {
                    Text(text = stringResource(R.string.close))
                }
            }
        }
    }
}

/** The foods eaten lately, one tap each, offered before anything is searched for. */
@Composable
private fun FoodRecentChips(
    foods: List<FoodItem>,
    onAdd: (FoodItem) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        foods.forEach { food ->
            AssistChip(
                onClick = { onAdd(food) },
                label = {
                    Text(
                        text = food.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 160.dp)
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
        }
    }
}

/** A labelled group of foods from one shelf. */
@Composable
internal fun FoodShelf(
    title: String,
    foods: List<FoodItem>,
    macrosEnabled: Boolean,
    onAdd: (FoodItem) -> Unit,
    onSelect: (FoodItem) -> Unit,
    countInMeal: (FoodItem) -> Int
) {
    val focusManager = LocalFocusManager.current
    val haptics = LocalHapticFeedback.current
    Column {
        SectionLabel(text = title, topPadding = 4.dp)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            foods.forEachIndexed { index, food ->
                FoodResultRow(
                    food = food,
                    addedCount = countInMeal(food),
                    macrosEnabled = macrosEnabled,
                    position = foodCardPosition(index, foods.size),
                    onAdd = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onAdd(food)
                    },
                    onSelect = {
                        // The keyboard has done its part once a food is picked.
                        focusManager.clearFocus()
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(food)
                    }
                )
            }
        }
    }
}

@Composable
private fun FoodResultRow(
    food: FoodItem,
    addedCount: Int,
    macrosEnabled: Boolean,
    position: CardPosition,
    onAdd: () -> Unit,
    onSelect: () -> Unit
) {
    val added = addedCount > 0
    val countDescription = stringResource(R.string.selected_count, addedCount)
    val rowColor by animateColorAsState(
        if (added) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ExpressiveMotion.fastEffects(), label = "foodAddedRow")
    val buttonColor by animateColorAsState(
        if (added) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
        ExpressiveMotion.fastEffects(), label = "foodAddedButton")
    val buttonContent by animateColorAsState(
        if (added) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
        ExpressiveMotion.fastEffects(), label = "foodAddedButtonContent")
    Surface(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth().semantics {
            selected = added
            if (added) stateDescription = countDescription
        },
        color = rowColor,
        shape = cardShape(position, FoodGroupRadius)
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 64.dp)
                .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = food.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = foodSummary(food, macrosEnabled),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            IconButtonTooltip(stringResource(R.string.journal_add_food)) {
                BadgedBox(badge = {
                    if (added) Badge(containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                        Text(NumberFormat.getIntegerInstance().format(addedCount))
                    }
                }) {
                    FilledTonalIconButton(onClick = onAdd, modifier = Modifier.size(48.dp).semantics {
                        if (added) stateDescription = countDescription
                    }, colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = buttonColor, contentColor = buttonContent),
                        shapes = IconButtonDefaults.shapes()) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = stringResource(R.string.journal_add_food),
                            modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

/** A saved copy and its source result share feedback without comparing translated names. */
internal fun FoodItem.mealKey(library: List<JournalFood>): String =
    library.firstOrNull { isLibraryCopy(it) }?.let { JournalFood.LIBRARY_KEY_PREFIX + it.id }
        ?: ProductBarcode.normalize(barcode)?.let { "barcode:$it" } ?: key

/** Under the results: ask Open Food Facts, or type the food in from its label. */
@Composable
private fun FoodSearchActions(
    query: String,
    online: FoodOnlineSearch?,
    canSearchOnline: Boolean,
    onSearchOnline: () -> Unit,
    onEnterValues: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (online) {
            null -> Unit
            FoodOnlineSearch.Loading -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                contentAlignment = Alignment.Center
            ) {
                LoadingIndicator()
            }

            else -> {
                val message = when {
                    online is FoodOnlineSearch.Failed -> stringResource(R.string.food_online_failed)
                    online is FoodOnlineSearch.Done && online.foods.isEmpty() ->
                        stringResource(R.string.food_online_empty)
                    else -> null
                }
                if (message != null) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
                if (online !is FoodOnlineSearch.Done) {
                    FilledTonalButton(
                        onClick = onSearchOnline,
                        enabled = canSearchOnline,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp),
                        shapes = ButtonDefaults.shapes(),
                        contentPadding = ButtonDefaults.ContentPadding
                    ) {
                        Icon(
                            imageVector = Icons.Default.Public,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(R.string.food_search_online))
                    }
                }
            }
        }
        OutlinedButton(
            onClick = {
                focusManager.clearFocus()
                onEnterValues()
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            shapes = ButtonDefaults.shapes(),
            contentPadding = ButtonDefaults.ContentPadding
        ) {
            Icon(imageVector = Icons.Default.EditNote, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.food_enter_values, query),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** The camera, and what became of the barcode it read. */
@Composable
internal fun FoodBarcodeScanner(
    lookup: FoodBarcodeLookup,
    onBarcode: (String) -> Unit,
    onEnterValues: (String) -> Unit,
    enabled: Boolean = true
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (lookup) {
            FoodBarcodeLookup.Idle -> InlineQrScannerCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                barcodeFormats = PRODUCT_BARCODE_FORMATS,
                scannerEnabled = enabled,
                // Anything that is not a product barcode leaves the camera running.
                onScanResult = { raw -> ProductBarcode.normalize(raw)?.also(onBarcode) != null }
            )

            is FoodBarcodeLookup.Looking -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
            ) {
                LoadingIndicator()
                Text(
                    text = stringResource(R.string.food_barcode_looking, lookup.barcode),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            is FoodBarcodeLookup.NotFound -> {
                Text(
                    text = stringResource(R.string.food_barcode_not_found, lookup.barcode),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                FilledTonalButton(
                    onClick = { onEnterValues(lookup.barcode) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.ContentPadding
                ) {
                    Icon(Icons.Default.EditNote, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.food_enter_label_values))
                }
            }

            is FoodBarcodeLookup.Failed -> {
                Text(
                    text = stringResource(R.string.food_online_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                FilledTonalButton(
                    onClick = { onBarcode(lookup.barcode) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.ContentPadding
                ) {
                    Text(text = stringResource(R.string.food_try_again))
                }
            }
        }
    }
}

internal val FoodGroupRadius = 20.dp

internal fun foodCardPosition(index: Int, size: Int): CardPosition = when {
    size <= 1 -> CardPosition.SINGLE
    index == 0 -> CardPosition.TOP
    index == size - 1 -> CardPosition.BOTTOM
    else -> CardPosition.MIDDLE
}
