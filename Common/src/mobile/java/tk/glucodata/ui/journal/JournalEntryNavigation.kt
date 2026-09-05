package tk.glucodata.ui.journal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.launch
import tk.glucodata.data.journal.JournalEntry
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.meal.MealRepository

/** A meal icon opens its meal; other entry icons open the entry editor. */
@Composable
internal fun rememberJournalEntryAction(
    onOpenMeal: ((Long) -> Unit)?,
    onEdit: (JournalEntry) -> Unit
): (JournalEntry) -> Unit {
    val repository = remember { MealRepository() }
    val scope = rememberCoroutineScope()
    val currentOpenMeal by rememberUpdatedState(onOpenMeal)
    val currentEdit by rememberUpdatedState(onEdit)
    return { entry ->
        scope.launch {
            navigateJournalEntry(
                type = entry.type,
                mealId = entry.mealId,
                mealExists = { repository.getMeal(it) != null },
                onOpenMeal = currentOpenMeal,
                onEdit = { currentEdit(entry) }
            )
        }
    }
}

internal suspend fun navigateJournalEntry(
    type: JournalEntryType,
    mealId: Long?,
    mealExists: suspend (Long) -> Boolean,
    onOpenMeal: ((Long) -> Unit)?,
    onEdit: () -> Unit
) {
    if (type == JournalEntryType.CARBS && mealId != null && onOpenMeal != null && mealExists(mealId)) {
        onOpenMeal(mealId)
    } else {
        // Meals can be deleted while retaining their eating entries.
        onEdit()
    }
}
