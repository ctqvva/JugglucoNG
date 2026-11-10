@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package tk.glucodata.ui.journal

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tk.glucodata.R
import tk.glucodata.data.journal.JournalFood
import tk.glucodata.ui.components.*

@Stable
internal class FoodLibraryManagement {
    var selecting by mutableStateOf(false)
    var selectedIds by mutableStateOf(emptySet<Long>())
    var visibleIds by mutableStateOf(emptySet<Long>())
    var deletingIds by mutableStateOf(emptySet<Long>())
    var movingIds by mutableStateOf(emptySet<Long>())

    fun cancel() { selecting = false; selectedIds = emptySet() }
    fun toggle(id: Long) {
        selecting = true
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }
    fun toggleAll() {
        selectedIds = if (selectedIds == visibleIds) emptySet() else visibleIds
    }
}

/** Each saved food is a lazy item, rather than one eager column containing the entire database. */
internal fun LazyListScope.managedFoodGroup(
    title: String,
    foods: List<JournalFood>,
    macrosEnabled: Boolean,
    management: FoodLibraryManagement,
    allFoods: List<JournalFood>,
    actions: FoodLibraryActions
) {
    if (foods.isEmpty()) return
    item(key = "group:$title") { SectionLabel(title, topPadding = 12.dp) }
    itemsIndexed(foods, key = { _, food -> "managed_food:${food.id}" }) { index, food ->
        Box(Modifier.padding(bottom = 2.dp)) {
            ManagedFoodRow(food, macrosEnabled, foodCardPosition(index, foods.size), management, allFoods, actions)
        }
    }
}

@Composable
private fun ManagedFoodRow(
    food: JournalFood,
    macrosEnabled: Boolean,
    position: CardPosition,
    management: FoodLibraryManagement,
    allFoods: List<JournalFood>,
    actions: FoodLibraryActions
) {
    val shape = cardShape(position)
    val swipe = rememberSwipeToDismissBoxState(
        positionalThreshold = { it * 0.4f },
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> management.deletingIds = setOf(food.id)
                SwipeToDismissBoxValue.StartToEnd -> actions.onArchive(setOf(food.id), !food.isArchived)
                else -> Unit
            }
            false
        }
    )
    val row: @Composable () -> Unit = {
        val selected = food.id in management.selectedIds
        Surface(shape = shape,
            color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.clip(shape)
                    .combinedClickable(
                        role = if (management.selecting) Role.Checkbox else Role.Button,
                        onClick = { if (management.selecting) management.toggle(food.id) else actions.onEdit(food) },
                        onLongClickLabel = stringResource(R.string.select),
                        onLongClick = { management.toggle(food.id) }
                    )
                    .semantics { if (management.selecting) toggleableState = ToggleableState(selected) }
                    .heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (management.selecting) {
                    Checkbox(checked = selected, onCheckedChange = null)
                    Spacer(Modifier.width(12.dp))
                } else {
                    IconTile(Icons.Default.Restaurant, tint = if (food.isArchived) MaterialTheme.colorScheme.onSurfaceVariant
                        else androidx.compose.ui.graphics.Color(food.accentColor))
                    Spacer(Modifier.width(IconTileDefaults.Gap))
                }
                Column(Modifier.weight(1f).alpha(if (food.isArchived) 0.6f else 1f)) {
                    Text(food.displayName, style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(foodSummary(food.toFoodItem(), macrosEnabled), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!management.selecting) FoodActionsMenu(listOf(food), allFoods, management, actions)
            }
        }
    }
    if (management.selecting) row() else {
        SwipeToDismissBox(state = swipe, backgroundContent = {
            val deleting = swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Box(Modifier.fillMaxSize().clip(shape)
                .background(if (deleting) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 24.dp),
                contentAlignment = if (deleting) Alignment.CenterEnd else Alignment.CenterStart) {
                Icon(if (deleting) Icons.Default.Delete else if (food.isArchived) Icons.Default.CheckCircle else Icons.Default.Block,
                    contentDescription = null,
                    tint = if (deleting) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }) { row() }
    }
}

@Composable
internal fun FoodActionsMenu(
    selected: List<JournalFood>,
    foods: List<JournalFood>,
    management: FoodLibraryManagement,
    actions: FoodLibraryActions,
    includeDelete: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    val ids = selected.mapTo(HashSet()) { it.id }
    val canMove = selected.isNotEmpty() && selected.all { it.kept && !it.isArchived } &&
        foods.any { it.kept && !it.isArchived && it.id !in ids }
    Box {
        IconButtonTooltip(stringResource(R.string.food_actions)) {
            IconButton(onClick = { expanded = true }, enabled = selected.isNotEmpty(), shapes = IconButtonDefaults.shapes()) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.food_actions))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (selected.size == 1) DropdownMenuItem(text = { Text(stringResource(R.string.edit)) },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                onClick = { expanded = false; management.cancel(); actions.onEdit(selected.single()) })
            if (canMove) DropdownMenuItem(text = { Text(stringResource(R.string.food_move)) },
                leadingIcon = { Icon(Icons.Default.LowPriority, contentDescription = null) },
                onClick = { expanded = false; management.movingIds = ids })
            if (selected.any { !it.isArchived }) DropdownMenuItem(text = { Text(stringResource(R.string.disable)) },
                leadingIcon = { Icon(Icons.Default.Block, contentDescription = null) },
                onClick = { expanded = false; actions.onArchive(ids, true); management.cancel() })
            if (selected.any { it.isArchived }) DropdownMenuItem(text = { Text(stringResource(R.string.enable)) },
                leadingIcon = { Icon(Icons.Default.CheckCircle, contentDescription = null) },
                onClick = { expanded = false; actions.onArchive(ids, false); management.cancel() })
            if (includeDelete) DropdownMenuItem(text = { Text(stringResource(R.string.delete)) },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                onClick = { expanded = false; management.deletingIds = ids })
        }
    }
}

