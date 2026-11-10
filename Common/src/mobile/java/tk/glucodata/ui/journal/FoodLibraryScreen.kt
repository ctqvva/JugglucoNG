@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package tk.glucodata.ui.journal

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.R
import tk.glucodata.data.food.FoodCatalog
import tk.glucodata.data.food.FoodItem
import tk.glucodata.data.food.FoodLibraryTransfer
import tk.glucodata.data.journal.JournalFood
import tk.glucodata.ui.components.AdaptiveButtonRow
import tk.glucodata.ui.components.AppTopBar
import tk.glucodata.ui.components.CompactSheetDragHandle
import tk.glucodata.ui.components.IconButtonTooltip
import tk.glucodata.ui.components.MasterSwitchCard
import tk.glucodata.ui.components.StableModalBottomSheet
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.IconTile
import tk.glucodata.ui.components.IconTileDefaults
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.SettingsItem
import tk.glucodata.ui.components.SettingsSwitchItem
import tk.glucodata.ui.components.cardShape
import tk.glucodata.ui.util.ExpressiveMotion
import tk.glucodata.ui.viewmodel.DashboardViewModel

/** What the library screen can do; the screen itself only shows. */
internal class FoodLibraryActions(
    val onLibraryEnabledChange: (Boolean) -> Unit,
    val onOnlineEnabledChange: (Boolean) -> Unit,
    val onEdit: (JournalFood) -> Unit,
    val onCreate: () -> Unit,
    val onSave: (FoodItem) -> Unit,
    val onImport: () -> Unit,
    val onExport: () -> Unit,
    val onCreateFromBarcode: (String) -> Unit = { onCreate() },
    val onDelete: (Set<Long>) -> Unit = {},
    val onArchive: (Set<Long>, Boolean) -> Unit = { _, _ -> },
    val onMove: (Set<Long>, Long?) -> Unit = { _, _ -> }
)

@Composable
fun JournalFoodLibraryScreen(
    navController: NavController,
    viewModel: DashboardViewModel
) {
    val allFoods by viewModel.journalFoods.collectAsStateWithLifecycle()
    val foodMacrosEnabled by viewModel.journalFoodMacrosEnabled.collectAsStateWithLifecycle()
    val foodLibraryEnabled by viewModel.journalFoodLibraryEnabled.collectAsStateWithLifecycle()
    val foodOnlineEnabled by viewModel.journalFoodOnlineEnabled.collectAsStateWithLifecycle()
    var editingFood by remember { mutableStateOf<JournalFood?>(null) }
    var creatingFood by remember { mutableStateOf(false) }
    var creatingBarcode by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val showFailure: () -> Unit = {
        Toast.makeText(context, R.string.food_manage_failed, Toast.LENGTH_LONG).show()
    }
    val deleteFoods: (Set<Long>) -> Unit = { ids ->
        viewModel.deleteJournalFoods(ids) { result ->
            result.fold(onSuccess = { deleted ->
                if (deleted.rows.isNotEmpty()) scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    val response = snackbar.showSnackbar(
                        context.getString(R.string.food_deleted, deleted.rows.size), context.getString(R.string.undo),
                        duration = SnackbarDuration.Short)
                    if (response == SnackbarResult.ActionPerformed) {
                        viewModel.undoDeleteJournalFoods(deleted) { success -> if (!success) showFailure() }
                    }
                }
            }, onFailure = { showFailure() })
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                }.getOrNull()?.let(FoodLibraryTransfer::parse)
            }
            if (parsed == null || parsed.foods.isEmpty()) {
                Toast.makeText(context, R.string.food_import_empty, Toast.LENGTH_LONG).show()
            } else {
                viewModel.importJournalFoods(parsed.foods) { added, replaced ->
                    Toast.makeText(
                        context,
                        context.getString(R.string.import_summary, added, parsed.skipped, replaced),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(FoodLibraryTransfer.MIME_TYPE)
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
                        it.write(FoodLibraryTransfer.export(allFoods))
                    } != null
                }.getOrDefault(false)
            }
            Toast.makeText(
                context,
                if (written) R.string.export_successful else R.string.export_failed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    FoodLibraryLayout(
        foods = allFoods,
        macrosEnabled = foodMacrosEnabled,
        libraryEnabled = foodLibraryEnabled,
        onlineEnabled = foodOnlineEnabled,
        snackbar = snackbar,
        onNavigateBack = { navController.popBackStack() },
        actions = FoodLibraryActions(
            onLibraryEnabledChange = viewModel::setJournalFoodLibraryEnabled,
            onOnlineEnabledChange = viewModel::setJournalFoodOnlineEnabled,
            onEdit = { editingFood = it },
            onCreate = { creatingBarcode = null; creatingFood = true },
            onCreateFromBarcode = { creatingBarcode = it; creatingFood = true },
            onSave = { food ->
                viewModel.keepJournalFood(food) { saved ->
                    Toast.makeText(context, if (saved) R.string.saved else R.string.savefailed, Toast.LENGTH_SHORT).show()
                }
            },
            onImport = { importLauncher.launch(arrayOf("text/*", "application/json", "application/octet-stream")) },
            onExport = { exportLauncher.launch(FoodLibraryTransfer.DEFAULT_FILE_NAME) },
            onDelete = deleteFoods,
            onArchive = { ids, archived -> viewModel.setJournalFoodsArchived(ids, archived) { if (!it) showFailure() } },
            onMove = { ids, before -> viewModel.moveJournalFoodsBefore(ids, before) { if (!it) showFailure() } }
        )
    )

    if (creatingFood) {
        FoodEditorSheet(
            food = null,
            initialBarcode = creatingBarcode,
            foodMacrosEnabled = foodMacrosEnabled,
            nextSortOrder = allFoods.size + 100,
            onDismiss = { creatingFood = false },
            onSave = {
                viewModel.saveJournalFood(it)
                creatingFood = false
            },
            onDelete = null
        )
    }

    editingFood?.let { food ->
        FoodEditorSheet(
            food = food,
            foodMacrosEnabled = foodMacrosEnabled,
            nextSortOrder = allFoods.size + 100,
            onDismiss = { editingFood = null },
            onSave = {
                viewModel.saveJournalFood(it)
                editingFood = null
            },
            onDelete = {
                deleteFoods(setOf(food.id))
                editingFood = null
            }
        )
    }
}

