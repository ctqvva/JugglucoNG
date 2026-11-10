package tk.glucodata.ui.journal

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.DragGestureDetector
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyListState
import tk.glucodata.R
import tk.glucodata.data.journal.JournalFood

internal typealias MoveFoods = (Set<Long>, Long?, (Boolean) -> Unit) -> Unit

/** Horizontal movement belongs to swipe actions, even when it starts on the reorder handle. */
private object VerticalFoodDragDetector : DragGestureDetector {
    override suspend fun PointerInputScope.detect(
        onDragStart: (Offset) -> Unit,
        onDragEnd: () -> Unit,
        onDragCancel: () -> Unit,
        onDrag: (PointerInputChange, Offset) -> Unit
    ) {
        detectVerticalDragGestures(onDragStart, onDragEnd, onDragCancel) { change, delta ->
            onDrag(change, Offset(0f, delta))
        }
    }
}

/** Only IDs are previewed during a drag. Nutrient definitions always come from the live library. */
@Stable
internal class FoodDragOrder {
    var preview by mutableStateOf<List<Long>?>(null)
    var draggingId by mutableStateOf<Long?>(null)
    private var original = emptyList<Long>()

    fun order(current: List<Long>): List<Long> = preview?.filter { it in current }?.let { known ->
        known + current.filter { it !in known }
    } ?: current

    fun start(id: Long, current: List<Long>) {
        original = order(current)
        preview = original
        draggingId = id
    }

    fun move(from: Long, to: Long, current: List<Long>) {
        val ids = order(current).toMutableList()
        val fromIndex = ids.indexOf(from)
        val toIndex = ids.indexOf(to)
        if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) return
        ids.add(toIndex, ids.removeAt(fromIndex))
        preview = ids
    }

    fun finish(current: List<Long>, persist: MoveFoods) {
        val id = draggingId ?: return
        val ids = order(current)
        draggingId = null
        val index = ids.indexOf(id)
        if (index < 0 || ids == original) { preview = null; return }
        persist(setOf(id), ids.getOrNull(index + 1)) { success -> if (!success) preview = null }
    }
}

internal class FoodLibraryReorder(
    val state: ReorderableLazyListState,
    val draft: FoodDragOrder,
    val ids: List<Long>,
    val persist: MoveFoods
) {
    fun ordered(foods: List<JournalFood>): List<JournalFood> {
        val byId = foods.associateBy { it.id }
        return draft.order(foods.map { it.id }).mapNotNull(byId::get)
    }
}

@Composable
internal fun rememberFoodLibraryReorder(list: LazyListState, foods: List<JournalFood>, persist: MoveFoods): FoodLibraryReorder {
    val draft = remember { FoodDragOrder() }
    val ids = foods.map { it.id }
    val state = rememberReorderableLazyListState(list) { from, to ->
        val fromId = (from.key as? String)?.removePrefix("managed_food:")?.toLongOrNull()
        val toId = (to.key as? String)?.removePrefix("managed_food:")?.toLongOrNull()
        if (fromId != null && toId != null) draft.move(fromId, toId, ids)
    }
    LaunchedEffect(foods) {
        if (draft.draggingId == null) draft.preview = null
    }
    return FoodLibraryReorder(state, draft, ids, persist)
}

@Composable
internal fun LazyItemScope.FoodReorderableRow(
    food: JournalFood,
    reorder: FoodLibraryReorder,
    enabled: Boolean,
    content: @Composable (Modifier) -> Unit
) {
    val moveUp = stringResource(R.string.food_move_up)
    val moveDown = stringResource(R.string.food_move_down)
    ReorderableItem(reorder.state, key = "managed_food:${food.id}", enabled = enabled) {
        val index = reorder.draft.order(reorder.ids).indexOf(food.id)
        val accessibilityActions = buildList {
            if (enabled && index > 0) add(CustomAccessibilityAction(moveUp) {
                reorder.draft.start(food.id, reorder.ids)
                reorder.draft.move(food.id, reorder.draft.order(reorder.ids)[index - 1], reorder.ids)
                reorder.draft.finish(reorder.ids, reorder.persist)
                true
            })
            if (enabled && index >= 0 && index < reorder.ids.lastIndex) add(CustomAccessibilityAction(moveDown) {
                reorder.draft.start(food.id, reorder.ids)
                reorder.draft.move(food.id, reorder.draft.order(reorder.ids)[index + 1], reorder.ids)
                reorder.draft.finish(reorder.ids, reorder.persist)
                true
            })
        }
        content(Modifier.draggableHandle(
            enabled = enabled,
            dragGestureDetector = VerticalFoodDragDetector,
            onDragStarted = { reorder.draft.start(food.id, reorder.ids) },
            onDragStopped = { reorder.draft.finish(reorder.ids, reorder.persist) }
        ).testTag("food_drag:${food.id}").semantics(mergeDescendants = true) { customActions = accessibilityActions })
    }
}
