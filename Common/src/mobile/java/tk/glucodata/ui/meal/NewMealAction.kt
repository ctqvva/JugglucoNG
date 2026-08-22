package tk.glucodata.ui.meal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import tk.glucodata.R
import tk.glucodata.data.meal.MealRepository

/**
 * The quick-add gesture for meals: create one and open it, so scanning can start right away.
 * Used by the "+" menus on the journal and the dashboard; the meal list stays one level up.
 */
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