/** Pinned file action and a scrolling library body; food count cannot bury file controls. */
@Composable
internal fun FoodLibraryLayout(
    foods: List<JournalFood>,
    macrosEnabled: Boolean,
    libraryEnabled: Boolean,
    onlineEnabled: Boolean,
    onNavigateBack: () -> Unit,
    actions: FoodLibraryActions,
    snackbar: SnackbarHostState = remember { SnackbarHostState() }
) {
    var showTransfer by remember { mutableStateOf(false) }
    val management = remember { FoodLibraryManagement() }
    val focusManager = LocalFocusManager.current
    BackHandler(enabled = management.selecting, onBack = management::cancel)
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (management.selecting) Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center) {
                FoodManagementToolbar(foods, management, actions)
            }
        },
        topBar = {
            AppTopBar(
                title = stringResource(R.string.journal_food_library),
                onNavigateBack = { if (management.selecting) management.cancel() else onNavigateBack() },
                actions = {
                    IconButtonTooltip(stringResource(R.string.food_select)) {
                        IconButton(onClick = { focusManager.clearFocus(); management.selecting = true },
                            enabled = libraryEnabled && foods.isNotEmpty() && !management.selecting,
                            shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Default.Checklist, contentDescription = stringResource(R.string.food_select))
                        }
                    }
                    val label = stringResource(R.string.food_file_title)
                    IconButtonTooltip(label) {
                        IconButton(
                            onClick = { focusManager.clearFocus(); showTransfer = true },
                            shapes = IconButtonDefaults.shapes()
                        ) {
                            Icon(Icons.Default.ImportExport, contentDescription = label)
                        }
                    }
                }
            )
        }
    ) { padding ->
        FoodLibraryContent(foods, macrosEnabled, libraryEnabled, onlineEnabled, actions,
            modifier = Modifier.padding(padding), management = management)
    }
    FoodManagementDialogs(foods, management, actions)
    if (showTransfer) {
        FoodLibraryTransferSheet(
            canExport = foods.any { it.kept && !it.isArchived },
            onDismiss = { showTransfer = false },
            onImport = { showTransfer = false; actions.onImport() },
            onExport = { showTransfer = false; actions.onExport() }
        )
    }
}

