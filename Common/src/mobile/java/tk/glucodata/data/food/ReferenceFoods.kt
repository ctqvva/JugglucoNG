package tk.glucodata.data.food

import java.util.Locale
import tk.glucodata.Natives

/**
 * The composition tables compiled into the native library (the UK "Composition of Foods"
 * dataset Juggluco has always shipped): about 2,900 foods, per 100 g, named in English.
 *
 * The names are read out once and matched here with [FoodSearch], the same as every other
 * shelf, instead of being handed to the native regex search.
 */
object ReferenceFoods {
    const val KEY_PREFIX = "ref:"
    private const val TAG = "ReferenceFoods"

    private class Columns(val carbs: Int, val protein: Int?, val fat: Int?, val fiber: Int?, val kcal: Int?)

    private class Index(val names: List<Pair<Int, FoodSearch.IndexedName>>, val columns: Columns)

    @Volatile
    private var index: Index? = null

    private fun index(): Index? {
        index?.let { return it }
        return synchronized(this) {
            index ?: runCatching { buildIndex() }
                .onFailure { tk.glucodata.Log.stack(TAG, "index", it) }
                .getOrNull()
                ?.also { index = it }
        }
    }

    private fun buildIndex(): Index {
        val count = Natives.foodnr().coerceAtLeast(0)
        val names = ArrayList<Pair<Int, FoodSearch.IndexedName>>(count)
        for (id in 0 until count) {
            val label = Natives.idfoodlabel(id)?.trim().orEmpty()
            if (label.isNotEmpty()) names += id to FoodSearch.IndexedName(label)
        }
        val labels = Natives.getcomponentlabels().orEmpty().map { it.orEmpty().lowercase(Locale.ROOT) }
        val units = Natives.getcomponentunits().orEmpty().map { it.orEmpty().lowercase(Locale.ROOT) }
        fun column(vararg needles: String, unit: String? = null): Int? = labels.indices.firstOrNull { i ->
            needles.any { labels[i].contains(it) } && (unit == null || units.getOrNull(i)?.trim() == unit)
        }
        return Index(
            names = names,
            columns = Columns(
                carbs = column("carbohydrate") ?: 0,
                protein = column("protein"),
                // "Fat" before "Saturated fat" in the table; an exact match keeps it that way.
                fat = labels.indexOf("fat").takeIf { it >= 0 } ?: column("fat"),
                fiber = column("fibre", "fiber"),
                kcal = column("energy", unit = "kcal")
            )
        )
    }

    /** Blocking (JNI); call off the main thread. */
    fun search(query: String, limit: Int): List<FoodItem> {
        val index = index() ?: return emptyList()
        return FoodSearch.search(index.names, query, index.names.size) { it.second }
            .asSequence()
            .mapNotNull { (id, name) -> item(id, name.name, index.columns) }
            .take(limit).toList()
    }

    private fun item(id: Int, name: String, columns: Columns): FoodItem? {
        val components = runCatching { Natives.getcomponents(id) }.getOrNull() ?: return null
        // Thousandths of the unit; negative values are the table's "unknown" and "trace".
        fun value(column: Int?): Float? = column
            ?.let { components.getOrNull(it) }
            ?.takeIf { it >= 0 }
            ?.let { it / 1000f }
        val carbs = value(columns.carbs) ?: return null
        return FoodItem(
            key = KEY_PREFIX + id,
            source = FoodSource.REFERENCE,
            name = name,
            referenceGrams = 100f,
            carbs = carbs,
            protein = value(columns.protein),
            fat = value(columns.fat),
            fiber = value(columns.fiber),
            kcal = value(columns.kcal)
        )
    }
}
