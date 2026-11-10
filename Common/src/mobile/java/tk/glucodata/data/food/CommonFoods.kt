package tk.glucodata.data.food

import android.content.Context
import java.io.BufferedReader
import java.io.Reader
import java.util.Locale

/**
 * The bundled everyday foods: a couple of hundred things people actually eat, with a name in
 * every language the app speaks and the household measures they come in.
 *
 * The table is `assets/foods/staples.tsv`, written by `tools/foods/build_staples.py` from
 * USDA FoodData Central (SR Legacy, public domain). Values are per 100 g; carbohydrate has the
 * fibre taken out, the way a European label counts it.
 */
class CommonFoods internal constructor(
    val language: String,
    private val foods: List<Entry>
) {
    internal class Entry(
        val item: FoodItem,
        val localName: FoodSearch.IndexedName,
        /** The English name too, so "rice" still finds рис. Null when the language is English. */
        val englishName: FoodSearch.IndexedName?
    )

    val size: Int get() = foods.size

    fun search(query: String, limit: Int): List<FoodItem> {
        val local = FoodSearch.search(foods, query, limit) { it.localName }
        if (local.size >= limit || foods.firstOrNull()?.englishName == null) return local.map { it.item }
        val seen = local.mapTo(HashSet()) { it.item.key }
        val english = FoodSearch.search(foods.filter { it.item.key !in seen }, query, limit - local.size) {
            it.englishName ?: it.localName
        }
        return (local + english).map { it.item }
    }

    fun byKey(key: String): FoodItem? = foods.firstOrNull { it.item.key == key }?.item

    companion object {
        const val ASSET_PATH = "foods/staples.tsv"
        const val KEY_PREFIX = "common:"
        private const val ENGLISH = "en"
        private const val FIRST_NAME_COLUMN = 7

        @Volatile
        private var cached: CommonFoods? = null

        /** The table in the language of [locale], read once per language. */
        fun get(context: Context, locale: Locale): CommonFoods {
            val language = locale.language.lowercase(Locale.ROOT)
            cached?.takeIf { it.language == language }?.let { return it }
            return synchronized(this) {
                cached?.takeIf { it.language == language } ?: runCatching {
                    context.assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { parse(it, language) }
                }.getOrElse {
                    tk.glucodata.Log.stack("CommonFoods", "read $ASSET_PATH", it)
                    CommonFoods(language, emptyList())
                }.also { cached = it }
            }
        }

        /** Parses the table; a language it has no column for falls back to English. */
        fun parse(reader: Reader, language: String): CommonFoods {
            val lines = BufferedReader(reader).lineSequence().iterator()
            if (!lines.hasNext()) return CommonFoods(language, emptyList())
            val header = lines.next().split('\t')
            val englishColumn = header.indexOf(ENGLISH)
            val localColumn = header.indexOf(language).takeIf { it >= FIRST_NAME_COLUMN } ?: englishColumn
            if (englishColumn < FIRST_NAME_COLUMN) return CommonFoods(language, emptyList())
            val entries = ArrayList<Entry>()
            for (line in lines) {
                val cells = line.split('\t')
                if (cells.size <= maxOf(englishColumn, localColumn)) continue
                val carbs = cells[1].toFloatOrNull() ?: continue
                val name = cells[localColumn].ifBlank { cells[englishColumn] }
                entries += Entry(
                    item = FoodItem(
                        key = KEY_PREFIX + cells[0],
                        source = FoodSource.COMMON,
                        name = name,
                        referenceGrams = 100f,
                        carbs = carbs,
                        protein = cells[2].toFloatOrNull(),
                        fat = cells[3].toFloatOrNull(),
                        fiber = cells[4].toFloatOrNull(),
                        kcal = cells[5].toFloatOrNull(),
                        portions = FoodPortions.decode(cells[6])
                    ),
                    localName = FoodSearch.IndexedName(name),
                    englishName = cells[englishColumn]
                        .takeIf { localColumn != englishColumn }
                        ?.let { FoodSearch.IndexedName(it) }
                )
            }
            return CommonFoods(language, entries)
        }
    }
}