@Composable
internal fun FoodLibraryTransferSheet(
    canExport: Boolean,
    onDismiss: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit
) {
    StableModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { CompactSheetDragHandle() }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(stringResource(R.string.food_file_title), style = MaterialTheme.typography.headlineSmallEmphasized)
            AdaptiveButtonRow {
                OutlinedButton(
                    onClick = onImport,
                    modifier = Modifier.heightIn(min = 56.dp),
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.ContentPadding
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.food_import))
                }
                OutlinedButton(
                    onClick = onExport,
                    enabled = canExport,
                    modifier = Modifier.heightIn(min = 56.dp),
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.ContentPadding
                ) {
                    Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.export))
                }
            }
            Text(stringResource(R.string.food_file_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The library screen's body. With nothing typed it is the person's own foods and what feeds
 * the search; typing turns it into the search itself, where a food from any shelf can be
 * looked at and saved.
 */
@Composable
internal fun FoodLibraryContent(
    foods: List<JournalFood>,
    macrosEnabled: Boolean,
    libraryEnabled: Boolean,
    onlineEnabled: Boolean,
    actions: FoodLibraryActions,
    modifier: Modifier = Modifier,
    picker: FoodPickerState = rememberFoodPickerState(foods),
    management: FoodLibraryManagement = remember { FoodLibraryManagement() }
) {
    val focusManager = LocalFocusManager.current
    var expandedKey by remember { mutableStateOf<String?>(null) }
    var scannedFood by remember { mutableStateOf<FoodItem?>(null) }
    val browseFoods = (listOfNotNull(scannedFood) + (picker.online as? FoodOnlineSearch.Done)?.foods.orEmpty())
        .distinctBy { it.key }
    val savedBrowseIds = foods.filter { row -> row.kept && !row.isArchived && browseFoods.any { it.isLibraryCopy(row) } }
        .mapTo(HashSet()) { it.id }
    val mine = remember(foods) { foods.filter { it.kept && !it.isArchived } }
    val remembered = remember(foods) {
        foods.filter { !it.kept && !it.isArchived }.sortedByDescending { it.lastUsedAt }
    }
    val disabled = remember(foods) { foods.filter { it.isArchived } }
    val ownMatches = (FoodCatalog.searchLibrary(foods, picker.query, limit = 40) +
        foods.filter { it.id in savedBrowseIds || picker.shelves.library.any { found -> found.libraryId == it.id } })
        .distinctBy { it.id }
    val unsavedBrowse = browseFoods.filter { item -> foods.none { it.kept && !it.isArchived && item.isLibraryCopy(it) } }
    val visibleIds = (if (picker.query.isBlank()) foods else ownMatches).mapTo(HashSet()) { it.id }
    SideEffect { management.visibleIds = if (libraryEnabled) visibleIds else emptySet() }
    LaunchedEffect(visibleIds, libraryEnabled) {
        management.selectedIds = management.selectedIds.intersect(visibleIds)
        if (!libraryEnabled) management.cancel()
    }
    // A food saved from a shelf moves to the person's own; the lists should say so at once.
    LaunchedEffect(foods) { picker.refresh() }
    LaunchedEffect(libraryEnabled, onlineEnabled) {
        if (!libraryEnabled || !onlineEnabled) picker.close()
    }

    val myFoodsLabel = stringResource(R.string.food_my_foods)
    val recentLabel = stringResource(R.string.food_recent)
    val disabledLabel = stringResource(R.string.journal_food_disabled_library)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        libraryItem(key = "food_library_enabled") {
            MasterSwitchCard(
                title = stringResource(R.string.journal_food_library),
                subtitle = stringResource(R.string.journal_food_choose_desc),
                checked = libraryEnabled,
                onCheckedChange = actions.onLibraryEnabledChange,
                icon = Icons.Default.Restaurant
            )
        }

        libraryItem(key = "food_online_enabled") {
            SettingsSwitchItem(
                title = stringResource(R.string.open_food_facts),
                subtitle = stringResource(R.string.food_online_desc),
                checked = onlineEnabled,
                onCheckedChange = actions.onOnlineEnabledChange,
                icon = Icons.Default.Public,
                iconTint = MaterialTheme.colorScheme.tertiary,
                enabled = libraryEnabled,
                position = CardPosition.SINGLE
            )
        }

        libraryItem(key = "food_add") {
            FilledTonalButton(
                onClick = actions.onCreate,
                enabled = libraryEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.ContentPadding
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(text = stringResource(R.string.journal_add_food))
            }
        }

        libraryItem(key = "food_search") {
            FoodSearchField(
                query = picker.query,
                onQueryChange = {
                    expandedKey = null
                    scannedFood = null
                    picker.onQueryChange(it)
                },
                active = picker.scanning,
                onActivate = picker::activateSearch,
                onClose = if (picker.scanning && picker.barcode is FoodBarcodeLookup.Looking) picker::close else null,
                onScan = if (onlineEnabled) {
                    {
                        focusManager.clearFocus()
                        scannedFood = null
                        expandedKey = null
                        picker.startScanning()
                    }
                } else null,
                enabled = libraryEnabled,
                onSearch = if (onlineEnabled) picker::searchOnline else null,
                modifier = Modifier.alpha(if (libraryEnabled) 1f else 0.48f)
            )
        }

        if (!libraryEnabled) return@LazyColumn

        if (picker.scanning && onlineEnabled) {
            libraryItem(key = "food_scanner") {
                FoodBarcodeScanner(
                    lookup = picker.barcode,
                    onBarcode = { code ->
                        picker.lookUp(code) { found ->
                            picker.close()
                            scannedFood = found
                            expandedKey = found.key
                            picker.onQueryChange(found.name)
                        }
                    },
                    onEnterValues = { code -> picker.close(); actions.onCreateFromBarcode(code) },
                    onClose = picker::close
                )
            }
            return@LazyColumn
        }

        if (picker.query.isBlank()) {
            managedFoodGroup(myFoodsLabel, mine, macrosEnabled, management, foods, actions)
            managedFoodGroup(recentLabel, remembered, macrosEnabled, management, foods, actions)
            managedFoodGroup(disabledLabel, disabled, macrosEnabled, management, foods, actions)
        } else {
            val shelves = picker.shelves
            managedFoodGroup(myFoodsLabel, ownMatches, macrosEnabled, management, foods, actions)
            val toggle: (FoodItem) -> Unit = { food ->
                focusManager.clearFocus()
                expandedKey = food.key.takeIf { it != expandedKey }
            }
            if (shelves.common.isNotEmpty()) {
                libraryItem(key = "food_shelf_common") {
                    FoodBrowseGroup(
                        stringResource(R.string.food_common_foods), shelves.common, macrosEnabled,
                        expandedKey, toggle, actions.onSave
                    )
                }
            }
            val online = picker.online
            if (onlineEnabled && unsavedBrowse.isNotEmpty()) {
                libraryItem(key = "food_shelf_online") {
                    FoodBrowseGroup(
                        stringResource(R.string.open_food_facts), unsavedBrowse, macrosEnabled,
                        expandedKey, toggle, actions.onSave
                    )
                }
            }
            if (shelves.reference.isNotEmpty()) {
                libraryItem(key = "food_shelf_reference") {
                    FoodBrowseGroup(
                        stringResource(R.string.food_reference_tables), shelves.reference, macrosEnabled,
                        expandedKey, toggle, actions.onSave
                    )
                }
            }
            if (onlineEnabled && online !is FoodOnlineSearch.Done && scannedFood == null) {
                libraryItem(key = "food_online") {
                    FoodOnlineButton(
                        online = online,
                        enabled = picker.query.trim().length >= FoodCatalog.MIN_QUERY_LENGTH,
                        onSearch = {
                            focusManager.clearFocus()
                            picker.searchOnline()
                        }
                    )
                }
            } else if (online is FoodOnlineSearch.Done && online.foods.isEmpty()) {
                libraryItem(key = "food_online") {
                    FoodCaption(stringResource(R.string.food_online_empty))
                }
            }
        }
    }
}

@Composable
private fun FoodCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Composable
private fun FoodOnlineButton(
    online: FoodOnlineSearch,
    enabled: Boolean,
    onSearch: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (online == FoodOnlineSearch.Failed) FoodCaption(stringResource(R.string.food_online_failed))
        if (online == FoodOnlineSearch.Loading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                contentAlignment = Alignment.Center
            ) {
                LoadingIndicator()
            }
        } else {
            FilledTonalButton(
                onClick = onSearch,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.ContentPadding
            ) {
                Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(text = stringResource(R.string.food_search_online))
            }
        }
    }
}

