package tk.glucodata.ui.journal

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tk.glucodata.R
import tk.glucodata.data.meal.MealRepository

@Composable
internal fun JournalMealLink(mealId: Long, onOpenMeal: (Long) -> Unit) {
    val repository = remember { MealRepository() }
    val mealFlow = remember(repository, mealId) { repository.observeMeal(mealId) }
    val meal by mealFlow.collectAsStateWithLifecycle(initialValue = null)
    // A retained eating entry may point to a meal that has since been deleted.
    meal?.let { linkedMeal ->
        FilledTonalButton(
            onClick = { onOpenMeal(linkedMeal.id) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Restaurant, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.meal_open_link, linkedMeal.label))
        }
    }
}
