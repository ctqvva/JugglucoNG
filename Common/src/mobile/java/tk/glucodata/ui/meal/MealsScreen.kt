@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package tk.glucodata.ui.meal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import tk.glucodata.R
import tk.glucodata.data.meal.Meal
import tk.glucodata.data.meal.MealItem
import tk.glucodata.data.meal.MealMath
import tk.glucodata.data.meal.MealRepository
import tk.glucodata.ui.components.CardPosition
import tk.glucodata.ui.components.SectionLabel
import tk.glucodata.ui.components.SettingsItem

/**
 * All meals, open ones first. A meal is created while cooking and filled by scanning; nothing here
 * writes to the journal — that happens from the meal's own screen when something is eaten.
 */
@Composable
fun MealsScreen(
    navController: NavController,
    repository: MealRepository = remember { MealRepository() }
) {
    val meals by repository.observeMeals().collectAsStateWithLifecycle(initialValue = emptyList())
    val allItems by repository.observeAllItems().collectAsStateWithLifecycle(initialValue = emptyList())
    val itemsByMeal = remember(allItems) { allItems.groupBy { it.mealId } }
    val scope = rememberCoroutineScope()
    val defaultLabel = stringResource(R.string.meal_default_label)
    val openMeals = remember(meals) { meals.filter { !it.isArchived } }
    val doneMeals = remember(meals) { meals.filter { it.isArchived } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.meal_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    scope.launch {
                        val id = repository.createMeal(defaultLabel)
                        navController.navigate("journal/meals/$id")
                    }
                },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.meal_new)) }
            )
        }
    ) { padding ->
        if (meals.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(
                        imageVector = Icons.Default.Restaurant,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = stringResource(R.string.meal_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (openMeals.isNotEmpty()) {
                item(key = "open_label") { SectionLabel(stringResource(R.string.meal_open_section), topPadding = 8.dp) }
                items(openMeals, key = { "open_${it.id}" }) { meal ->
                    MealRow(
                        meal = meal,
                        items = itemsByMeal[meal.id].orEmpty(),
                        position = cardPosition(openMeals.indexOf(meal), openMeals.size),
                        onClick = { navController.navigate("journal/meals/${meal.id}") }
                    )
                }
            }
            if (doneMeals.isNotEmpty()) {
                item(key = "done_label") { SectionLabel(stringResource(R.string.meal_archived_section)) }
                items(doneMeals, key = { "done_${it.id}" }) { meal ->
                    MealRow(
                        meal = meal,
                        items = itemsByMeal[meal.id].orEmpty(),
                        position = cardPosition(doneMeals.indexOf(meal), doneMeals.size),
                        onClick = { navController.navigate("journal/meals/${meal.id}") }
                    )
                }
            }
        }
    }
}

@Composable
private fun MealRow(meal: Meal, items: List<MealItem>, position: CardPosition, onClick: () -> Unit) {
    val totals = remember(items) { MealMath.totals(items) }
    val dateText = remember(meal.updatedAt) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(meal.updatedAt))
    }
    val subtitle = buildString {
        append(stringResource(R.string.meal_items_count, items.size))
        if (items.isNotEmpty()) {
            append(" · ")
            append(stringResource(R.string.meal_carbs_short, MealFormat.grams(totals.facts.carbsGrams)))
        }
        append(" · ")
        append(dateText)
    }
    SettingsItem(
        title = meal.label,
        subtitle = subtitle,
        showArrow = true,
        onClick = onClick,
        icon = Icons.Default.Restaurant,
        iconTint = MaterialTheme.colorScheme.secondary,
        position = position,
        modifier = Modifier.fillMaxWidth()
    )
}

internal fun cardPosition(index: Int, size: Int): CardPosition = when {
    size <= 1 -> CardPosition.SINGLE
    index == 0 -> CardPosition.TOP
    index == size - 1 -> CardPosition.BOTTOM
    else -> CardPosition.MIDDLE
}
