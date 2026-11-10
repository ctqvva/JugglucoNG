@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package tk.glucodata.ui.journal

import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.launch
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
    actions: FoodLibraryActions,
    reorder: FoodLibraryReorder? = null
) {
    if (foods.isEmpty()) return
    item(key = "group:$title") { SectionLabel(title, topPadding = 12.dp) }
    itemsIndexed(foods, key = { _, food -> "managed_food:${food.id}" }) { _, food ->
        val content: @Composable (Modifier) -> Unit = { handle ->
            Box(Modifier.padding(bottom = 8.dp)) {
                ManagedFoodRow(food, macrosEnabled, management, actions, handle)
            }
        }
        if (reorder != null) {
            FoodReorderableRow(food, reorder, enabled = !management.selecting, content)
        } else content(Modifier)

    }
}

@Composable
private fun ManagedFoodRow(
    food: JournalFood,
    macrosEnabled: Boolean,
    management: FoodLibraryManagement,
    actions: FoodLibraryActions,
    handle: Modifier
) {
    val shape = RoundedCornerShape(12.dp)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var width by remember(food.id) { mutableIntStateOf(0) }
    var pending by remember(food.id) { mutableStateOf(false) }
    // A dismissed state must not be saved and restored when Undo recreates this row.
    val swipe = remember(food.id) {
        SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled) { it * 0.4f }
    }
    val latestFood by rememberUpdatedState(food)
    val latestActions by rememberUpdatedState(actions)
    val armed by remember {
        derivedStateOf {
            val offset = runCatching { swipe.requireOffset() }.getOrDefault(0f)
            if (width > 0 && kotlin.math.abs(offset) >= width * 0.4f) swipe.dismissDirection
            else SwipeToDismissBoxValue.Settled
        }
    }
    LaunchedEffect(armed) {
        if (armed != SwipeToDismissBoxValue.Settled) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    LaunchedEffect(management.selecting, food.isArchived) {
        if (management.selecting || swipe.settledValue != SwipeToDismissBoxValue.Settled) {
            swipe.snapTo(SwipeToDismissBoxValue.Settled)
        }
    }
    LaunchedEffect(swipe.settledValue) {
        val value = swipe.settledValue
        if (value == SwipeToDismissBoxValue.Settled || pending || management.selecting) return@LaunchedEffect
        pending = true
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        when (value) {
            SwipeToDismissBoxValue.EndToStart -> latestActions.onDelete(setOf(food.id)) { success ->
                if (!success) scope.launch { pending = false; swipe.reset() }
            }
            SwipeToDismissBoxValue.StartToEnd -> latestActions.onArchive(setOf(food.id), !latestFood.isArchived) {
                scope.launch { pending = false; swipe.reset() }
            }
            else -> Unit
        }
    }
    val selected = food.id in management.selectedIds
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromEndToStart = !food.isBuiltIn,
        gesturesEnabled = !management.selecting && !pending,
        modifier = Modifier.onSizeChanged { width = it.width },
        backgroundContent = {
            val direction = swipe.dismissDirection
            val deleting = direction == SwipeToDismissBoxValue.EndToStart
            Box(Modifier.fillMaxSize().clip(shape)
                .background(when (direction) {
                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.error
                    SwipeToDismissBoxValue.StartToEnd -> if (food.isArchived) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary
                    else -> androidx.compose.ui.graphics.Color.Transparent
                }).padding(horizontal = 24.dp),
                contentAlignment = if (deleting) Alignment.CenterEnd else Alignment.CenterStart) {
                Icon(if (deleting) Icons.Default.Delete else if (food.isArchived) Icons.Default.Check else Icons.Default.Close,
                    contentDescription = null, tint = androidx.compose.ui.graphics.Color.White)
            }
        }
    ) {
        Surface(shape = shape,
            color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceContainer,
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
                    .padding(16.dp).defaultMinSize(minHeight = 48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Fixed slots keep both title wrapping and row height unchanged during selection.
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    SelectionIndicator(management.selecting, selected, gap = 0.dp)
                    if (!management.selecting) IconTile(Icons.Default.Restaurant,
                        tint = if (food.isArchived) MaterialTheme.colorScheme.onSurfaceVariant
                            else androidx.compose.ui.graphics.Color(food.accentColor))
                }
                Spacer(Modifier.width(IconTileDefaults.Gap))
                Column(Modifier.weight(1f).alpha(if (food.isArchived) 0.6f else 1f)) {
                    Text(food.displayName, style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(foodSummary(food.toFoodItem(), macrosEnabled), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(handle.size(48.dp), contentAlignment = Alignment.Center) {
                    if (!management.selecting && handle != Modifier) Icon(Icons.Default.DragHandle,
                        contentDescription = stringResource(R.string.food_reorder),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun FoodManagementToolbar(foods: List<JournalFood>, management: FoodLibraryManagement, actions: FoodLibraryActions, modifier: Modifier = Modifier) {
    val selected = foods.filter { it.id in management.selectedIds }
    SelectionToolbar(modifier = modifier, selectedCount = selected.size,
        allSelected = management.visibleIds.isNotEmpty() && management.selectedIds == management.visibleIds,
        onCancel = management::cancel, onToggleSelectAll = management::toggleAll) {
        IconButtonTooltip(stringResource(R.string.disable)) {
            IconButton(onClick = { actions.onArchive(management.selectedIds, true) {}; management.cancel() },
                enabled = selected.any { !it.isArchived }, shapes = IconButtonDefaults.shapes()) {
                Icon(Icons.Default.Block, contentDescription = stringResource(R.string.disable))
            }
        }
        IconButtonTooltip(stringResource(R.string.enable)) {
            IconButton(onClick = { actions.onArchive(management.selectedIds, false) {}; management.cancel() },
                enabled = selected.any { it.isArchived }, shapes = IconButtonDefaults.shapes()) {
                Icon(Icons.Default.CheckCircle, contentDescription = stringResource(R.string.enable))
            }
        }
        IconButtonTooltip(stringResource(R.string.delete)) {
            FilledTonalIconButton(onClick = { management.deletingIds = management.selectedIds },
                enabled = selected.isNotEmpty() && selected.none { it.isBuiltIn }, shapes = IconButtonDefaults.shapes(),
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
    val deleting = foods.filter { it.id in management.deletingIds && !it.isBuiltIn }
    if (deleting.isNotEmpty()) AlertDialog(
        onDismissRequest = { management.deletingIds = emptySet() },
        title = { Text(stringResource(R.string.food_delete_title, deleting.size)) },
        text = { Text(stringResource(R.string.this_action_can_be_undone)) },
        confirmButton = {
            TextButton(onClick = {
                actions.onDelete(deleting.mapTo(HashSet()) { it.id }) {}
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
}