@Composable
internal fun FoodManagementToolbar(foods: List<JournalFood>, management: FoodLibraryManagement, actions: FoodLibraryActions) {
    val selected = foods.filter { it.id in management.selectedIds }
    SelectionToolbar(selectedCount = selected.size,
        allSelected = management.visibleIds.isNotEmpty() && management.selectedIds == management.visibleIds,
        onCancel = management::cancel, onToggleSelectAll = management::toggleAll) {
        FoodActionsMenu(selected, foods, management, actions, includeDelete = false)
        IconButtonTooltip(stringResource(R.string.delete)) {
            FilledTonalIconButton(onClick = { management.deletingIds = management.selectedIds },
                enabled = selected.isNotEmpty(), shapes = IconButtonDefaults.shapes(),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
            }
        }
    }
}

@Composable
internal fun FoodManagementDialogs(foods: List<JournalFood>, management: FoodLibraryManagement, actions: FoodLibraryActions) {
    val deleting = foods.filter { it.id in management.deletingIds }
    if (deleting.isNotEmpty()) AlertDialog(
        onDismissRequest = { management.deletingIds = emptySet() },
        title = { Text(stringResource(R.string.food_delete_title, deleting.size)) },
        text = { Text(stringResource(R.string.this_action_can_be_undone)) },
        confirmButton = {
            TextButton(onClick = {
                actions.onDelete(deleting.mapTo(HashSet()) { it.id })
                management.deletingIds = emptySet(); management.cancel()
            }, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) {
                Text(stringResource(R.string.delete))
            }
        },
        dismissButton = {
            TextButton(onClick = { management.deletingIds = emptySet() },
                shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonContentPadding) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
    val moving = foods.filter { it.id in management.movingIds && it.kept && !it.isArchived }
    if (moving.isNotEmpty()) {
        val ids = moving.mapTo(HashSet()) { it.id }
        FoodMoveSheet(foods.filter { it.kept && !it.isArchived && it.id !in ids }, moving.size,
            onDismiss = { management.movingIds = emptySet() },
            onMove = { before ->
                actions.onMove(ids, before); management.movingIds = emptySet(); management.cancel()
            })
    }
}

@Composable
internal fun FoodMoveSheet(destinations: List<JournalFood>, count: Int, onDismiss: () -> Unit, onMove: (Long?) -> Unit) {
    StableModalBottomSheet(onDismissRequest = onDismiss, adaptiveHeight = true,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { CompactSheetDragHandle() }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.food_move_title, count), style = MaterialTheme.typography.headlineSmallEmphasized,
                modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss, shapes = IconButtonDefaults.shapes()) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
            }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item {
                AdaptiveButtonRow(Modifier.padding(bottom = 12.dp)) {
                    OutlinedButton(onClick = { onMove(destinations.firstOrNull()?.id) },
                        shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.ContentPadding,
                        modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.food_move_first)) }
                    OutlinedButton(onClick = { onMove(null) }, shapes = ButtonDefaults.shapes(),
                        contentPadding = ButtonDefaults.ContentPadding, modifier = Modifier.heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.food_move_last))
                    }
                }
            }
            itemsIndexed(destinations, key = { _, food -> food.id }) { index, food ->
                SettingsItem(title = stringResource(R.string.food_move_before, food.displayName),
                    icon = Icons.Default.LowPriority, position = foodCardPosition(index, destinations.size),
                    onClick = { onMove(food.id) })
            }
        }
    }
}
