package tk.glucodata.ui.meal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.launch
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
fun rememberNewMealAction(
    navController: NavController,
    repository: MealRepository = remember { MealRepository() }
): () -> Unit {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    return {
        scope.launch {
            val id = repository.createMeal(context.getString(suggestedMealTitleRes()))
            navController.navigate("journal/meals/$id")
        }
    }
}
