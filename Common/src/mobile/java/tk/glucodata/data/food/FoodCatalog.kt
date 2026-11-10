package tk.glucodata.data.food

import android.content.Context
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tk.glucodata.data.journal.JournalFood

/**
 * One search over everything on the device: the person's foods, the bundled everyday foods in
 * their language, and the English composition tables. Open Food Facts is asked separately and
 * only on request ([OpenFoodFactsClient]).
 */
object FoodCatalog {
    const val MIN_QUERY_LENGTH = 2
    private const val LIBRARY_LIMIT = 12
    private const val COMMON_LIMIT = 12
    private const val REFERENCE_LIMIT = 20
    private const val RECENT_LIMIT = 6
    private const val MINE_LIMIT = 12

    /** What a typed query found, shelf by shelf. */
    data class Shelves(
        val library: List<FoodItem> = emptyList(),
        val common: List<FoodItem> = emptyList(),
        val reference: List<FoodItem> = emptyList()
    ) {
        val isEmpty: Boolean get() = library.isEmpty() && common.isEmpty() && reference.isEmpty()
    }

    /** What to offer before anything is typed. */
    data class Suggestions(
        val recent: List<FoodItem> = emptyList(),
        val mine: List<FoodItem> = emptyList()
    )

    fun appLocale(context: Context): Locale =
        context.resources.configuration.locales.takeIf { !it.isEmpty }?.get(0) ?: Locale.getDefault()

    /** The foods eaten most recently, then the rest of the saved ones in the library's order. */
    fun suggestions(library: List<JournalFood>): Suggestions {
        val active = library.filter { !it.isArchived }
        val recent = active.filter { it.lastUsedAt > 0L }
            .sortedByDescending { it.lastUsedAt }
            .take(RECENT_LIMIT)
        val recentIds = recent.mapTo(HashSet()) { it.id }
        val mine = active.filter { it.kept && it.id !in recentIds }.take(MINE_LIMIT)
        return Suggestions(recent.map { it.toFoodItem() }, mine.map { it.toFoodItem() })
    }

    fun searchLibrary(library: List<JournalFood>, query: String, limit: Int = LIBRARY_LIMIT): List<JournalFood> {
        val indexed = library.map { food ->
            food to FoodSearch.IndexedName(listOfNotNull(food.displayName, food.brand).joinToString(" "))
        }
        // A food eaten often is the likelier answer among equals.
        return FoodSearch.search(indexed.sortedByDescending { it.first.useCount }, query, limit) { it.second }
            .map { it.first }
    }

    suspend fun search(context: Context, query: String, library: List<JournalFood>): Shelves {
        val needle = query.trim()
        if (needle.isEmpty()) return Shelves()
        return withContext(Dispatchers.Default) {
            val active = library.filter { !it.isArchived }
            val mine = searchLibrary(active, needle).map { it.toFoodItem() }
            if (needle.length < MIN_QUERY_LENGTH) return@withContext Shelves(library = mine)
            // A food already in the library is shown once, as the person's own.
            val owned = library.mapNotNullTo(HashSet()) { it.sourceKey }
            val common = CommonFoods.get(context, appLocale(context))
                .search(needle, COMMON_LIMIT + owned.size)
                .filter { it.key !in owned }
                .take(COMMON_LIMIT)
            val reference = ReferenceFoods.search(needle, REFERENCE_LIMIT + owned.size)
                .filter { it.key !in owned }
                .take(REFERENCE_LIMIT)
            Shelves(mine, common, reference)
        }
    }
}