/** Gaps between controls and shelves; managed rows use the smaller grouped-row gap. */
private fun LazyListScope.libraryItem(key: String, content: @Composable () -> Unit) {
    item(key = key) { Box(Modifier.padding(bottom = 12.dp)) { content() } }
}

/** Foods from a shelf under a label; a row opens to show what is in the food and to save it. */
@Composable
private fun FoodBrowseGroup(
    title: String,
    foods: List<FoodItem>,
    macrosEnabled: Boolean,
    expandedKey: String?,
    onToggle: (FoodItem) -> Unit,
    onSave: (FoodItem) -> Unit
) {
    Column {
        SectionLabel(text = title, topPadding = 12.dp)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            foods.forEachIndexed { index, food ->
                FoodBrowseRow(
                    food = food,
                    macrosEnabled = macrosEnabled,
                    position = foodCardPosition(index, foods.size),
                    expanded = food.key == expandedKey,
                    onToggle = { onToggle(food) },
                    onSave = { onSave(food) }
                )
            }
        }
    }
}

@Composable
private fun FoodBrowseRow(
    food: FoodItem,
    macrosEnabled: Boolean,
    position: CardPosition,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSave: () -> Unit
) {
    val shape = cardShape(position)
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
                    .clickable(role = Role.Button, onClick = onToggle)
                    .heightIn(min = 72.dp)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // No wash: the food is not the person's own yet.
                IconTile(
                    icon = Icons.Default.Restaurant,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    containerColor = Color.Transparent
                )
                Spacer(Modifier.width(IconTileDefaults.Gap))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = food.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = if (expanded) 4 else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = foodSummary(food, macrosEnabled),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded) {
                Column(
                    // The text column of the row above: one left edge for the whole card.
                    modifier = Modifier.padding(start = 68.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FoodComposition(food)
                    FilledTonalButton(
                        onClick = onSave,
                        modifier = Modifier.align(Alignment.End),
                        shapes = ButtonDefaults.shapes(),
                        contentPadding = ButtonDefaults.ContentPadding
                    ) {
                        Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(text = stringResource(R.string.journal_food_save_to_library))
                    }
                }
            }
        }
    }
}

