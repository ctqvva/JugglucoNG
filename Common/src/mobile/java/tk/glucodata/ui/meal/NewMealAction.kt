package tk.glucodata.ui.meal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import tk.glucodata.R
import tk.glucodata.data.meal.Meal
import tk.glucodata.data.meal.MealRepository

/**
 * The quick-add gesture for meals: create one and open it, so scanning can start right away.
 * Used by the "+" menus on the journal and the dashboard; the meal list stays one level up.
 */
/** The most recently edited unarchived meal without a saved eating entry. */
@Composable
fun rememberCurrentMeal(): Meal? {
    val repository = remember { MealRepository() }
    val meal by repository.observeCurrentMeal().collectAsStateWithLifecycle(initialValue = null)
    return meal
}

@Composable
fun rememberNewMealAction(navController: NavController): () -> Unit {
    val scope = rememberCoroutineScope()
    val repository = remember { MealRepository() }
    val label = stringResource(R.string.meal_default_label)
    return {
        scope.launch {
            val id = repository.createMeal(label)
            navController.navigate("journal/meals/$id")
        }
    }
}