/** What the food's reference amount holds, and the measures it comes in. */
@Composable
private fun FoodComposition(food: FoodItem) {
    val gram = stringResource(R.string.unit_carbs_short)
    val rows = listOfNotNull(
        stringResource(R.string.food_carbs) to "${formatFoodNumber(food.carbs)} $gram",
        food.fiber?.let { stringResource(R.string.food_fiber) to "${formatFoodNumber(it)} $gram" },
        food.protein?.let { stringResource(R.string.journal_food_protein) to "${formatFoodNumber(it)} $gram" },
        food.fat?.let { stringResource(R.string.journal_food_fat) to "${formatFoodNumber(it)} $gram" },
        food.kcal?.let { stringResource(R.string.food_energy) to stringResource(R.string.food_kcal_value, formatFoodNumber(it)) }
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { (label, value) ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    textAlign = TextAlign.End
                )
            }
        }
        if (food.portions.isNotEmpty()) {
            Text(
                text = food.portions.map { foodPortionLabel(it, food.isLiquid) }.joinToString("   "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}


/** Identity, rather than translated names or the active search words, links a saved result. */
internal fun FoodItem.isLibraryCopy(food: JournalFood): Boolean =
    libraryId == food.id || key == food.sourceKey ||
        barcode != null && food.barcode != null &&
        (tk.glucodata.data.food.ProductBarcode.normalize(barcode) ?: barcode) ==
        (tk.glucodata.data.food.ProductBarcode.normalize(food.barcode) ?: food.barcode)
